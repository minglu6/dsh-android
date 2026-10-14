package dev.dsh.pocket.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Credentials for one connection attempt: either a device token (hello) or a pairing code (pair). */
sealed interface Auth {
    data class Token(val token: String) : Auth
    data class Pair(val code: String, val deviceName: String) : Auth
}

data class HelloResult(val raw: JsonObject, val route: Route? = null) {
    val ops: Set<String> get() = raw["ops"].arr().orEmpty().mapNotNull { it.str() }.toSet()
    val deviceId: String? get() = raw["deviceId"].str() ?: raw["device"]["id"].str()
    /** Present only after a successful pairing. */
    val issuedToken: String? get() = raw["token"].str()
    val proto: Int? get() = raw["proto"].int()
    val pluginVersion: String? get() = raw["version"].str()
    val minimumAppVersion: String? get() = raw["minimumAppVersion"].str()

    /** Versions exchanged in hello/pair for [VersionCompatibility]. */
    fun handshakeVersions(): HandshakeVersions? {
        val p = proto ?: return null
        // Absent plugin version ⇒ older than the floor so the UI asks for an update.
        val plugin = pluginVersion ?: "0.0.0"
        val minApp = minimumAppVersion ?: VersionCompatibility.MINIMUM_APP_VERSION
        return HandshakeVersions(p, plugin, minApp)
    }
}

/**
 * Client for the dsh-plugin-pocket protocol v1 over any [FrameTransport]
 * (WebSocket text frames). Auth tokens are identical on LAN and Tailscale.
 */
class PocketClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** App semver sent in hello/pair for [VersionCompatibility]. */
    private val appVersion: String = VersionCompatibility.MINIMUM_APP_VERSION,
) {
    enum class State { Idle, Connecting, Open, Closed }

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private var transport: FrameTransport? = null
    private var pump: Job? = null
    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.SUSPEND)
    /** Server pushes (`ev` frames). */
    val events: SharedFlow<JsonObject> = _events
    @Volatile var closeReason: String? = null; private set
    /** Live route from the active transport (Tailscale / LAN / local). */
    val route: Route? get() = transport?.route

    /** Negotiated WS extensions from the active transport (OkHttp ↔ permessage-deflate). */
    @Volatile var negotiatedExtensions: String? = null; private set

    /** Connect over loopback WebSocket (dev/tests only). */
    suspend fun connect(url: String, auth: Auth, timeoutMs: Long = 120_000): HelloResult =
        connect(listOf(WsTransport.factory(url)), auth, timeoutMs)

    /** Connect using an ordered list of transports (host:port WebSocket). */
    suspend fun connect(factories: List<TransportFactory>, auth: Auth, timeoutMs: Long = 120_000): HelloResult =
        withTimeout(timeoutMs) {
            close()
            _state.value = State.Connecting
            closeReason = null
            val t = dialInOrder(factories)
            attach(t)
            val result = when (auth) {
                is Auth.Token -> call(
                    "hello",
                    jsonOf(
                        "proto" to VersionCompatibility.PROTO,
                        "token" to auth.token,
                        "app" to "dsh-android",
                        "appVersion" to appVersion,
                    ),
                )
                is Auth.Pair -> call(
                    "pair",
                    jsonOf(
                        "proto" to VersionCompatibility.PROTO,
                        "code" to auth.code,
                        "name" to auth.deviceName,
                        "app" to "dsh-android",
                        "appVersion" to appVersion,
                    ),
                )
            }
            _state.value = State.Open
            HelloResult(result.obj() ?: JsonObject(emptyMap()), t.route)
        }

    suspend fun connect(factory: TransportFactory, auth: Auth, timeoutMs: Long = 120_000): HelloResult =
        connect(listOf(factory), auth, timeoutMs)

    private fun attach(t: FrameTransport) {
        transport = t
        negotiatedExtensions = (t as? WsTransport)?.negotiatedExtensions
        pump = scope.launch {
            try {
                t.incoming.collect { onFrame(it) }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            } finally {
                if (_state.value == State.Open || _state.value == State.Connecting) {
                    fail(t.closeReason ?: "closed")
                }
            }
        }
    }

    private fun fail(reason: String) {
        closeReason = reason
        _state.value = State.Closed
        val err = PocketError("transport", reason)
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
    }

    private fun onFrame(text: String) {
        val m = runCatching { PocketJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = m["id"].long()
        if (id != null && pending.containsKey(id)) { pending.remove(id)?.complete(m); return }
        if (m["ev"].str() == "ping") {
            scope.launch { runCatching { transport?.send("""{"op":"pong"}""") } }
            return
        }
        if (m["ev"] != null) scope.launch { _events.emit(m) }
    }

    /** Send one op; returns `result` or throws [PocketError], preserving caller cancellation. */
    suspend fun call(op: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 60_000): JsonElement {
        val t = transport ?: throw PocketError("transport", "not connected")
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        val frame = buildJsonObject { put("id", id); put("op", op); put("args", args) }
        val reply = try {
            try {
                t.send(frame.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw PocketError("transport", e.message ?: "send failed")
            }
            withTimeoutOrNull(timeoutMs) { d.await() }
                ?: throw PocketError("timeout", "RPC $op timed out after ${timeoutMs}ms")
        } finally {
            pending.remove(id)
        }
        if (reply["ok"].bool() == true) return reply["result"] ?: JsonNull
        throw PocketError(reply["error"]["code"].str() ?: "error", reply["error"]["message"].str() ?: "", reply["error"]["details"])
    }

    // ---- typed helpers
    suspend fun listSessions(): List<SessionSummary> = call("sessions.list")["items"].arr().orEmpty().mapNotNull(SessionSummary::parse)
    suspend fun createSession(cwd: String? = null, agentPreset: String? = null, workspaceId: String? = null): String =
        call("sessions.create", jsonOf("cwd" to cwd, "agentPreset" to agentPreset, "workspaceId" to workspaceId))["sessionId"].str() ?: throw PocketError("bad-reply", "no sessionId")
    suspend fun prompt(
        sessionId: String,
        text: String,
        mode: String,
        images: List<PromptImage> = emptyList(),
        files: List<PromptFile> = emptyList(),
        requestId: String? = null,
    ) =
        call("prompt", buildJsonObject {
            put("sessionId", sessionId); put("text", text); put("mode", mode)
            requestId?.let { put("requestId", it) }
            if (images.isNotEmpty()) putJsonArray("images") {
                images.forEach { img ->
                    add(buildJsonObject {
                        put("mediaType", img.mediaType); put("data", img.data)
                        img.name?.let { put("name", it) }
                    })
                }
            }
            if (files.isNotEmpty()) putJsonArray("files") {
                files.forEach { f -> add(buildJsonObject { put("receiptId", f.receiptId) }) }
            }
        })
    suspend fun cancel(sessionId: String) = call("cancel", jsonOf("sessionId" to sessionId))
    suspend fun executeCommand(sessionId: String, line: String): JsonElement =
        call("commands.execute", jsonOf("sessionId" to sessionId, "line" to line))
    suspend fun exportSession(sessionId: String, includeDescendants: Boolean = true): JsonElement =
        call("session.export", jsonOf("sessionId" to sessionId, "includeDescendants" to includeDescendants), timeoutMs = 180_000)
    suspend fun listFiles(sessionId: String, path: String = "."): JsonElement =
        call("files.list", jsonOf("sessionId" to sessionId, "path" to path))
    suspend fun readFile(sessionId: String, path: String, offset: Int? = null, limit: Int? = null): JsonElement {
        val range = if (offset != null || limit != null) {
            buildJsonObject {
                offset?.let { put("offset", it) }
                limit?.let { put("limit", it) }
            }
        } else null
        return call("files.read", jsonOf("sessionId" to sessionId, "path" to path, "range" to range))
    }
    suspend fun readFileBytes(sessionId: String, path: String): JsonElement =
        call("files.readBytes", jsonOf("sessionId" to sessionId, "path" to path))
    suspend fun listJobs(sessionId: String): JsonElement =
        call("jobs.list", jsonOf("sessionId" to sessionId))
    suspend fun killJob(sessionId: String, jobId: String): JsonElement =
        call("jobs.kill", jsonOf("sessionId" to sessionId, "jobId" to jobId))

    /** Observe without consuming the model's output cursor; cancellation detaches on the Host. */
    fun followJob(sessionId: String, jobId: String, from: Long? = null): Flow<JsonObject> = flow {
        coroutineScope {
            val inbox = Channel<JsonObject>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                events.collect { if (it["ev"].str()?.startsWith("job") == true) inbox.send(it) }
            }
            val disconnect = launch {
                state.first { it == State.Closed }
                inbox.close(PocketError("transport", closeReason ?: "connection closed"))
            }
            var subscription: String? = null
            try {
                subscription = call("jobs.follow", jsonOf("sessionId" to sessionId, "jobId" to jobId, "from" to from))["subId"].str()
                    ?: throw PocketError("bad-reply", "missing job subscription")
                for (event in inbox) {
                    if (event["subId"].str() != subscription) continue
                    when (event["ev"].str()) {
                        "job" -> (event["frame"] as? JsonObject)?.let { emit(it) }
                        "job.end" -> break
                        "job.error" -> throw PocketError(event["error"]["code"].str() ?: "internal", event["error"]["message"].str() ?: "job stream failed")
                    }
                }
            } finally {
                collector.cancel(); disconnect.cancel(); inbox.cancel()
                subscription?.let { withContext(NonCancellable) { runCatching { withTimeout(5_000) { unfollow(it) } } } }
            }
        }
    }
    suspend fun listTerminals(sessionId: String): JsonElement =
        call("terminal.list", jsonOf("sessionId" to sessionId))
    suspend fun createTerminal(sessionId: String, cols: Int = 80, rows: Int = 24, id: String? = null): JsonElement =
        call("terminal.create", jsonOf("sessionId" to sessionId, "cols" to cols, "rows" to rows, "id" to id))
    suspend fun followTerminal(sessionId: String, id: String, attachmentId: String? = null): JsonElement =
        call("terminal.follow", jsonOf("sessionId" to sessionId, "id" to id, "attachmentId" to attachmentId))
    suspend fun writeTerminal(sessionId: String, id: String, attachmentId: String, data: String): JsonElement =
        call("terminal.write", jsonOf("sessionId" to sessionId, "id" to id, "attachmentId" to attachmentId, "data" to data))
    suspend fun resizeTerminal(sessionId: String, id: String, attachmentId: String, cols: Int, rows: Int): JsonElement =
        call("terminal.resize", jsonOf("sessionId" to sessionId, "id" to id, "attachmentId" to attachmentId, "cols" to cols, "rows" to rows))
    suspend fun closeTerminal(sessionId: String, id: String): JsonElement =
        call("terminal.close", jsonOf("sessionId" to sessionId, "id" to id))
    suspend fun modelCatalog(): ModelCatalog = ModelCatalog.parse(call("models.catalog"))
    suspend fun selectModel(sessionId: String, provider: String, model: String, effort: String?) =
        call("models.select", jsonOf("sessionId" to sessionId, "provider" to provider, "model" to model, "reasoningEffort" to effort))
    suspend fun forkSession(sessionId: String, atSeq: Long? = null, allowMigration: Boolean = true): JsonElement =
        call(
            "sessions.fork",
            jsonOf(
                "sessionId" to sessionId,
                "atSeq" to atSeq,
                "allowMigration" to if (allowMigration) null else false,
            ),
        )
    suspend fun follow(sessionId: String, afterSeq: Long? = null, maxMessages: Int? = 50): String =
        call("follow", jsonOf("sessionId" to sessionId, "afterSeq" to afterSeq, "maxMessages" to maxMessages))["subId"].str() ?: ""
    /** Load older turns above the current snapshot window (desktop history.page). */
    suspend fun historyPage(
        sessionId: String,
        throughSeq: Long,
        beforeSeq: Long? = null,
        maxMessages: Int = 100,
    ): JsonElement = call(
        "history.page",
        jsonOf(
            "sessionId" to sessionId,
            "throughSeq" to throughSeq,
            "beforeSeq" to beforeSeq,
            "maxMessages" to maxMessages,
        ),
    )
    suspend fun unfollow(subId: String) = call("unfollow", jsonOf("subId" to subId))
    suspend fun answerApproval(approvalId: String, outcome: String) =
        call("approval.answer", jsonOf("approvalId" to approvalId, "outcome" to outcome))
    suspend fun permissionCatalog(): PermissionCatalog = PermissionCatalog.parse(call("permissions.catalog"))
    suspend fun permission(sessionId: String): String =
        PermissionSelection.parse(call("permissions.get", jsonOf("sessionId" to sessionId))).currentValue
    suspend fun setPermission(sessionId: String, name: String): String =
        PermissionSelection.parse(call("permissions.set", jsonOf("sessionId" to sessionId, "name" to name))).currentValue
    suspend fun presets(): List<String> = call("presets.list")["presets"].arr().orEmpty().mapNotNull { it["id"].str() }

    suspend fun searchSessions(query: String): JsonElement =
        call("sessions.search", jsonOf("query" to query))

    suspend fun workspaceState(): JsonElement = call("workspace.state")
    suspend fun archiveSession(sessionId: String, stopActivity: Boolean = false): JsonElement =
        call("workspace.archiveSession", jsonOf("sessionId" to sessionId, "stopActivity" to if (stopActivity) true else null))
    suspend fun unarchiveSession(sessionId: String): JsonElement =
        call("workspace.unarchiveSession", jsonOf("sessionId" to sessionId))
    suspend fun pinSession(sessionId: String): JsonElement =
        call("workspace.pinSession", jsonOf("sessionId" to sessionId))
    suspend fun unpinSession(sessionId: String): JsonElement =
        call("workspace.unpinSession", jsonOf("sessionId" to sessionId))

    suspend fun getFeedback(sessionId: String): JsonElement =
        call("feedback.get", jsonOf("sessionId" to sessionId))
    suspend fun setFeedback(
        sessionId: String,
        messageId: String,
        rating: String?,
        category: String? = null,
        note: String? = null,
        ifVersion: String? = null,
    ): JsonElement = call(
        "feedback.set",
        buildJsonObject {
            put("sessionId", sessionId)
            put("messageId", messageId)
            if (rating == null) put("rating", JsonNull) else put("rating", rating)
            category?.let { put("category", it) }
            note?.let { put("note", it) }
            ifVersion?.let { put("ifVersion", it) }
        },
    )

    suspend fun sessionAttachment(sessionId: String, attachmentId: String): JsonElement =
        call("session.attachment", jsonOf("sessionId" to sessionId, "attachmentId" to attachmentId))
    suspend fun uploadAttachment(sessionId: String, data: String, name: String? = null): JsonElement =
        call("attachments.upload", jsonOf("sessionId" to sessionId, "data" to data, "name" to name))

    suspend fun referenceFiles(sessionId: String, query: String = ""): JsonElement =
        call("references.files", jsonOf("sessionId" to sessionId, "query" to query))
    suspend fun referenceSessions(sessionId: String, query: String = ""): JsonElement =
        call("references.sessions", jsonOf("sessionId" to sessionId, "query" to query))

    suspend fun answerUserQuestion(sessionId: String, callId: String, answers: List<JsonObject>): JsonElement =
        call(
            "userQuestions.answer",
            buildJsonObject {
                put("sessionId", sessionId)
                put("callId", callId)
                put("answers", buildJsonObject {
                    putJsonArray("answers") { answers.forEach { add(it) } }
                })
            },
        )

    suspend fun workspaceChangesSummary(sessionId: String, seq: Long): JsonElement =
        call("workspaceChanges.summary", jsonOf("sessionId" to sessionId, "seq" to seq))

    // ---- Desktop settings parity (models / plugins / schedule)
    suspend fun modelsProviders(): JsonElement = call("models.providers")
    suspend fun modelsProviderGet(provider: String): JsonElement =
        call("models.providerGet", jsonOf("provider" to provider))
    suspend fun modelsSettingsMutate(ns: String, ops: JsonArray, expectedRevision: Long? = null): JsonElement =
        call(
            "models.settingsMutate",
            buildJsonObject {
                put("ns", ns)
                put("ops", ops)
                expectedRevision?.let { put("expectedRevision", it) }
            },
        )
    suspend fun modelsDiscover(settingsNs: String, request: JsonObject = JsonObject(emptyMap())): JsonElement =
        call("models.discover", buildJsonObject {
            put("settingsNs", settingsNs)
            put("request", request)
        })

    suspend fun pluginsList(): JsonElement = call("plugins.list")
    suspend fun pluginsInventory(): JsonElement = call("plugins.inventory")
    suspend fun pluginsSetEnabled(enabled: Boolean, bundle: String? = null, id: String? = null): JsonElement =
        call(
            "plugins.setEnabled",
            buildJsonObject {
                put("enabled", enabled)
                bundle?.let { put("bundle", it) }
                id?.let { put("id", it) }
            },
        )
    suspend fun pluginsInstall(spec: String, options: JsonObject? = null): JsonElement =
        call(
            "plugins.install",
            buildJsonObject {
                put("spec", spec)
                options?.let { put("options", it) }
            },
            timeoutMs = 1_800_000,
        )
    suspend fun pluginsInspect(spec: String, registry: String? = null): JsonElement =
        call("plugins.inspect", buildJsonObject {
            put("spec", spec)
            registry?.let { put("options", jsonOf("registry" to it)) }
        })
    suspend fun pluginsRegistries(): JsonElement = call("plugins.registries")
    suspend fun pluginsUninstall(bundle: String): JsonElement =
        call("plugins.uninstall", jsonOf("bundle" to bundle), timeoutMs = 1_800_000)
    suspend fun pluginsCancelInstall(requestId: String): JsonElement =
        call("plugins.cancelInstall", jsonOf("requestId" to requestId), timeoutMs = 120_000)
    suspend fun pluginsWaitForInstall(requestId: String): JsonElement =
        call("plugins.waitForInstall", jsonOf("requestId" to requestId), timeoutMs = 1_800_000)
    suspend fun pluginsSettingsDescribe(): JsonElement = call("plugins.settingsDescribe")
    suspend fun pluginsSettingsMutate(ns: String, ops: JsonArray, expectedRevision: Int): JsonElement =
        call("plugins.settingsMutate", buildJsonObject {
            put("ns", ns)
            put("ops", ops)
            put("expectedRevision", expectedRevision)
        })

    suspend fun scheduleCatalog(): JsonElement = call("schedule.catalog")
    suspend fun scheduleList(sessionId: String): JsonElement =
        call("schedule.list", jsonOf("sessionId" to sessionId))
    suspend fun scheduleCreate(args: JsonObject): JsonElement = call("schedule.create", args)
    suspend fun scheduleUpdate(args: JsonObject): JsonElement = call("schedule.update", args)
    suspend fun scheduleDelete(sessionId: String, id: String): JsonElement =
        call("schedule.delete", jsonOf("sessionId" to sessionId, "id" to id))
    suspend fun scheduleHistory(sessionId: String, id: String, limit: Int = 20, before: String? = null): JsonElement =
        call(
            "schedule.history",
            jsonOf("sessionId" to sessionId, "id" to id, "limit" to limit, "before" to before),
        )

    suspend fun credentialsDescribe(ref: String): JsonElement =
        call("credentials.describe", jsonOf("ref" to ref))
    suspend fun credentialsSet(ref: String, value: String): JsonElement =
        call("credentials.set", jsonOf("ref" to ref, "value" to value))
    suspend fun credentialsUnset(ref: String): JsonElement =
        call("credentials.unset", jsonOf("ref" to ref))

    fun close() {
        pump?.cancel(); pump = null
        transport?.close(); transport = null
        if (_state.value != State.Idle) _state.value = State.Closed
    }

    companion object {
        /**
         * Loopback WS must bypass system HTTP proxies that break local upgrades.
         * OkHttp negotiates permessage-deflate by default; keep the 1 KiB threshold
         * in sync with the pocket server (adapted from saya-ch/dsh-mobile).
         */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .minWebSocketMessageToCompress(WsTransport.MIN_MESSAGE_TO_COMPRESS)
            .build()
    }
}
