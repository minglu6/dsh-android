package dev.dsh.pocket.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Credentials for one connection attempt: either a device token (hello) or a pairing code (pair). */
sealed interface Auth {
    data class Token(val token: String) : Auth
    data class Pair(val code: String, val deviceName: String) : Auth
}

data class HelloResult(val raw: JsonObject) {
    val ops: Set<String> get() = raw["ops"].arr().orEmpty().mapNotNull { it.str() }.toSet()
    val deviceId: String? get() = raw["deviceId"].str() ?: raw["device"]["id"].str()
    /** Present only after a successful pairing. */
    val issuedToken: String? get() = raw["token"].str()
}

/**
 * Client for the dsh-plugin-pocket protocol v1 over one WebSocket.
 * Requests are `{id, op, args}`; replies `{id, ok, result|error}`; pushes `{ev, ...}`.
 */
class PocketClient(
    private val http: OkHttpClient = defaultHttp(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    enum class State { Idle, Connecting, Open, Closed }

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private var ws: WebSocket? = null
    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.SUSPEND)
    /** Server pushes (`ev` frames). */
    val events: SharedFlow<JsonObject> = _events
    @Volatile var closeReason: String? = null; private set

    suspend fun connect(url: String, auth: Auth, timeoutMs: Long = 10_000): HelloResult = withTimeout(timeoutMs) {
        close()
        _state.value = State.Connecting
        closeReason = null
        val opened = CompletableDeferred<Unit>()
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) { onFrame(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail("closed $code $reason", opened) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail(t.message ?: t.toString(), opened) }
        })
        opened.await()
        val result = when (auth) {
            is Auth.Token -> call("hello", jsonOf("proto" to 1, "token" to auth.token, "app" to "dsh-android"))
            is Auth.Pair -> call("pair", jsonOf("proto" to 1, "code" to auth.code, "name" to auth.deviceName))
        }
        _state.value = State.Open
        HelloResult(result.obj() ?: JsonObject(emptyMap()))
    }

    private fun fail(reason: String, opened: CompletableDeferred<Unit>) {
        closeReason = reason
        _state.value = State.Closed
        opened.completeExceptionally(PocketError("transport", reason))
        val err = PocketError("transport", reason)
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
    }

    private fun onFrame(text: String) {
        val m = runCatching { PocketJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = m["id"].long()
        if (id != null && pending.containsKey(id)) { pending.remove(id)?.complete(m); return }
        if (m["ev"].str() == "ping") { ws?.send("""{"op":"pong"}"""); return }
        if (m["ev"] != null) scope.launch { _events.emit(m) }
    }

    /** Send one op; returns `result` or throws [PocketError]. */
    suspend fun call(op: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 60_000): JsonElement {
        val socket = ws ?: throw PocketError("transport", "not connected")
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        val frame = buildJsonObject { put("id", id); put("op", op); put("args", args) }
        if (!socket.send(frame.toString())) { pending.remove(id); throw PocketError("transport", "send failed") }
        val reply = try { withTimeout(timeoutMs) { d.await() } } finally { pending.remove(id) }
        if (reply["ok"].bool() == true) return reply["result"] ?: JsonNull
        throw PocketError(reply["error"]["code"].str() ?: "error", reply["error"]["message"].str() ?: "")
    }

    // ---- typed helpers
    suspend fun listSessions(): List<SessionSummary> = call("sessions.list")["items"].arr().orEmpty().mapNotNull(SessionSummary::parse)
    suspend fun createSession(cwd: String? = null, agentPreset: String? = null): String =
        call("sessions.create", jsonOf("cwd" to cwd, "agentPreset" to agentPreset))["sessionId"].str() ?: throw PocketError("bad-reply", "no sessionId")
    suspend fun prompt(sessionId: String, text: String, mode: String) =
        call("prompt", jsonOf("sessionId" to sessionId, "text" to text, "mode" to mode))
    suspend fun cancel(sessionId: String) = call("cancel", jsonOf("sessionId" to sessionId))
    suspend fun modelCatalog(): ModelCatalog = ModelCatalog.parse(call("models.catalog"))
    suspend fun selectModel(sessionId: String, provider: String, model: String, effort: String?) =
        call("models.select", jsonOf("sessionId" to sessionId, "provider" to provider, "model" to model, "reasoningEffort" to effort))
    suspend fun follow(sessionId: String, afterSeq: Long? = null): String =
        call("follow", jsonOf("sessionId" to sessionId, "afterSeq" to afterSeq))["subId"].str() ?: ""
    suspend fun unfollow(subId: String) = call("unfollow", jsonOf("subId" to subId))
    suspend fun answerApproval(approvalId: String, outcome: String) =
        call("approval.answer", jsonOf("approvalId" to approvalId, "outcome" to outcome))
    suspend fun permissionCatalog(): List<String> = call("permissions.catalog")["options"].arr().orEmpty().mapNotNull { it["value"].str() }
    suspend fun permission(sessionId: String): String? = call("permissions.get", jsonOf("sessionId" to sessionId))["current"].str()
    suspend fun setPermission(sessionId: String, name: String): String? = call("permissions.set", jsonOf("sessionId" to sessionId, "name" to name))["current"].str()
    suspend fun presets(): List<String> = call("presets.list")["presets"].arr().orEmpty().mapNotNull { it["id"].str() }

    fun close() {
        ws?.close(1000, "bye"); ws = null
        if (_state.value != State.Idle) _state.value = State.Closed
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
