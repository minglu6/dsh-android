package dev.dsh.pocket.app

import android.content.Context
import android.os.Build
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import java.util.UUID

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
)

data class ModelChoice(val provider: String, val model: String, val name: String, val effort: String?)

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
    val catalog = MutableStateFlow<ModelCatalog?>(null)
    val presets = MutableStateFlow<List<String>>(emptyList())
    val ops = MutableStateFlow<Set<String>>(emptySet())
    val chat = MutableStateFlow<ChatState?>(null)
    val permission = MutableStateFlow<Map<String, String>>(emptyMap())
    val model = MutableStateFlow<Map<String, ModelChoice>>(emptyMap())
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Live host PTY screen text for the open terminal sheet (ANSI stripped). */
    val terminalScreen = MutableStateFlow("")
    val terminalMeta = MutableStateFlow<TerminalMeta?>(null)

    private var client: PocketClient? = null
    private var connJob: Job? = null
    private var reducer = TranscriptReducer()
    private var subId: String? = null
    private var terminalSubId: String? = null
    private val stray = HashMap<String, MutableList<JsonObject>>()
    /** Per-session follow attempts across reconnects; capped to stop spin loops. */
    private val followAttempts = HashMap<String, Int>()
    private const val MAX_FOLLOW_ATTEMPTS = 3
    private const val FOLLOW_MAX_MESSAGES = 50
    private const val HISTORY_PAGE_SIZE = 100

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
        setRoute(profileId, null)
        connJob = main.launch { runConnection(profileId) }
    }

    private fun factoriesFor(p: Profile): List<TransportFactory> {
        if (p.needsRepair) return emptyList()
        return transportPlan(p.host, p.port)
    }

    private suspend fun runConnection(profileId: String) {
        var backoff = 1000L
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
                val hello = c.connect(factories, Auth.Token(token))
                client = c; ops.value = hello.ops
                setRoute(p.id, c.route ?: hello.route)
                setDot(p.id, Dot.Online); backoff = 1000L
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
            delay(backoff); backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }

    private suspend fun refreshAll(c: PocketClient) {
        runCatching { sessions.value = c.listSessions().sortedByDescending { it.updatedAt } }
        runCatching { approvals.value = c.call("approval.pending")["approvals"].arr().orEmpty().mapNotNull { (it as? JsonObject)?.let(Approval::parse) } }
        runCatching { catalog.value = c.modelCatalog() }
        if ("presets.list" in ops.value) runCatching { presets.value = c.presets() }
        chat.value?.let { refollow(c, it.sessionId, reducer.cursor.takeIf { s -> s >= 0 }) }
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
                terminalScreen.value = stripAnsi(frame["screen"].str().orEmpty())
                frame["info"]?.let { info ->
                    terminalMeta.value = terminalMeta.value?.copy(
                        title = info["title"].str() ?: terminalMeta.value?.title,
                        state = info["state"].str() ?: terminalMeta.value?.state,
                    )
                }
            }
            "output" -> terminalScreen.value = (terminalScreen.value + stripAnsi(frame["data"].str().orEmpty())).takeLast(80_000)
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
        chat.value = ChatState(sessionId, title = sessions.value.firstOrNull { it.sessionId == sessionId }?.title)
        val c = client ?: return@launch
        runCatching { refollow(c, sessionId, null) }
            .onFailure {
                errors.tryEmit(it.message ?: "follow failed")
                chat.value = chat.value?.copy(loaded = true)
            }
        if ("permissions.get" in ops.value) runCatching { c.permission(sessionId)?.let { permission.value = permission.value + (sessionId to it) } }
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
        runCatching {
            terminalSubId?.let { old -> runCatching { c.unfollow(old) } }
            terminalSubId = null
            terminalScreen.value = ""
            val existing = c.listTerminals(sessionId)["terminals"].arr().orEmpty()
                .firstOrNull { it["state"].str() == "running" }
            val info = existing ?: c.createTerminal(sessionId)["terminal"]
            val id = info["id"].str() ?: error("no terminal id")
            val follow = c.followTerminal(sessionId, id)
            val attachmentId = follow["attachmentId"].str() ?: error("no attachmentId")
            terminalSubId = follow["subId"].str()
            terminalMeta.value = TerminalMeta(sessionId, id, attachmentId, info["title"].str(), info["state"].str())
        }.onFailure { errors.tryEmit(it.message ?: "打开终端失败") }
    }

    fun writeTerminal(data: String) = main.launch {
        val c = client ?: return@launch
        val meta = terminalMeta.value ?: return@launch
        runCatching { c.writeTerminal(meta.sessionId, meta.id, meta.attachmentId, data) }
            .onFailure { errors.tryEmit(it.message ?: "终端输入失败") }
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

/** Strip CSI / OSC sequences so a host PTY is readable without a full emulator. */
internal fun stripAnsi(s: String): String =
    s.replace(Regex("\\u001B\\][^\\u0007]*\\u0007"), "")
        .replace(Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
        .replace(Regex("\\u001B."), "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
