package dev.dsh.pocket.app

import android.content.Context
import android.os.Build
import dev.dsh.pocket.app.iroh.IrohNative
import dev.dsh.pocket.app.iroh.IrohTransport
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import java.io.File
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
    /** Active frame route per profile (WS / iroh direct / iroh relay). */
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
    private var irohDir: File? = null

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = AppPrefs(ctx); tokens = SecureTokenStore(ctx)
        irohDir = IrohNative.defaultDataDir(ctx.filesDir)
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
        return when (p.remote) {
            RemoteTransport.Iroh -> {
                val iroh = irohDir?.let { dir -> IrohTransport.factory(p.nodeId, dir) }
                // Loopback WS only for local/dev iroh profiles — never remote Tailscale WS.
                val loopback = p.host.takeIf { isLoopbackHost(it) && p.port in 1..65535 }?.let {
                    "ws://${if (':' in it) "[$it]" else it}:${p.port}"
                }
                transportPlan(RemoteTransport.Iroh, nodeId = p.nodeId, irohFactory = iroh, loopbackWsUrl = loopback)
            }
            RemoteTransport.Tailscale -> transportPlan(
                RemoteTransport.Tailscale,
                host = p.host,
                port = p.port,
            )
        }
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
                    errors.tryEmit(
                        when (p.remote) {
                            RemoteTransport.Iroh -> "iroh 不可用：请确认 APK 含原生库，或重新配对"
                            RemoteTransport.Tailscale -> "Tailscale 地址无效，请重新配对"
                        },
                    )
                    return
                }
                // One mode only — never dial Tailscale then iroh (or the reverse).
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
                    // Composer Stop uses chat.running (from the follow reducer). Cancel often clears
                    // session status before / without a folded turn/end; mirror idle into the open chat.
                    if (!r) chat.value?.takeIf { it.sessionId == id && it.running }?.let { st ->
                        reducer.markIdle()
                        chat.value = st.copy(running = false, runStartedAt = null, items = reducer.items())
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

    /** Pair using an already-parsed [PairingLink] (mode implied by the link). */
    suspend fun pair(name: String, link: PairingLink): Result<Profile> = when (link) {
        is PairingLink.Iroh -> pairIroh(name, link.nodeId, link.code)
        is PairingLink.Tailscale -> pairTailscale(name, link.host, link.port, link.code)
    }

    /** Pair over iroh only (Tokyo relay may be used inside iroh pathing). */
    suspend fun pairIroh(name: String, nodeId: String, code: String): Result<Profile> = withContext(Dispatchers.IO) {
        if (nodeId.isBlank()) return@withContext Result.failure(Exception("配对串不完整：需要桌面端显示的整行或扫码"))
        if (!IrohNative.available) return@withContext Result.failure(Exception("本机缺少 iroh 原生库，无法配对"))
        val c = PocketClient()
        try {
            val draft = Profile("", name.ifBlank { "电脑" }, transport = "iroh", nodeId = nodeId)
            val factories = factoriesFor(draft)
            if (factories.isEmpty()) return@withContext Result.failure(Exception("无法建立 iroh 连接"))
            val hello = c.connect(factories, Auth.Pair(code, Build.MODEL ?: "Android"))
            val token = hello.issuedToken ?: error("服务端未返回令牌")
            val p = Profile(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { "电脑" },
                transport = "iroh",
                nodeId = nodeId,
                deviceId = hello.deviceId,
            )
            tokens.put(p.id, token)
            withContext(main.coroutineContext) {
                prefs.preferredTransport = RemoteTransport.Iroh
                profiles.value = profiles.value + p
                prefs.profiles = profiles.value
            }
            Result.success(p)
        } catch (e: PocketError) {
            Result.failure(Exception(when {
                e.code.contains("expired") -> "配对码已过期"
                e.code.startsWith("pair/") -> "配对码错误或未开启配对"
                e.code == "transport" -> "无法经 iroh 连上桌面端，请确认电脑已打开配对且网络可达"
                else -> e.message
            }))
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "配对失败"))
        } finally { c.close() }
    }

    /** Pair over Tailscale / remote WS only — never falls back to iroh. */
    suspend fun pairTailscale(name: String, host: String, port: Int, code: String): Result<Profile> = withContext(Dispatchers.IO) {
        if (host.isBlank() || port !in 1..65535) {
            return@withContext Result.failure(Exception("地址无效：需要 host:port（如 Tailscale IP）"))
        }
        val c = PocketClient()
        try {
            val draft = Profile("", name.ifBlank { "电脑" }, transport = "tailscale", host = host, port = port)
            val factories = factoriesFor(draft)
            if (factories.isEmpty()) return@withContext Result.failure(Exception("无法建立 WebSocket 连接"))
            val hello = c.connect(factories, Auth.Pair(code, Build.MODEL ?: "Android"))
            val token = hello.issuedToken ?: error("服务端未返回令牌")
            val p = Profile(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { host },
                transport = "tailscale",
                host = host,
                port = port,
                deviceId = hello.deviceId,
            )
            tokens.put(p.id, token)
            withContext(main.coroutineContext) {
                prefs.preferredTransport = RemoteTransport.Tailscale
                profiles.value = profiles.value + p
                prefs.profiles = profiles.value
            }
            Result.success(p)
        } catch (e: PocketError) {
            Result.failure(Exception(when {
                e.code.contains("expired") -> "配对码已过期"
                e.code.startsWith("pair/") -> "配对码错误或未开启配对"
                e.code == "transport" -> "无法经 Tailscale/WS 连上桌面端，请确认电脑已打开配对且手机在同一 tailnet"
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

    /** Soft probe: Tailscale profiles get a TCP check; iroh stays offline until selected. */
    fun probe() = main.launch {
        profiles.value.filter { it.id != active.value }.forEach { p ->
            if (p.needsRepair || tokens.get(p.id) == null) {
                setDot(p.id, Dot.Unauthorized)
                return@forEach
            }
            when (p.remote) {
                RemoteTransport.Iroh -> if (status.value[p.id] == null) setDot(p.id, Dot.Offline)
                RemoteTransport.Tailscale -> launch(Dispatchers.IO) {
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
}

data class TerminalMeta(val sessionId: String, val id: String, val attachmentId: String, val title: String? = null, val state: String? = null)

/** Strip CSI / OSC sequences so a host PTY is readable without a full emulator. */
internal fun stripAnsi(s: String): String =
    s.replace(Regex("\\u001B\\][^\\u0007]*\\u0007"), "")
        .replace(Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
        .replace(Regex("\\u001B."), "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
