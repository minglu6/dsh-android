package dev.dsh.pocket.app

import android.content.Context
import android.os.Build
import android.util.Base64
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class Dot { Online, Connecting, Offline, Unauthorized }

data class ChatState(
    val sessionId: String,
    val items: List<Item> = emptyList(),
    val steps: List<StepRecord> = emptyList(),
    val title: String? = null,
    val running: Boolean = false,
    val usage: Usage = Usage(0, 0, 0, 0, 0),
    val loaded: Boolean = false,
    val runStartedAt: Long? = null,
    /** Older history remains above the current window (snapshot/page hasMore). */
    val hasMore: Boolean = false,
    val firstSeq: Long = -1,
    val loadingOlder: Boolean = false,
    /** Latest agent todo list (`todo/write` / projections.todos). */
    val todos: List<TodoItem> = emptyList(),
)

data class ModelChoice(val provider: String, val model: String, val name: String, val effort: String?)

/** Minimal host task event; Android builds notification text locally. */
data class TaskEvent(
    val kind: Kind,
    val sessionId: String,
    val turn: Long = 0,
    val questionId: String? = null,
) {
    enum class Kind { Done, Question }

    companion object {
        fun parse(m: JsonObject): TaskEvent? {
            val sessionId = m["sessionId"].str() ?: return null
            val kind = when (m["kind"].str()) {
                "done" -> Kind.Done
                "question" -> Kind.Question
                else -> return null
            }
            return TaskEvent(
                kind = kind,
                sessionId = sessionId,
                turn = m["turn"].long() ?: 0L,
                questionId = m["questionId"].str(),
            )
        }
    }
}

/** Process-wide connection + session state. All mutation happens on [main]. */
@OptIn(ExperimentalCoroutinesApi::class)
object Pocket {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    lateinit var prefs: AppPrefs; private set
    lateinit var tokens: SecureTokenStore; private set

    val profiles = MutableStateFlow<List<Profile>>(emptyList())
    val status = MutableStateFlow<Map<String, Dot>>(emptyMap())
    /** Active frame route per profile (Tailscale / LAN / local WS). */
    val routes = MutableStateFlow<Map<String, Route>>(emptyMap())
    val active = MutableStateFlow<String?>(null)
    val sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val approvals = MutableStateFlow<List<Approval>>(emptyList())
    val userQuestions = MutableStateFlow<List<UserQuestion>>(emptyList())
    val archivedIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedIds = MutableStateFlow<Set<String>>(emptySet())
    /** messageId → feedback for the open session. */
    val feedback = MutableStateFlow<Map<String, MessageFeedback>>(emptyMap())
    val catalog = MutableStateFlow<ModelCatalog?>(null)
    val presets = MutableStateFlow<List<String>>(emptyList())
    val ops = MutableStateFlow<Set<String>>(emptySet())
    val chat = MutableStateFlow<ChatState?>(null)
    val permission = MutableStateFlow<Map<String, String>>(emptyMap())
    val model = MutableStateFlow<Map<String, ModelChoice>>(emptyMap())
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Transient toast-style notices (archive undo, feedback thanks, …). */
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-shot text to insert into the open composer (e.g. `@path` mention). */
    val composerInsert = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Live host PTY emulator for the open terminal sheet. */
    val terminal = AnsiTerminal(80, 24)
    /** Bumps whenever [terminal] mutates so Compose can redraw. */
    val terminalRevision = MutableStateFlow(0L)
    val terminalMeta = MutableStateFlow<TerminalMeta?>(null)
    /** attachmentId → decoded bytes cache for transcript image bubbles. */
    private val attachmentCache = ConcurrentHashMap<String, ByteArray>()
    /** Last archived session for snackbar 撤销. */
    @Volatile var lastArchivedId: String? = null
        private set

