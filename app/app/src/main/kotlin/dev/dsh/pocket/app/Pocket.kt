package dev.dsh.pocket.app

import android.content.Context
import android.os.Build
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

enum class Dot { Online, Connecting, Offline, Unauthorized }

data class ChatState(
    val sessionId: String,
    val items: List<Item> = emptyList(),
    val title: String? = null,
    val running: Boolean = false,
    val usage: Usage = Usage(0, 0, 0, 0, 0),
    val loaded: Boolean = false,
    val runStartedAt: Long? = null,
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

    private var client: PocketClient? = null
    private var connJob: Job? = null
    private var reducer = TranscriptReducer()
    private var subId: String? = null
    private val stray = HashMap<String, MutableList<JsonObject>>()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = AppPrefs(ctx); tokens = SecureTokenStore(ctx)
        profiles.value = prefs.profiles
        active.value = prefs.activeProfile?.takeIf { id -> profiles.value.any { it.id == id } }
        active.value?.let { connect(it) }
    }

    val activeProfile get() = profiles.value.firstOrNull { it.id == active.value }

    private fun setDot(id: String, d: Dot) { status.value = status.value + (id to d) }

    fun connect(profileId: String) {
        connJob?.cancel(); client?.close(); client = null
        active.value = profileId; prefs.activeProfile = profileId
        sessions.value = emptyList(); chat.value = null; approvals.value = emptyList()
        connJob = main.launch { runConnection(profileId) }
    }

    private suspend fun runConnection(profileId: String) {
        var backoff = 1000L
        while (currentCoroutineContext().isActive) {
            val p = profiles.value.firstOrNull { it.id == profileId } ?: return
            val token = tokens.get(p.id) ?: run { setDot(p.id, Dot.Unauthorized); return }
            setDot(p.id, Dot.Connecting)
            val c = PocketClient()
            try {
                val hello = c.connect(p.url, Auth.Token(token))
                client = c; ops.value = hello.ops; setDot(p.id, Dot.Online); backoff = 1000L
                coroutineScope {
                    launch { c.events.collect { onPush(it) } }
                    launch { refreshAll(c) }
                    c.state.first { it == PocketClient.State.Closed }
                    cancel()
                }
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) { c.close(); throw e }
            } catch (e: Exception) { /* fall through to retry */ }
            client = null; c.close()
            val reason = c.closeReason.orEmpty() + ""
            if ("4401" in reason || "4403" in reason || "auth/" in reason) { setDot(p.id, Dot.Unauthorized); return }
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
            "follow.end", "follow.error" -> if (m["subId"].str() == subId) subId = null
            "session" -> when (m["kind"].str()) {
                "added" -> SessionSummary.parse(m["summary"])?.let { s -> sessions.value = listOf(s) + sessions.value.filter { it.sessionId != s.sessionId } }
                "removed" -> sessions.value = sessions.value.filter { it.sessionId != m["sessionId"].str() }
                "status" -> { val id = m["sessionId"].str(); val r = m["running"].bool() ?: false
                    sessions.value = sessions.value.map { if (it.sessionId == id) it.copy(running = r) else it }
                    client?.let { c -> main.launch { refreshTitles(c); if (!r) { delay(4000); refreshTitles(c) } } } }
                "activity" -> { val id = m["sessionId"].str(); val t = m["updatedAt"].long() ?: 0
                    sessions.value = sessions.value.map { if (it.sessionId == id) it.copy(updatedAt = t) else it }.sortedByDescending { it.updatedAt } }
                "error" -> m["message"].str()?.let { errors.tryEmit(it) }
            }
            "approval" -> { val a = Approval.parse(m); approvals.value = approvals.value.filter { it.approvalId != a.approvalId } + a }
            "approval.closed" -> approvals.value = approvals.value.filter { it.approvalId != m["approvalId"].str() }
        }
    }

    private fun applyFrame(f: kotlinx.serialization.json.JsonElement?) {
        val st = chat.value ?: return
        val wasRunning = reducer.running
        reducer.apply(f)
        chat.value = st.copy(items = reducer.items(), title = reducer.title ?: st.title, running = reducer.running, usage = reducer.usage, loaded = true,
            runStartedAt = if (reducer.running) (if (wasRunning) st.runStartedAt else System.currentTimeMillis()) else null)
    }

    private suspend fun refollow(c: PocketClient, sessionId: String, afterSeq: Long?) {
        subId?.let { old -> runCatching { c.unfollow(old) } }
        subId = null
        val sid = c.follow(sessionId, afterSeq)
        subId = sid
        stray.remove(sid)?.forEach { applyFrame(it["frame"]) }
        stray.clear()
    }

    fun openSession(sessionId: String) = main.launch {
        reducer = TranscriptReducer()
        chat.value = ChatState(sessionId, title = sessions.value.firstOrNull { it.sessionId == sessionId }?.title)
        val c = client ?: return@launch
        runCatching { refollow(c, sessionId, null) }.onFailure { errors.tryEmit(it.message ?: "follow failed") }
        if ("permissions.get" in ops.value) runCatching { c.permission(sessionId)?.let { permission.value = permission.value + (sessionId to it) } }
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
        chat.value?.let { chat.value = it.copy(items = reducer.items()) }
    }

    /** dsh titles a session asynchronously after the first turn; the list is the source of truth. */
    private suspend fun refreshTitles(c: PocketClient) = runCatching {
        sessions.value = c.listSessions().sortedByDescending { it.updatedAt }
        chat.value?.let { st -> sessions.value.firstOrNull { it.sessionId == st.sessionId }?.title?.let { t -> chat.value = st.copy(title = t) } }
    }

    fun cancel() = main.launch { val st = chat.value ?: return@launch; runCatching { client?.cancel(st.sessionId) } }

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

    /** Pair with a desktop: exchanges the 6-digit code for a device token and saves a profile. */
    suspend fun pair(name: String, host: String, port: Int, code: String): Result<Profile> = withContext(Dispatchers.IO) {
        val c = PocketClient()
        try {
            val hello = c.connect(Profile("", name, host, port).url, Auth.Pair(code, Build.MODEL ?: "Android"))
            val token = hello.issuedToken ?: error("服务端未返回令牌")
            val p = Profile(UUID.randomUUID().toString(), name.ifBlank { host }, host, port, hello.deviceId)
            tokens.put(p.id, token)
            withContext(main.coroutineContext) { profiles.value = profiles.value + p; prefs.profiles = profiles.value }
            Result.success(p)
        } catch (e: PocketError) {
            Result.failure(Exception(when {
                e.code.contains("expired") -> "配对码已过期"
                e.code.startsWith("pair/") -> "配对码错误或未开启配对"
                e.code == "transport" -> "无法连接 $host:$port，请确认 Tailscale 已连接且电脑端 dsh 正在运行"
                else -> e.message
            }))
        } catch (e: Exception) { Result.failure(Exception("无法连接 $host:$port")) } finally { c.close() }
    }

    fun removeProfile(id: String) = main.launch {
        if (active.value == id) { connJob?.cancel(); client?.close(); client = null; active.value = null; prefs.activeProfile = null; sessions.value = emptyList(); chat.value = null }
        tokens.remove(id); profiles.value = profiles.value.filter { it.id != id }; prefs.profiles = profiles.value
    }

    /** TCP reachability for non-active profiles (status dots on the connections screen). */
    fun probe() = main.launch {
        profiles.value.filter { it.id != active.value }.forEach { p ->
            launch(Dispatchers.IO) {
                val ok = runCatching { Socket().use { it.connect(InetSocketAddress(p.host, p.port), 2500) } }.isSuccess
                main.launch { if (p.id != active.value) setDot(p.id, if (tokens.get(p.id) == null) Dot.Unauthorized else if (ok) Dot.Online else Dot.Offline) }
            }
        }
    }
}