    private var client: PocketClient? = null
    private var connJob: Job? = null
    private var reducer = TranscriptReducer()
    private var subId: String? = null
    private var terminalSubId: String? = null
    /** Last panel grid size from [TerminalView]; used when creating / attaching a PTY. */
    private var wantCols = 80
    private var wantRows = 24
    private val stray = HashMap<String, MutableList<JsonObject>>()
    /** Per-session follow attempts across reconnects; capped to stop spin loops. */
    private val followAttempts = HashMap<String, Int>()
    private const val MAX_FOLLOW_ATTEMPTS = 3
    private const val FOLLOW_MAX_MESSAGES = 50
    private const val HISTORY_PAGE_SIZE = 100
    /** Slow-link hello / dial budget (ref dsh-mobile SLOW_CONNECTIONS ~120s init wait). */
    private const val CONNECT_TIMEOUT_MS = 120_000L
    /** Current reconnect backoff; reset to 1s on Online or network restore. */
    private val backoffMs = AtomicLong(1_000L)
    /** Wakes a pending reconnect delay when the network becomes available. */
    private val reconnectWake = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Collapses NetworkCallback bursts (adapted from dsh-mobile RestoreAttemptGate). */
    private val restoreGate = RestoreAttemptGate()
    /** Minimal task events from the host for local notification text. */
    val taskEvents = MutableSharedFlow<TaskEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = AppPrefs(ctx); tokens = SecureTokenStore(ctx)
        profiles.value = prefs.profiles
        active.value = prefs.activeProfile?.takeIf { id -> profiles.value.any { it.id == id } }
        active.value?.let { connect(it) }
    }

    val activeProfile get() = profiles.value.firstOrNull { it.id == active.value }

    private fun setDot(id: String, d: Dot) { status.value = status.value + (id to d) }
    private fun setRoute(id: String, r: Route?) {
        routes.value = if (r == null) routes.value - id else routes.value + (id to r)
    }

    fun connect(profileId: String) {
        connJob?.cancel(); client?.close(); client = null
        active.value = profileId; prefs.activeProfile = profileId
        sessions.value = emptyList(); chat.value = null; approvals.value = emptyList()
        userQuestions.value = emptyList(); archivedIds.value = emptySet(); pinnedIds.value = emptySet()
        feedback.value = emptyMap(); attachmentCache.clear()
        setRoute(profileId, null)
        connJob = main.launch { runConnection(profileId) }
    }

    private fun factoriesFor(p: Profile): List<TransportFactory> {
        if (p.needsRepair) return emptyList()
        return transportPlan(p.host, p.port)
    }

    private suspend fun runConnection(profileId: String) {
        backoffMs.set(1_000L)
        while (currentCoroutineContext().isActive) {
            val p = profiles.value.firstOrNull { it.id == profileId } ?: return
            if (p.needsRepair) {
                setDot(p.id, Dot.Unauthorized)
                return
            }
            val token = tokens.get(p.id) ?: run { setDot(p.id, Dot.Unauthorized); return }
            setDot(p.id, Dot.Connecting)
            val c = PocketClient()
            try {
                val factories = factoriesFor(p)
                if (factories.isEmpty()) {
                    setDot(p.id, Dot.Offline)
                    errors.tryEmit("地址无效，请重新配对（需要 host:port）")
                    return
                }
                val hello = c.connect(factories, Auth.Token(token), CONNECT_TIMEOUT_MS)
                client = c; ops.value = hello.ops
                setRoute(p.id, c.route ?: hello.route)
                setDot(p.id, Dot.Online); backoffMs.set(1_000L)
                coroutineScope {
                    launch {
                        while (isActive) {
                            delay(1_000)
                            c.route?.let { setRoute(p.id, it) }
                        }
                    }
                    launch { c.events.collect { onPush(it) } }
                    launch { refreshAll(c) }
                    c.state.first { it == PocketClient.State.Closed }
                    cancel()
                }
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) { c.close(); throw e }
            } catch (e: Exception) { /* fall through to retry */ }
            client = null; c.close(); setRoute(profileId, null)
            val reason = c.closeReason.orEmpty() + ""
            if ("4401" in reason || "4403" in reason || "auth/" in reason) { setDot(p.id, Dot.Unauthorized); return }
            // Oversized iroh frame: surface the error and stop spinning the open chat.
            if ("line over limit" in reason || "over limit" in reason) {
                errors.tryEmit("消息帧过大，无法同步会话")
                chat.value = chat.value?.copy(loaded = true)
            }
            setDot(p.id, Dot.Offline)
            val wait = backoffMs.get()
            backoffMs.updateAndGet { (it * 2).coerceAtMost(30_000) }
            // NetworkCallback may wake this early after resetting backoff.
            withTimeoutOrNull(wait) { reconnectWake.first() }
        }
    }

    /**
     * ConnectivityManager.NetworkCallback path: reset backoff and reconnect immediately
     * when the network becomes available (adapted from dsh-mobile restore policy/gate).
     */
    fun onNetworkRestored() {
        val id = active.value ?: return
        val dot = status.value[id]
        if (!ConnectionRestorePolicy.shouldReconnect(dot)) return
        val generation = restoreGate.begin()
        backoffMs.set(1_000L)
        reconnectWake.tryEmit(Unit)
        // Stuck Connecting: drop the half-open client so the loop retries at once.
        if (dot == Dot.Connecting && restoreGate.isCurrent(generation)) {
            client?.close()
        }
    }

    private suspend fun refreshAll(c: PocketClient) {
        runCatching { sessions.value = c.listSessions().sortedByDescending { it.updatedAt } }
        runCatching { approvals.value = c.call("approval.pending")["approvals"].arr().orEmpty().mapNotNull { (it as? JsonObject)?.let(Approval::parse) } }
        runCatching { catalog.value = c.modelCatalog() }
        if ("presets.list" in ops.value) runCatching { presets.value = c.presets() }
        if ("workspace.state" in ops.value) runCatching { refreshWorkspaceState(c) }
        chat.value?.let { st ->
            refollow(c, st.sessionId, reducer.cursor.takeIf { s -> s >= 0 })
            if ("feedback.get" in ops.value) runCatching { loadFeedback(st.sessionId) }
        }
    }

    private suspend fun refreshWorkspaceState(c: PocketClient) {
        val state = c.workspaceState()
        archivedIds.value = state["archivedSessionIds"].arr().orEmpty().mapNotNull { it.str() }.toSet()
        pinnedIds.value = state["pinnedSessionIds"].arr().orEmpty().mapNotNull { it.str() }.toSet()
    }

    private fun onPush(m: JsonObject) {
        when (m["ev"].str()) {
            "follow" -> {
                val sid = m["subId"].str() ?: return
                if (sid == subId) applyFrame(m["frame"]) else stray.getOrPut(sid) { mutableListOf() }.add(m)
            }
            "follow.end" -> if (m["subId"].str() == subId) subId = null
            "follow.error" -> if (m["subId"].str() == subId) {
                subId = null
                val msg = m["error"]["message"].str()
                    ?: m["error"]["code"].str()
                    ?: "follow error"
                errors.tryEmit(msg)
                // Stop the spinner; do not keep asking for the same oversized frame.
                chat.value = chat.value?.copy(loaded = true)
            }
            "session" -> when (m["kind"].str()) {
                "added" -> SessionSummary.parse(m["summary"])?.let { s -> sessions.value = listOf(s) + sessions.value.filter { it.sessionId != s.sessionId } }
                "removed" -> sessions.value = sessions.value.filter { it.sessionId != m["sessionId"].str() }
                "status" -> { val id = m["sessionId"].str(); val r = m["running"].bool() ?: false
                    sessions.value = sessions.value.map { if (it.sessionId == id) it.copy(running = r) else it }
                    // Composer Stop uses chat.running (from the follow reducer). Cancel often clears
                    // session status before / without a folded turn/end; mirror idle into the open chat.
                    if (!r) chat.value?.takeIf { it.sessionId == id && it.running }?.let { st ->
                        reducer.markIdle()
                        chat.value = st.copy(running = false, runStartedAt = null, items = reducer.items(), steps = reducer.stepRecords())
                    }
                    client?.let { c -> main.launch { refreshTitles(c); if (!r) { delay(4000); refreshTitles(c) } } } }
                "activity" -> { val id = m["sessionId"].str(); val t = m["updatedAt"].long() ?: 0
                    sessions.value = sessions.value.map { if (it.sessionId == id) it.copy(updatedAt = t) else it }.sortedByDescending { it.updatedAt } }
                "error" -> m["message"].str()?.let { errors.tryEmit(it) }
            }
            "approval" -> { val a = Approval.parse(m); approvals.value = approvals.value.filter { it.approvalId != a.approvalId } + a }
            "approval.closed" -> approvals.value = approvals.value.filter { it.approvalId != m["approvalId"].str() }
            "user-question" -> UserQuestion.parse(m)?.let { q ->
                userQuestions.value = userQuestions.value.filter { it.questionId != q.questionId } + q
            }
            "user-question.closed" -> {
                val id = m["questionId"].str()
                userQuestions.value = userQuestions.value.filter { it.questionId != id }
            }
            "task" -> TaskEvent.parse(m)?.let { taskEvents.tryEmit(it) }
            "terminal" -> if (m["subId"].str() == terminalSubId) applyTerminalFrame(m["frame"])
            "terminal.end", "terminal.error" -> if (m["subId"].str() == terminalSubId) {
                m["error"]["message"].str()?.let { errors.tryEmit(it) }
                terminalSubId = null
            }
        }
    }

    private fun applyTerminalFrame(frame: kotlinx.serialization.json.JsonElement?) {
        when (frame["type"].str()) {
            "snapshot" -> {
                terminal.applySnapshot(frame["screen"].str().orEmpty())
                terminalRevision.value = terminal.revision
                frame["info"]?.let { info ->
                    terminalMeta.value = terminalMeta.value?.copy(
                        title = info["title"].str() ?: terminalMeta.value?.title,
                        state = info["state"].str() ?: terminalMeta.value?.state,
                    )
                }
            }
            "output" -> {
                terminal.write(frame["data"].str().orEmpty())
                terminalRevision.value = terminal.revision
            }
            "state" -> frame["info"]?.let { info ->
                terminalMeta.value = terminalMeta.value?.copy(
                    title = info["title"].str() ?: terminalMeta.value?.title,
                    state = info["state"].str() ?: terminalMeta.value?.state,
                )
            }
        }
    }

    private fun applyFrame(f: kotlinx.serialization.json.JsonElement?) {
        val st = chat.value ?: return
        val wasRunning = reducer.running
        reducer.apply(f)
        if (f["type"].str() == "snapshot") followAttempts.remove(st.sessionId)
        chat.value = st.copy(
            items = reducer.items(),
            steps = reducer.stepRecords(),
            title = reducer.title ?: st.title,
            running = reducer.running,
            usage = reducer.usage,
            loaded = true,
            hasMore = reducer.hasMore,
            firstSeq = reducer.firstSeq,
            runStartedAt = if (reducer.running) (if (wasRunning) st.runStartedAt else System.currentTimeMillis()) else null,
            todos = reducer.todos(),
        )
    }

    private suspend fun refollow(c: PocketClient, sessionId: String, afterSeq: Long?) {
        val attempts = (followAttempts[sessionId] ?: 0) + 1
        if (attempts > MAX_FOLLOW_ATTEMPTS) {
            errors.tryEmit("会话同步失败（已重试 $MAX_FOLLOW_ATTEMPTS 次）")
            chat.value = chat.value?.copy(loaded = true)
            return
        }
        followAttempts[sessionId] = attempts
        subId?.let { old -> runCatching { c.unfollow(old) } }
        subId = null
        val sid = c.follow(sessionId, afterSeq, maxMessages = FOLLOW_MAX_MESSAGES)
        subId = sid
        stray.remove(sid)?.forEach { applyFrame(it["frame"]) }
        stray.clear()
    }

    fun openSession(sessionId: String) = main.launch {
        followAttempts.remove(sessionId)
        reducer = TranscriptReducer()
        feedback.value = emptyMap()
        chat.value = ChatState(sessionId, title = sessions.value.firstOrNull { it.sessionId == sessionId }?.title)
        val c = client ?: return@launch
        runCatching { refollow(c, sessionId, null) }
            .onFailure {
                errors.tryEmit(it.message ?: "follow failed")
                chat.value = chat.value?.copy(loaded = true)
            }
        if ("permissions.get" in ops.value) runCatching { c.permission(sessionId)?.let { permission.value = permission.value + (sessionId to it) } }
        if ("feedback.get" in ops.value) runCatching { loadFeedback(sessionId) }
    }

    /**
     * Load older turns when the user scrolls to the top and [ChatState.hasMore] is true.
     * Prepends via [TranscriptReducer.prepend] (same history.page path as desktop dsh).
     */
    fun loadOlderHistory() = main.launch {
        val st = chat.value ?: return@launch
        val c = client ?: return@launch
        if (!st.hasMore || st.loadingOlder || st.firstSeq < 0) return@launch
        chat.value = st.copy(loadingOlder = true)
        try {
            val through = reducer.cursor.coerceAtLeast(st.firstSeq)
            val page = c.historyPage(
                sessionId = st.sessionId,
                throughSeq = through,
                beforeSeq = st.firstSeq,
                maxMessages = HISTORY_PAGE_SIZE,
            )
            reducer.prepend(page["records"], page["hasMore"].bool() ?: false)
            chat.value = chat.value?.copy(
                items = reducer.items(),
                steps = reducer.stepRecords(),
                hasMore = reducer.hasMore,
                firstSeq = reducer.firstSeq,
                loadingOlder = false,
            )
        } catch (e: Exception) {
            errors.tryEmit(e.message ?: "加载历史失败")
            chat.value = chat.value?.copy(loadingOlder = false)
        }
    }

    fun closeChat() = main.launch { subId?.let { s -> client?.let { runCatching { it.unfollow(s) } } }; subId = null; chat.value = null }

    /** Create a session (optionally with preset), then send [text] into it. */
    fun newSession(text: String, preset: String?, pendingPermission: String?, pendingModel: ModelChoice?, cwd: String? = null) = main.launch {
        val c = client ?: return@launch errors.tryEmit("未连接").let { }
        try {
            val id = c.createSession(cwd = cwd, agentPreset = preset)
            pendingModel?.let { m -> c.selectModel(id, m.provider, m.model, m.effort); model.value = model.value + (id to m) }
            pendingPermission?.let { p -> if ("permissions.set" in ops.value) c.setPermission(id, p)?.let { permission.value = permission.value + (id to it) } }
            openSession(id).join()
            if (text.isNotBlank()) { echo(text); c.prompt(id, text, "queue") }
        } catch (e: Exception) { errors.tryEmit(e.message ?: e.toString()) }
    }

    fun send(text: String) = main.launch {
        val c = client ?: return@launch; val st = chat.value ?: return@launch
        val mode = if (st.running) prefs.busySend.mode else "queue"
        echo(text)
        runCatching { c.prompt(st.sessionId, text, mode) }.onFailure { errors.tryEmit(it.message ?: "发送失败") }
    }

    private fun echo(text: String) {
        reducer.echo(text)
        chat.value?.let { chat.value = it.copy(items = reducer.items(), steps = reducer.stepRecords()) }
    }

    /** dsh titles a session asynchronously after the first turn; the list is the source of truth. */
    private suspend fun refreshTitles(c: PocketClient) = runCatching {
        sessions.value = c.listSessions().sortedByDescending { it.updatedAt }
        chat.value?.let { st -> sessions.value.firstOrNull { it.sessionId == st.sessionId }?.title?.let { t -> chat.value = st.copy(title = t) } }
    }

    /** Same path as desktop InputBar Stop → `sessions.cancel` / plugin `cancel`. */
    fun cancel() = main.launch {
        val st = chat.value ?: return@launch
        val c = client ?: return@launch errors.tryEmit("未连接").let { }
        runCatching { c.cancel(st.sessionId) }
            .onFailure { errors.tryEmit(it.message ?: "终止失败") }
    }

    fun selectModel(sessionId: String?, m: ModelChoice) = main.launch {
        if (sessionId == null) return@launch
        model.value = model.value + (sessionId to m)
        runCatching { client?.selectModel(sessionId, m.provider, m.model, m.effort) }.onFailure { errors.tryEmit(it.message ?: "切换模型失败") }
    }

    fun setPermission(sessionId: String?, name: String) = main.launch {
        if (sessionId == null) return@launch
        runCatching { client?.setPermission(sessionId, name)?.let { permission.value = permission.value + (sessionId to it) } }
            .onFailure { errors.tryEmit(it.message ?: "切换权限失败") }
    }

    fun rename(sessionId: String, title: String) = main.launch { runCatching { client?.call("sessions.rename", jsonOf("sessionId" to sessionId, "title" to title)) } }

    /**
     * Fork the open session at [atSeq] (desktop `message.branch`).
     * Omitting [atSeq] uses the Host's latest completed-turn prefix.
     */
    fun forkSession(atSeq: Long? = null) = main.launch {
        val c = client ?: run { errors.tryEmit("未连接"); return@launch }
        val st = chat.value ?: run { errors.tryEmit("无会话"); return@launch }
        if (!hasOp("sessions.fork")) { errors.tryEmit("桌面端未提供分叉"); return@launch }
        runCatching {
            val childId = c.forkSession(st.sessionId, atSeq)["sessionId"].str()
                ?: error("fork 未返回 sessionId")
            refreshTitles(c)
            openSession(childId)
        }.onFailure { errors.tryEmit(it.message ?: "无法分叉会话，请重试") }
    }

    fun answer(approvalId: String, outcome: String) = main.launch {
        approvals.value = approvals.value.filter { it.approvalId != approvalId }
        runCatching { client?.answerApproval(approvalId, outcome) }.onFailure { errors.tryEmit(it.message ?: "审批失败") }
    }

    fun hasOp(op: String) = op in ops.value

    fun executeCommand(line: String) = main.launch {
        val c = client ?: run { errors.tryEmit("未连接"); return@launch }
        val st = chat.value ?: run { errors.tryEmit("无会话"); return@launch }
        if (!hasOp("commands.execute")) { errors.tryEmit("桌面端未提供指令"); return@launch }
        runCatching {
            val r = c.executeCommand(st.sessionId, line)
            r["result"]["text"].str()?.takeIf { it.isNotBlank() }?.let { errors.tryEmit(it) }
        }.onFailure { errors.tryEmit(it.message ?: "指令失败") }
    }

    fun exportSession(onBytes: (filename: String, bytes: ByteArray) -> Unit) = main.launch {
        val c = client ?: run { errors.tryEmit("未连接"); return@launch }
        val st = chat.value ?: run { errors.tryEmit("无会话"); return@launch }
        if (!hasOp("session.export")) { errors.tryEmit("桌面端未提供导出"); return@launch }
        runCatching {
            val r = c.exportSession(st.sessionId)
            val name = r["filename"].str() ?: "dsh-session.zip"
            val b64 = r["data"].str() ?: error("empty export")
            onBytes(name, android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        }.onFailure { errors.tryEmit(it.message ?: "导出失败") }
    }

    fun sendImages(text: String, images: List<PromptImage>) = main.launch {
        val c = client ?: return@launch; val st = chat.value ?: return@launch
        val mode = if (st.running) prefs.busySend.mode else "queue"
        if (text.isNotBlank()) echo(text)
        runCatching { c.prompt(st.sessionId, text, mode, images) }.onFailure { errors.tryEmit(it.message ?: "发送失败") }
    }

    fun sendWithFiles(text: String, images: List<PromptImage>, files: List<PromptFile>) = main.launch {
        val c = client ?: return@launch; val st = chat.value ?: return@launch
        val mode = if (st.running) prefs.busySend.mode else "queue"
        if (text.isNotBlank()) echo(text)
        runCatching { c.prompt(st.sessionId, text, mode, images, files) }
            .onFailure { errors.tryEmit(it.message ?: "发送失败") }
    }

    /** Upload a non-image file; returns receiptId or null on failure. */
    suspend fun uploadFile(sessionId: String, bytes: ByteArray, name: String?): UploadReceipt? {
        val c = client ?: return null
        if (!hasOp("attachments.upload")) return null
        return runCatching {
            UploadReceipt.parse(
                c.uploadAttachment(sessionId, Base64.encodeToString(bytes, Base64.NO_WRAP), name),
            )
        }.onFailure { errors.tryEmit(it.message ?: L.uploadFailed()) }.getOrNull()
    }

    /** Load attachment bytes (base64) for transcript image bubbles; cached. */
    suspend fun loadAttachmentBytes(sessionId: String, attachmentId: String): ByteArray? {
        attachmentCache[attachmentId]?.let { return it }
        val c = client ?: return null
        if (!hasOp("session.attachment")) return null
        return runCatching {
            val r = c.sessionAttachment(sessionId, attachmentId)
            val b64 = r["data"].str() ?: return null
            Base64.decode(b64, Base64.DEFAULT).also { attachmentCache[attachmentId] = it }
        }.getOrNull()
    }

    fun searchSessions(query: String, onResult: (List<SessionSummary>) -> Unit) = main.launch {
        val c = client ?: run { onResult(emptyList()); return@launch }
        if (!hasOp("sessions.search") || query.isBlank()) {
            onResult(emptyList()); return@launch
        }
        runCatching {
            val r = c.searchSessions(query)
            val items = r["items"].arr().orEmpty().mapNotNull(SessionSummary::parse)
            onResult(items)
        }.onFailure {
            errors.tryEmit(it.message ?: "搜索失败")
            onResult(emptyList())
        }
    }

    fun archiveSession(sessionId: String, stopActivity: Boolean = true) = main.launch {
        val c = client ?: return@launch
        if (!hasOp("workspace.archiveSession")) { errors.tryEmit("桌面端未提供归档"); return@launch }
        runCatching {
            c.archiveSession(sessionId, stopActivity = stopActivity)
            lastArchivedId = sessionId
            refreshWorkspaceState(c)
            notices.tryEmit(L.archivedToast())
        }.onFailure { errors.tryEmit(it.message ?: "归档失败") }
    }

    fun unarchiveSession(sessionId: String) = main.launch {
        val c = client ?: return@launch
        if (!hasOp("workspace.unarchiveSession")) return@launch
        runCatching {
            c.unarchiveSession(sessionId)
            refreshWorkspaceState(c)
        }.onFailure { errors.tryEmit(it.message ?: "取消归档失败") }
    }

    fun pinSession(sessionId: String) = main.launch {
        val c = client ?: return@launch
        if (!hasOp("workspace.pinSession")) { errors.tryEmit("桌面端未提供置顶"); return@launch }
        runCatching { c.pinSession(sessionId); refreshWorkspaceState(c) }
            .onFailure { errors.tryEmit(it.message ?: "置顶失败") }
    }

    fun unpinSession(sessionId: String) = main.launch {
        val c = client ?: return@launch
        if (!hasOp("workspace.unpinSession")) return@launch
        runCatching { c.unpinSession(sessionId); refreshWorkspaceState(c) }
            .onFailure { errors.tryEmit(it.message ?: "取消置顶失败") }
    }

    private suspend fun loadFeedback(sessionId: String) {
        val c = client ?: return
        val r = c.getFeedback(sessionId)
        val items = r["items"].arr().orEmpty().mapNotNull(MessageFeedback::parse)
        feedback.value = items.associateBy { it.messageId }
    }

    fun setMessageFeedback(
        messageId: String,
        rating: String?,
        category: String? = null,
        note: String? = null,
    ) = main.launch {
        val c = client ?: return@launch
        val st = chat.value ?: return@launch
        if (!hasOp("feedback.set")) { errors.tryEmit("桌面端未提供反馈"); return@launch }
        val prev = feedback.value[messageId]
        runCatching {
            val r = c.setFeedback(
                st.sessionId, messageId, rating, category, note, prev?.version,
            )
            val next = MessageFeedback.parse(r) ?: MessageFeedback(messageId, rating, category = category, note = note)
            feedback.value = if (rating == null) feedback.value - messageId else feedback.value + (messageId to next)
            if (rating != null) notices.tryEmit(L.thanksFeedback())
        }.onFailure { errors.tryEmit(it.message ?: "反馈保存失败") }
    }

    fun lookupFileRefs(query: String, onResult: (List<FileReference>) -> Unit) = main.launch {
        val c = client ?: run { onResult(emptyList()); return@launch }
        val st = chat.value ?: run { onResult(emptyList()); return@launch }
        if (!hasOp("references.files")) { onResult(emptyList()); return@launch }
        runCatching {
            onResult(c.referenceFiles(st.sessionId, query)["items"].arr().orEmpty().mapNotNull(FileReference::parse))
        }.onFailure { onResult(emptyList()) }
    }

    fun lookupSessionRefs(query: String, onResult: (List<SessionReference>) -> Unit) = main.launch {
        val c = client ?: run { onResult(emptyList()); return@launch }
        val st = chat.value ?: run { onResult(emptyList()); return@launch }
        if (!hasOp("references.sessions")) { onResult(emptyList()); return@launch }
        runCatching {
            onResult(c.referenceSessions(st.sessionId, query)["items"].arr().orEmpty().mapNotNull(SessionReference::parse))
        }.onFailure { onResult(emptyList()) }
    }

    fun answerUserQuestion(callId: String, answers: List<JsonObject>) = main.launch {
        val c = client ?: return@launch
        val st = chat.value ?: return@launch
        if (!hasOp("userQuestions.answer")) { errors.tryEmit("桌面端未提供提问"); return@launch }
        userQuestions.value = userQuestions.value.filter { it.callId != callId && it.questionId != callId }
        runCatching { c.answerUserQuestion(st.sessionId, callId, answers) }
            .onFailure { errors.tryEmit(it.message ?: "回答失败") }
    }

    /** Insert a mention token into the open composer (`@path` / session mention). */
    fun insertComposer(token: String) {
        if (token.isNotBlank()) composerInsert.tryEmit(token)
    }

    /**
     * Enrich a `workspace/changes` card via Host summary.
     * @return Triple(total, singleDisplayName, added) or null when unavailable.
     */
    fun fetchChangesSummary(seq: Long, onResult: (Triple<Int, String?, Int>?) -> Unit) = main.launch {
        val c = client ?: run { onResult(null); return@launch }
        val st = chat.value ?: run { onResult(null); return@launch }
        if (!hasOp("workspaceChanges.summary")) { onResult(null); return@launch }
        runCatching {
            val summary = c.workspaceChangesSummary(st.sessionId, seq)["summary"]
            if (summary == null || summary is kotlinx.serialization.json.JsonNull) {
                onResult(null); return@runCatching
            }
            val total = summary["total"].long()?.toInt() ?: summary["files"].arr()?.size ?: 0
            val files = summary["files"].arr().orEmpty()
            val name = files.singleOrNull()?.let { f ->
                f["display"].str() ?: f["path"].str()?.substringAfterLast('/')
            }
            val added = summary["added"].long()?.toInt() ?: 0
            onResult(Triple(total, name, added))
        }.onFailure { onResult(null) }
    }

    suspend fun listFiles(sessionId: String, path: String) = client?.listFiles(sessionId, path)
        ?: throw PocketError("transport", "未连接")
    suspend fun readFile(sessionId: String, path: String) = client?.readFile(sessionId, path)
        ?: throw PocketError("transport", "未连接")
    suspend fun listJobs(sessionId: String) = client?.listJobs(sessionId)
        ?: throw PocketError("transport", "未连接")
    fun killJob(sessionId: String, jobId: String) = main.launch {
        runCatching { client?.killJob(sessionId, jobId) }.onFailure { errors.tryEmit(it.message ?: "终止失败") }
    }

    /** Open (or reuse) a host PTY for [sessionId] and start following its screen. */
    fun openTerminal(sessionId: String) = main.launch {
        val c = client ?: run { errors.tryEmit("未连接"); return@launch }
        if (!hasOp("terminal.create") || !hasOp("terminal.follow")) {
            errors.tryEmit("桌面端未提供终端"); return@launch
        }
        val cols = wantCols
        val rows = wantRows
        runCatching {
            terminalSubId?.let { old -> runCatching { c.unfollow(old) } }
            terminalSubId = null
            terminal.resize(cols, rows)
            terminal.clear()
            terminalRevision.value = terminal.revision
            val existing = c.listTerminals(sessionId)["terminals"].arr().orEmpty()
                .firstOrNull { it["state"].str() == "running" }
            val info = existing ?: c.createTerminal(sessionId, cols = cols, rows = rows)["terminal"]
            val id = info["id"].str() ?: error("no terminal id")
            val follow = c.followTerminal(sessionId, id)
            val attachmentId = follow["attachmentId"].str() ?: error("no attachmentId")
            terminalSubId = follow["subId"].str()
            terminalMeta.value = TerminalMeta(sessionId, id, attachmentId, info["title"].str(), info["state"].str())
            // Align PTY to the latest panel grid (view may have measured while we were attaching).
            val cCols = wantCols
            val cRows = wantRows
            if (cCols != cols || cRows != rows || existing != null) {
                terminal.resize(cCols, cRows)
                terminalRevision.value = terminal.revision
                runCatching { c.resizeTerminal(sessionId, id, attachmentId, cCols, cRows) }
            }
        }.onFailure { errors.tryEmit(it.message ?: "打开终端失败") }
    }

    fun writeTerminal(data: String) = main.launch {
        val c = client ?: return@launch
        val meta = terminalMeta.value ?: return@launch
        runCatching { c.writeTerminal(meta.sessionId, meta.id, meta.attachmentId, data) }
            .onFailure { errors.tryEmit(it.message ?: "终端输入失败") }
    }

    fun resizeTerminal(cols: Int, rows: Int) = main.launch {
        wantCols = cols.coerceAtLeast(2)
        wantRows = rows.coerceAtLeast(1)
        if (wantCols != terminal.cols || wantRows != terminal.rows) {
            terminal.resize(wantCols, wantRows)
            terminalRevision.value = terminal.revision
        }
        val c = client ?: return@launch
        val meta = terminalMeta.value ?: return@launch
        runCatching { c.resizeTerminal(meta.sessionId, meta.id, meta.attachmentId, wantCols, wantRows) }
            .onFailure { errors.tryEmit(it.message ?: "终端调整失败") }
    }

    fun closeTerminalFollow() = main.launch {
        val c = client ?: return@launch
        terminalSubId?.let { runCatching { c.unfollow(it) } }
        terminalSubId = null
    }

    /** Pair using an already-parsed [PairingLink] (host:port WebSocket). */
    suspend fun pair(name: String, link: PairingLink): Result<Profile> =
        pairHost(name, link.host, link.port, link.code)

    /** Pair over Tailscale / LAN WebSocket (host:port). */
    suspend fun pairHost(name: String, host: String, port: Int, code: String): Result<Profile> = withContext(Dispatchers.IO) {
        if (host.isBlank() || port !in 1..65535) {
            return@withContext Result.failure(Exception("地址无效：需要 host:port（Tailscale IP 或局域网地址）"))
        }
        val c = PocketClient()
        try {
            val draft = Profile("", name.ifBlank { "电脑" }, host = host, port = port)
            val factories = factoriesFor(draft)
            if (factories.isEmpty()) return@withContext Result.failure(Exception("无法建立 WebSocket 连接"))
            val hello = c.connect(factories, Auth.Pair(code, Build.MODEL ?: "Android"))
            val token = hello.issuedToken ?: error("服务端未返回令牌")
            val p = Profile(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { host },
                host = host,
                port = port,
                deviceId = hello.deviceId,
            )
            tokens.put(p.id, token)
            withContext(main.coroutineContext) {
                profiles.value = profiles.value + p
                prefs.profiles = profiles.value
            }
            Result.success(p)
        } catch (e: PocketError) {
            Result.failure(Exception(when {
                e.code.contains("expired") -> "配对码已过期"
                e.code.startsWith("pair/") -> "配对码错误或未开启配对"
                e.code == "transport" -> "无法连上桌面端，请确认电脑已打开配对且手机可达该 host:port（同一 Tailscale 或局域网）"
                else -> e.message
            }))
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "配对失败"))
        } finally { c.close() }
    }

    fun removeProfile(id: String) = main.launch {
        if (active.value == id) { connJob?.cancel(); client?.close(); client = null; active.value = null; prefs.activeProfile = null; sessions.value = emptyList(); chat.value = null }
        tokens.remove(id); profiles.value = profiles.value.filter { it.id != id }; prefs.profiles = profiles.value
    }

    /**
     * Rename a local desktop [Profile] label on the phone.
     * Does not call desktop `PATCH /pocket/devices` (that renames the phone device on the Mac).
     * Name is trimmed and truncated to 64 chars like plugin `devices.js`.
     */
    fun renameProfile(id: String, name: String) {
        val n = clipProfileName(name)
        if (profiles.value.none { it.id == id }) error("profile not found: $id")
        profiles.value = profiles.value.map { if (it.id == id) it.copy(name = n) else it }
        prefs.profiles = profiles.value
    }

    /** Soft probe: TCP check for inactive host:port profiles. */
    fun probe() = main.launch {
        profiles.value.filter { it.id != active.value }.forEach { p ->
            if (p.needsRepair || tokens.get(p.id) == null) {
                setDot(p.id, Dot.Unauthorized)
                return@forEach
            }
            launch(Dispatchers.IO) {
                val ok = runCatching {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(p.host, p.port), 2500) }
                }.isSuccess
                if (p.id != active.value) {
                    setDot(p.id, if (ok) Dot.Online else Dot.Offline)
                }
            }
        }
    }
}

data class TerminalMeta(val sessionId: String, val id: String, val attachmentId: String, val title: String? = null, val state: String? = null)
