package dev.dsh.pocket.app

import android.content.Context
import android.os.Build
import android.util.Base64
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class Dot { Online, Connecting, Offline, Unauthorized }
enum class FeedbackLoadStatus { Cold, Loading, Ready, Error }

/** One submission identity spans session creation, local echo, and the Host prompt. */
data class FirstPrompt(val requestId: String, val text: String, val error: String? = null)

data class ChatState(
    val sessionId: String,
    val items: List<Item> = emptyList(),
    val steps: List<StepRecord> = emptyList(),
    val queuedPrompts: List<QueuedPrompt> = emptyList(),
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
    /** Host `plan` projection — plan mode is on (desktop PlanChip). */
    val planMode: Boolean = false,
    val composerMetrics: ComposerMetrics = ComposerMetrics(),
    val goal: JsonObject? = null,
    val agentPreset: String? = null,
    val turns: List<TurnRecord> = emptyList(),
    val turnOutline: List<TurnOutlineEntry> = emptyList(),
    val turnTokenUsage: Map<Int, TurnTokenUsage> = emptyMap(),
    /** Original Host events in the loaded history window; never inferred from rendered items. */
    val inspectorRecords: List<JsonObject> = emptyList(),
    val address: SessionAddress = SessionAddress(sessionId),
) {
    val canControl: Boolean get() = address.canControl
}

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
    private var teamStore = AgentTeamStore()
    private val _team = MutableStateFlow<AgentTeam?>(null)
    val team: StateFlow<AgentTeam?> = _team.asStateFlow()
    private val _subagents = MutableStateFlow<List<SubagentEntry>?>(null)
    val subagents: StateFlow<List<SubagentEntry>?> = _subagents.asStateFlow()
    private fun publishTeam() {
        _team.value = teamStore.team(chat.value?.address, sessions.value)
        _subagents.value = teamStore.subagents(chat.value?.address, sessions.value)
    }
    private val completionReminders = CompletionReminders()
    val completionUnread = completionReminders.unreadIds
    private val completionViewLock = Mutex()
    private var desiredViewedSession: String? = null
    private var completionReadySession: String? = null
    private var completionViewClient: PocketClient? = null
    private var completionViewSession: String? = null
    val approvals = MutableStateFlow<List<Approval>>(emptyList())
    val userQuestions = MutableStateFlow<List<UserQuestion>>(emptyList())
    private val questionStore = UserQuestionStore()
    val archivedIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedIds = MutableStateFlow<Set<String>>(emptySet())
    /** messageId → feedback for the open session. */
    val feedback = MutableStateFlow<Map<String, MessageFeedback>>(emptyMap())
    val feedbackStatus = MutableStateFlow(FeedbackLoadStatus.Cold)
    val feedbackPending = MutableStateFlow<Map<String, Int>>(emptyMap())
    val feedbackFailures = MutableStateFlow<Map<String, String>>(emptyMap())
    private val feedbackLock = Mutex()
    private var feedbackGeneration = 0L
    private var feedbackLoaded: FeedbackRequest? = null
    private data class FeedbackRequest(val client: PocketClient, val sessionId: String, val generation: Long, val hostId: String?) {
        fun sameTarget() = active.value == hostId && chat.value?.sessionId == sessionId
        fun current() = Pocket.client === client && sameTarget() && feedbackGeneration == generation
        fun requireCurrent() { check(current()) { "Feedback target changed" } }
    }
    val catalog = MutableStateFlow<ModelCatalog?>(null)
    private val _catalogLoading = MutableStateFlow(false)
    val catalogLoading: StateFlow<Boolean> = _catalogLoading.asStateFlow()
    private val _catalogError = MutableStateFlow<String?>(null)
    val catalogError: StateFlow<String?> = _catalogError.asStateFlow()
    private val _modelPending = MutableStateFlow<ModelChoice?>(null)
    val modelPending: StateFlow<ModelChoice?> = _modelPending.asStateFlow()
    private val modelCatalogEpoch = AtomicLong()
    private val modelSelectionEpoch = AtomicLong()
    private var modelProjectionSeq = -1L
    private var modelUsesDefault = false
    val presetRoster = MutableStateFlow<List<JsonObject>>(emptyList())
    val workspaces = MutableStateFlow<List<WorkspaceView>>(emptyList())
    /** New-chat workspace selection shared by the composer and navigation. */
    val draftWorkspacePath = MutableStateFlow<String?>(null)
    val goalActivation = MutableStateFlow<String?>(null)
    val pluginCatalogRevision = MutableStateFlow(0L)
    val scheduleCatalogRevision = MutableStateFlow(0L)
    val modelSettingsRevision = MutableStateFlow(0L)
    private var goalProjectionSeq = -1L
    private var goalActivationEpoch = 0L
    private var presetProjectionSeq = -1L
    val ops = MutableStateFlow<Set<String>>(emptySet())
    val chat = MutableStateFlow<ChatState?>(null)
    private val _firstPrompt = MutableStateFlow<FirstPrompt?>(null)
    val firstPrompt: StateFlow<FirstPrompt?> = _firstPrompt.asStateFlow()
    private var firstPromptSessionId: String? = null
    val permission = MutableStateFlow<Map<String, String>>(emptyMap())
    val permissionCatalog = MutableStateFlow<PermissionCatalog?>(null)
    val permissionCatalogLoading = MutableStateFlow(false)
    val permissionCatalogError = MutableStateFlow<String?>(null)
    val permissionChanging = MutableStateFlow(false)
    private val permissionCatalogEpoch = AtomicLong()
    private var permissionProjectionSeq = -1L
    val model = MutableStateFlow<Map<String, ModelChoice>>(emptyMap())
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Handshake version mismatch for the active profile (plugin/app too old). */
    val compatibility = MutableStateFlow<CompatibilityIssue?>(null)
    /** Transient toast-style notices (archive undo, feedback thanks, …). */
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /**
     * One-shot composer edits:
     * - empty string → consume a lone `/` slash trigger (desktop `slash/input-consume-token`)
     * - starts with `/` → claim/replace the draft (desktop leadingInput claim, e.g. `/计划 `)
     * - otherwise → insert an `@` mention token
     */
    val composerInsert = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Live host PTY emulator for the open terminal sheet. */
    val terminal = AnsiTerminal(80, 24)
    /** Bumps whenever [terminal] mutates so Compose can redraw. */
    val terminalRevision = MutableStateFlow(0L)
    val terminalMeta = MutableStateFlow<TerminalMeta?>(null)
    private val _terminalPlatform = MutableStateFlow<String?>(null)
    val terminalPlatform: StateFlow<String?> = _terminalPlatform.asStateFlow()
    /** attachmentId → decoded bytes cache for transcript image bubbles. */
    private val attachmentCache = ConcurrentHashMap<String, ByteArray>()
    /** Last archived session for snackbar 撤销. */
    @Volatile var lastArchivedId: String? = null
        private set

    private var client: PocketClient? = null
    private var connJob: Job? = null
    private var reducer = TranscriptReducer()
    private var inspectorLog = SessionInspectorLog()
    private var subId: String? = null
    private val followEpoch = AtomicLong()
    private val transcriptPublisher = TranscriptPublisher(main) { publishTranscript() }
    private var continuityRepairAttempts = 0
    private var pendingFollowEpoch: Long? = null
    private var terminalSubId: String? = null
    private val terminalOpenEpoch = AtomicLong()
    private var terminalOpening = false
    private var terminalHostId: String? = null
    private var terminalReady: CompletableDeferred<Unit>? = null
    private val terminalRosterRevisions = ConcurrentHashMap<Pair<String, String>, AtomicLong>()
    private val terminalOwnerLocks = ConcurrentHashMap<Pair<String, String>, Mutex>()
    private val terminalStray = HashMap<String, MutableList<JsonObject>>()
    /** Last panel grid size from [TerminalView]; used when creating / attaching a PTY. */
    private var wantCols = 80
    private var wantRows = 24
    private val stray = HashMap<String, MutableList<JsonObject>>()
    /** Per-session follow attempts across reconnects; capped to stop spin loops. */
    private val followAttempts = HashMap<String, Int>()
    private const val MAX_FOLLOW_ATTEMPTS = 3
    /** Slow-link hello / dial budget (ref dsh-mobile SLOW_CONNECTIONS ~120s init wait). */
    private const val CONNECT_TIMEOUT_MS = 120_000L
    /** Interactive pair sheet budget — fail visibly instead of spinning for the full connect wait. */
    private const val PAIR_TIMEOUT_MS = 45_000L
    /** Current reconnect backoff; reset via [ReconnectBackoffPolicy] on Online or network restore. */
    private val backoffMs = AtomicLong(ReconnectBackoffPolicy.INITIAL_MS)
    /** Wakes a pending reconnect delay when the network becomes available. */
    private val reconnectWake = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Collapses NetworkCallback bursts (adapted from dsh-mobile RestoreAttemptGate). */
    private val restoreGate = RestoreAttemptGate()
    /** Minimal task events from the host for local notification text. */
    val taskEvents = MutableSharedFlow<TaskEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun newClient() = PocketClient(appVersion = BuildConfig.APP_VERSION)

    private fun applyCompatibility(hello: HelloResult) {
        val handshake = hello.handshakeVersions()
        compatibility.value = if (handshake == null) {
            CompatibilityIssue(CompatibilityIssueKind.PROTOCOL_UNSUPPORTED)
        } else {
            VersionCompatibility.evaluate(BuildConfig.APP_VERSION, handshake)
        }
    }

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = AppPrefs(ctx); tokens = SecureTokenStore(ctx)
        val loaded = prefs.profiles
        val migrated = if (prefs.profilesMigratedV1) loaded else migrateLegacyProfiles(loaded).also {
            prefs.profiles = it
            prefs.profilesMigratedV1 = true
        }
        profiles.value = migrated
        active.value = prefs.activeProfile?.takeIf { id -> profiles.value.any { it.id == id } }
        active.value?.let { connect(it) }
    }

    /** Profiles sorted by last used (active first-ish via timestamp). */
    fun profilesSorted(): List<Profile> =
        profiles.value.sortedWith(compareByDescending<Profile> { it.lastUsedAt }.thenBy { it.name })

    fun touchLastUsed(id: String, at: Long = System.currentTimeMillis()) {
        profiles.value = profiles.value.map { if (it.id == id) it.copy(lastUsedAt = at) else it }
        prefs.profiles = profiles.value
    }

    /** Apply LAN discovery address updates for paired instance ids. */
    fun applyDiscoveredHosts(found: List<DiscoveredHost>) {
        if (found.isEmpty()) return
        var reconnectId: String? = null
        val next = profiles.value.map { p ->
            val match = found.firstOrNull { it.instanceId == p.instanceId && p.instanceId.isNotBlank() }
                ?: return@map p
            val updated = LanDiscovery.applyAddressUpdate(p, match) ?: return@map p
            if (p.id == active.value) reconnectId = p.id
            updated
        }
        if (next != profiles.value) {
            profiles.value = next
            prefs.profiles = next
            reconnectId?.let { connect(it) }
        }
    }

    val activeProfile get() = profiles.value.firstOrNull { it.id == active.value }

    private fun setDot(id: String, d: Dot) { status.value = status.value + (id to d) }
    private fun setRoute(id: String, r: Route?) {
        routes.value = if (r == null) routes.value - id else routes.value + (id to r)
    }

    fun connect(profileId: String) {
        connJob?.cancel(); client?.close(); client = null
        completionReminders.reset()
        desiredViewedSession = null
        clearFirstPrompt()
        prefs.hostSettings.clear()
        followEpoch.incrementAndGet()
        detachFollow()
        active.value = profileId; prefs.activeProfile = profileId
        sessions.value = emptyList(); chat.value = null; approvals.value = emptyList()
        teamStore.clear(); publishTeam()
        draftWorkspacePath.value = null
        inspectorLog = SessionInspectorLog()
        questionStore.clear()
        userQuestions.value = emptyList(); archivedIds.value = emptySet(); pinnedIds.value = emptySet()
        workspaces.value = emptyList(); presetRoster.value = emptyList(); ops.value = emptySet()
        closeTerminalFollow()
        _terminalPlatform.value = null
        resetFeedback(); attachmentCache.clear()
        invalidatePermissionCatalog()
        invalidateModelCatalog()
        permission.value = emptyMap()
        permissionChanging.value = false
        permissionProjectionSeq = -1L
        compatibility.value = null
        setRoute(profileId, null)
        connJob = main.launch { runConnection(profileId) }
    }

    private fun factoriesFor(p: Profile): List<TransportFactory> {
        if (p.needsRepair) return emptyList()
        return transportPlan(p.host, p.port)
    }

    private suspend fun runConnection(profileId: String) {
        backoffMs.set(ReconnectBackoffPolicy.reset())
        while (currentCoroutineContext().isActive) {
            val p = profiles.value.firstOrNull { it.id == profileId } ?: return
            if (p.needsRepair) {
                setDot(p.id, Dot.Unauthorized)
                return
            }
            val token = tokens.get(p.id) ?: run { setDot(p.id, Dot.Unauthorized); return }
            setDot(p.id, Dot.Connecting)
            val c = newClient()
            try {
                val factories = factoriesFor(p)
                if (factories.isEmpty()) {
                    setDot(p.id, Dot.Offline)
                    errors.tryEmit("地址无效，请重新配对（需要 host:port）")
                    return
                }
                val hello = c.connect(factories, Auth.Token(token), CONNECT_TIMEOUT_MS)
                applyCompatibility(hello)
                client = c; ops.value = hello.ops
                completionReminders.reset()
                completionViewClient = null
                followEpoch.incrementAndGet()
                detachFollow()
                continuityRepairAttempts = 0
                reducer.clearComposerProjections()
                teamStore.resetSequences()
                permissionProjectionSeq = -1L
                modelProjectionSeq = -1L
                questionStore.clear()
                userQuestions.value = emptyList()
                setRoute(p.id, c.route ?: hello.route)
                setDot(p.id, Dot.Online); backoffMs.set(ReconnectBackoffPolicy.reset())
                val remoteInstance = hello.raw["instanceId"].str()
                if (!remoteInstance.isNullOrBlank() && remoteInstance != p.instanceId) {
                    profiles.value = profiles.value.map {
                        if (it.id == p.id) it.copy(instanceId = remoteInstance) else it
                    }
                    prefs.profiles = profiles.value
                }
                touchLastUsed(p.id)
                coroutineScope {
                    launch {
                        while (isActive) {
                            delay(1_000)
                            c.route?.let { setRoute(p.id, it) }
                        }
                    }
                    launch { c.events.collect { if (client === c) onPush(it) } }
                    if ("plugins.settingsDescribe" in hello.ops && "plugins.settingsMutate" in hello.ops) {
                        prefs.hostSettings.bind(c::pluginsSettingsDescribe, c::pluginsSettingsMutate)
                    }
                    launch { refreshAll(c) }
                    c.state.first { it == PocketClient.State.Closed }
                    cancel()
                }
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) { c.close(); throw e }
            } catch (e: Exception) { /* fall through to retry */ }
            client = null; c.close(); setRoute(profileId, null)
            failFirstPrompt("连接已断开，首条消息未确认发送，请重新编辑")
            prefs.hostSettings.clear()
            resetFeedback(clearItems = false)
            followEpoch.incrementAndGet()
            detachFollow()
            closeTerminalFollow()
            ops.value = emptySet()
            invalidatePermissionCatalog()
            invalidateModelCatalog()
            permissionChanging.value = false
            val reason = c.closeReason.orEmpty() + ""
            if ("4401" in reason || "4403" in reason || "auth/" in reason) { setDot(p.id, Dot.Unauthorized); return }
            // Oversized iroh frame: surface the error and stop spinning the open chat.
            if ("line over limit" in reason || "over limit" in reason) {
                errors.tryEmit("消息帧过大，无法同步会话")
                chat.value = chat.value?.copy(loaded = true)
            }
            setDot(p.id, Dot.Offline)
            val wait = backoffMs.get()
            backoffMs.updateAndGet { ReconnectBackoffPolicy.next(it) }
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
        backoffMs.set(ReconnectBackoffPolicy.reset())
        reconnectWake.tryEmit(Unit)
        // Stuck Connecting: drop the half-open client so the loop retries at once.
        if (dot == Dot.Connecting && restoreGate.isCurrent(generation)) {
            client?.close()
        }
    }

    private suspend fun refreshAll(c: PocketClient) {
        if ("completion.state" in ops.value) {
            try {
                val snapshot = c.call("completion.state")
                if (client === c) completionReminders.accept(snapshot)
                syncCompletionView()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors.tryEmit(e.message ?: "完成状态同步失败") }
        }
        runCatching { sessions.value = c.listSessions().sortedByDescending { it.updatedAt } }
        runCatching { approvals.value = c.call("approval.pending")["approvals"].arr().orEmpty().mapNotNull { (it as? JsonObject)?.let(Approval::parse) } }
        loadModelCatalog(c)
        if ("permissions.catalog" in ops.value) loadPermissionCatalog(c)
        if ("presets.list" in ops.value) runCatching {
            val roster = c.call("presets.list")["presets"].arr().orEmpty().filterIsInstance<JsonObject>()
            if (client === c) presetRoster.value = roster
        }
        pluginCatalogRevision.value++
        scheduleCatalogRevision.value++
        modelSettingsRevision.value++
        if ("workspace.state" in ops.value) runCatching { refreshWorkspaceState(c) }
        chat.value?.let { st ->
            refollow(c, st.address, reducer.cursor.takeIf { s -> s >= 0 })
            refreshParentProjections(c, st.address)
            if (!st.address.isSubagent && "feedback.get" in ops.value) loadFeedback(st.sessionId)
        }
    }

    private fun invalidateModelCatalog() {
        modelCatalogEpoch.incrementAndGet()
        modelSelectionEpoch.incrementAndGet()
        catalog.value = null
        _catalogLoading.value = false
        _catalogError.value = null
        _modelPending.value = null
        model.value = emptyMap()
        modelProjectionSeq = -1L
        modelUsesDefault = false
    }

    /** A refresh retains usable data; only a Host reset discards the directory. */
    private suspend fun loadModelCatalog(c: PocketClient) {
        if (client !== c) return
        val epoch = modelCatalogEpoch.incrementAndGet()
        _catalogLoading.value = true
        _catalogError.value = null
        try {
            val value = c.modelCatalog()
            if (client === c && modelCatalogEpoch.get() == epoch) {
                catalog.value = value
                model.value = model.value.mapValues { (_, choice) ->
                    choice.copy(name = value.find(choice.provider, choice.model)?.name ?: choice.model)
                }
                if (modelUsesDefault) chat.value?.sessionId?.let { applyDefaultModel(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (client === c && modelCatalogEpoch.get() == epoch) {
                _catalogError.value = e.message ?: "无法读取主机模型目录"
            }
        } finally {
            if (client === c && modelCatalogEpoch.get() == epoch) _catalogLoading.value = false
        }
    }

    fun reloadModelCatalog() = main.launch {
        val c = client
        if (c == null) {
            invalidateModelCatalog()
            _catalogError.value = "未连接到主机"
        } else {
            loadModelCatalog(c)
        }
    }

    private fun modelChoice(value: kotlinx.serialization.json.JsonElement?): ModelChoice? {
        val provider = value["provider"].str() ?: return null
        val id = value["model"].str() ?: return null
        return ModelChoice(provider, id, catalog.value?.find(provider, id)?.name ?: id, value["reasoningEffort"].str())
    }

    private fun applyDefaultModel(sessionId: String) {
        val value = catalog.value
        val provider = value?.defaultProvider
        val id = value?.defaultModel
        model.value = if (value == null || provider == null || id == null) model.value - sessionId else model.value +
            (sessionId to ModelChoice(provider, id, value.find(provider, id)?.name ?: id, value.defaultEffort))
    }

    private fun applyModelProjection(sessionId: String, seq: Long, value: kotlinx.serialization.json.JsonElement?) {
        if (seq < modelProjectionSeq) return
        modelProjectionSeq = seq
        val nextValue = value["next"]
        val next = modelChoice(nextValue)
        modelUsesDefault = nextValue is JsonNull
        when {
            modelUsesDefault -> applyDefaultModel(sessionId)
            next != null -> model.value = model.value + (sessionId to next)
            else -> model.value = model.value - sessionId
        }
    }

    private fun invalidatePermissionCatalog() {
        permissionCatalogEpoch.incrementAndGet()
        permissionCatalog.value = null
        permissionCatalogLoading.value = false
        permissionCatalogError.value = null
    }

    /** A refresh belongs to one connection generation; late responses cannot revive stale options. */
    private suspend fun loadPermissionCatalog(c: PocketClient) {
        val epoch = permissionCatalogEpoch.incrementAndGet()
        permissionCatalog.value = null
        permissionCatalogLoading.value = true
        permissionCatalogError.value = null
        try {
            val value = c.permissionCatalog()
            if (client === c && permissionCatalogEpoch.get() == epoch) permissionCatalog.value = value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (client === c && permissionCatalogEpoch.get() == epoch) {
                permissionCatalogError.value = e.message ?: "无法读取主机权限目录"
            }
        } finally {
            if (client === c && permissionCatalogEpoch.get() == epoch) permissionCatalogLoading.value = false
        }
    }

    fun reloadPermissionCatalog() = main.launch {
        val c = client
        if (c == null) {
            invalidatePermissionCatalog()
            permissionCatalogError.value = "未连接到主机"
        } else {
            loadPermissionCatalog(c)
        }
    }

    private suspend fun refreshWorkspaceState(c: PocketClient) {
        val state = c.workspaceState()
        if (client !== c) return
        workspaces.value = state["items"].arr().orEmpty().mapNotNull(WorkspaceView::parse)
        archivedIds.value = state["archivedSessionIds"].arr().orEmpty().mapNotNull { it.str() }.toSet()
        pinnedIds.value = state["pinnedSessionIds"].arr().orEmpty().mapNotNull { it.str() }.toSet()
    }

    private fun onPush(m: JsonObject) {
        when (m["ev"].str()) {
            "ops.changed" -> ops.value = m["ops"].arr().orEmpty().mapNotNull { it.str() }.toSet()
            "plugins.changed" -> {
                pluginCatalogRevision.value++
                modelSettingsRevision.value++
            }
            "models.changed", "credentials.changed" -> modelSettingsRevision.value++
            "workspace.changed" -> {
                val frame = m["frame"]
                when (frame["type"].str()) {
                    "baseline" -> {
                        val value = frame["value"]
                        workspaces.value = value["items"].arr().orEmpty().mapNotNull(WorkspaceView::parse)
                        archivedIds.value = value["archivedSessionIds"].arr().orEmpty().mapNotNull { it.str() }.toSet()
                        pinnedIds.value = value["pinnedSessionIds"].arr().orEmpty().mapNotNull { it.str() }.toSet()
                    }
                    "upsert" -> WorkspaceView.parse(frame["workspace"])?.let { row ->
                        workspaces.value = if (workspaces.value.any { it.workspaceId == row.workspaceId })
                            workspaces.value.map { if (it.workspaceId == row.workspaceId) row else it } else workspaces.value + row
                    }
                    "remove" -> workspaces.value = workspaces.value.filter { it.workspaceId != frame["workspaceId"].str() }
                    "order" -> {
                        val rows = workspaces.value.associateBy { it.workspaceId }
                        workspaces.value = frame["workspaceIds"].arr().orEmpty().mapNotNull { rows[it.str()] }
                    }
                    "archived" -> archivedIds.value = frame["archivedSessionIds"].arr().orEmpty().mapNotNull { it.str() }.toSet()
                    "pinned" -> pinnedIds.value = frame["pinnedSessionIds"].arr().orEmpty().mapNotNull { it.str() }.toSet()
                }
            }
            "schedule.changed" -> scheduleCatalogRevision.value++
            "settings.changed" -> {
                prefs.hostSettings.refresh()
                modelSettingsRevision.value++
            }
            "goal.activation" -> if (m["sessionId"].str() == chat.value?.sessionId) {
                goalActivationEpoch++
                goalActivation.value = m["goal"]["activation"].str()
            }
            "permission-presets/catalog-changed" -> {
                invalidatePermissionCatalog()
                client?.let { c -> main.launch { loadPermissionCatalog(c) } }
            }
            "completion" -> completionReminders.accept(m)
            "follow" -> {
                val sid = m["subId"].str() ?: return
                if (sid == subId) applyFrame(m["frame"])
                else if (pendingFollowEpoch == followEpoch.get()) stray.getOrPut(sid) { mutableListOf() }.add(m)
            }
            "follow.end" -> if (m["subId"].str() == subId) recoverFollow()
            "follow.error" -> if (m["subId"].str() == subId) {
                subId = null
                transcriptPublisher.flush()
                val msg = m["error"]["message"].str()
                    ?: m["error"]["code"].str()
                    ?: "follow error"
                errors.tryEmit(msg)
                // Stop the spinner; do not keep asking for the same oversized frame.
                chat.value = chat.value?.copy(loaded = true)
            }
            "session" -> {
                when (m["kind"].str()) {
                "added" -> SessionSummary.parse(m["summary"])?.let { s -> sessions.value = listOf(s) + sessions.value.filter { it.sessionId != s.sessionId } }
                "removed" -> sessions.value = sessions.value.filter { it.sessionId != m["sessionId"].str() }
                "status" -> { val id = m["sessionId"].str(); val r = m["running"].bool() ?: false
                    sessions.value = sessions.value.map { if (it.sessionId == id) it.copy(running = r) else it }
                    // Host lifecycle is independent of the assistant's transient text stream.
                    chat.value?.takeIf { it.sessionId == id }?.let {
                        reducer.setRunning(r)
                        transcriptPublisher.flush()
                    }
                    client?.let { c -> main.launch { refreshTitles(c); if (!r) { delay(4000); refreshTitles(c) } } } }
                "activity" -> { val id = m["sessionId"].str(); val t = m["updatedAt"].long() ?: 0
                    sessions.value = sessions.value.map { if (it.sessionId == id) it.copy(updatedAt = t) else it }.sortedByDescending { it.updatedAt } }
                "error" -> m["message"].str()?.let { errors.tryEmit(it) }
                }
                publishTeam()
            }
            "approval" -> { val a = Approval.parse(m); approvals.value = approvals.value.filter { it.approvalId != a.approvalId } + a }
            "approval.closed" -> approvals.value = approvals.value.filter { it.approvalId != m["approvalId"].str() }
            "user-question" -> {
                questionStore.offer(m)
                userQuestions.value = questionStore.questions()
            }
            "user-question.closed" -> {
                m["questionId"].str()?.let { questionStore.close(it, m["sessionId"].str()) }
                userQuestions.value = questionStore.questions()
            }
            "task" -> TaskEvent.parse(m)?.let { taskEvents.tryEmit(it) }
            "terminal", "terminal.end", "terminal.error" -> {
                val id = m["subId"].str() ?: return
                if (id == terminalSubId) {
                    if (m["ev"].str() == "terminal") applyTerminalFrame(m["frame"])
                    else {
                        val message = m["error"]["message"].str() ?: "终端连接已结束"
                        terminalReady?.completeExceptionally(PocketError("terminal/unavailable", message))
                        if (m["ev"].str() == "terminal.error") errors.tryEmit(message)
                        terminalSubId = null
                        terminalMeta.value = null
                    }
                } else if (terminalOpening) terminalStray.getOrPut(id) { mutableListOf() }.add(m)
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
                        cwd = info["cwd"].str() ?: terminalMeta.value?.cwd,
                    )
                }
                val meta = terminalMeta.value
                val hostId = terminalHostId
                if (meta != null && hostId != null && active.value == hostId) {
                    val key = hostId to meta.sessionId
                    terminalRosterRevisions.getOrPut(key) { AtomicLong() }.incrementAndGet()
                    prefs.rememberTerminal(hostId, meta.sessionId, meta.id)
                    terminalReady?.complete(Unit)
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
                    cwd = info["cwd"].str() ?: terminalMeta.value?.cwd,
                )
            }
        }
    }

    private fun applyFrame(f: kotlinx.serialization.json.JsonElement?) {
        val st = chat.value ?: return
        val frameSession = f["sessionId"].str()
        if (frameSession != null && frameSession != st.sessionId) return
        if (f["type"].str() == "projection" && frameSession == null) return
        val changed = reducer.apply(f)
        if (reducer.needsResync) {
            transcriptPublisher.flush()
            recoverFollow()
            return
        }
        inspectorLog.apply(f)
        when (f["type"].str()) {
            "snapshot", "resume" -> {
                teamStore.baseline(st.sessionId, f["projections"])
                publishTeam()
            }
            "projection" -> f["key"].str()?.let {
                if (teamStore.put(st.sessionId, it, f["seq"].long() ?: -1L, f["value"])) publishTeam()
            }
        }
        val frameType = f["type"].str()
        if (frameType == "snapshot" || frameType == "resume" ||
            frameType == "projection" && (f["key"].str() == "userQuestions" || f["key"].str() == "inbox")) {
            questionStore.apply(st.sessionId, f)
            userQuestions.value = questionStore.questions()
        }
        if (changed && (frameType == "event" ||
                frameType == "assistant-stream" && f["frame"]["type"].str() == "chunk")) {
            continuityRepairAttempts = 0
        }
        if (frameType == "assistant-stream" && f["frame"]["type"].str() == "chunk") {
            if (changed) transcriptPublisher.schedule()
            return
        }
        if (frameType == "snapshot" || frameType == "resume") {
            // Inspect this opening's records, not merged turn metadata: withEarlier()
            // may restore an old timestamp even when the new window omitted its start.
            val boundary = f["records"].arr().orEmpty().lastOrNull {
                val type = it["event"]["type"].str()
                (type == "turn/start" || type == "turn/end") && it["event"]["ignorable"].bool() != true
            }?.get("event")
            val activeAttempt = f["assistantStream"]["activeAttempt"] as? JsonObject
            val activeTurn = activeAttempt?.get("turn").long()
            val boundaryTurn = boundary?.get("data")?.get("turn").long()
            if (boundary == null || activeTurn != null && boundaryTurn != null && activeTurn > boundaryTurn) {
                val known = sessions.value.firstOrNull { it.sessionId == st.sessionId }
                reducer.setRunning(known?.running ?: (activeAttempt != null ||
                    st.running && reducer.turnRecords().lastOrNull()?.closed == false))
            }
        }
        val projection = when (f["type"].str()) {
            "snapshot", "resume" -> f["projections"]
            else -> null
        }
        if (projection != null) {
            val seq = projection["asOfSeq"].long() ?: -1L
            if ((projection["values"] as? JsonObject)?.containsKey("modelSelection") == true) {
                applyModelProjection(st.sessionId, seq, projection["values"]["modelSelection"])
            }
            projection["values"]["title"].str()?.let { title ->
                sessions.value = sessions.value.map {
                    if (it.sessionId == st.sessionId && it.title != title) it.copy(title = title) else it
                }
            }
            if ((projection["values"] as? JsonObject)?.containsKey("permissions") == true && seq >= permissionProjectionSeq) {
                permissionProjectionSeq = seq
                val current = projection["values"]["permissions"]["currentValue"].str()
                permission.value = if (current == null) permission.value - st.sessionId
                    else permission.value + (st.sessionId to current)
            }
        } else if (f["type"].str() == "projection" && f["key"].str() == "permissions") {
            val seq = f["seq"].long() ?: -1L
            if (seq >= permissionProjectionSeq) {
                permissionProjectionSeq = seq
                val current = f["value"]["currentValue"].str()
                permission.value = if (current == null) permission.value - st.sessionId
                    else permission.value + (st.sessionId to current)
            }
        }
        if (f["type"].str() == "projection" && f["key"].str() == "modelSelection") {
            applyModelProjection(st.sessionId, f["seq"].long() ?: -1L, f["value"])
        }
        if (f["type"].str() == "snapshot") followAttempts.remove(st.sessionId)
        var goal = st.goal
        var preset = st.agentPreset
        val values = projection?.get("values") as? JsonObject
        val seq = projection?.get("asOfSeq").long() ?: f["seq"].long() ?: f["cursor"].long() ?: -1L
        if (f["type"].str() == "snapshot" || values?.containsKey("goal") == true || f["key"].str() == "goal") {
            if (seq >= goalProjectionSeq) {
                goalProjectionSeq = seq
                goal = (values?.get("goal") ?: f["value"])["goal"] as? JsonObject
            }
        }
        if (f["type"].str() == "snapshot" || values?.containsKey("agentPreset") == true || f["key"].str() == "agentPreset") {
            if (seq >= presetProjectionSeq) {
                presetProjectionSeq = seq
                preset = (values?.get("agentPreset") ?: f["value"]).str()
            }
        }
        transcriptPublisher.cancel()
        publishTranscript(goal, preset, ready = frameType == "snapshot" || frameType == "resume")
        if (frameType == "snapshot" || frameType == "resume") {
            completionReadySession = st.sessionId
            syncCompletionView()
        }
    }

    private fun publishTranscript(
        goal: JsonObject? = chat.value?.goal,
        preset: String? = chat.value?.agentPreset,
        ready: Boolean = false,
    ) {
        val st = chat.value ?: return
        val turns = reducer.turnRecords()
        chat.value = st.copy(
            items = reducer.items(),
            steps = reducer.stepRecords(),
            queuedPrompts = reducer.queueMessages(),
            turns = turns,
            turnOutline = reducer.turnOutline(),
            turnTokenUsage = reducer.turnTokenUsage(),
            inspectorRecords = inspectorLog.snapshot(),
            title = reducer.title ?: st.title,
            running = reducer.running,
            usage = reducer.usage,
            composerMetrics = reducer.composerMetrics,
            loaded = st.loaded || ready,
            hasMore = reducer.hasMore,
            firstSeq = reducer.firstSeq,
            runStartedAt = if (reducer.running) turns.lastOrNull { !it.closed }?.startedAt else null,
            todos = reducer.todos(),
            planMode = reducer.planMode,
            goal = goal,
            agentPreset = preset,
        )
    }

    /** Revoke the damaged generation before any unsubscribe RPC can suspend. */
    private fun recoverFollow() {
        val c = client ?: return
        val address = chat.value?.address ?: return
        val expectedEpoch = followEpoch.get() + 1
        continuityRepairAttempts++
        if (continuityRepairAttempts > MAX_FOLLOW_ATTEMPTS) {
            followEpoch.incrementAndGet()
            val old = detachFollow()
            errors.tryEmit("会话事件连续性校验失败，请重新打开会话")
            chat.value = chat.value?.copy(loaded = true)
            if (old != null) main.launch {
                try { c.unfollow(old) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Already detached locally. */ }
            }
            return
        }
        main.launch(start = CoroutineStart.UNDISPATCHED) {
            try { refollow(c, address, null) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (currentFollow(c, address, expectedEpoch)) {
                    errors.tryEmit(e.message ?: "会话重新同步失败，请重新打开会话")
                    chat.value = chat.value?.copy(loaded = true)
                }
            }
        }
    }

    /** Revoke delivery eligibility synchronously, before any unsubscribe can suspend. */
    private fun detachFollow(): String? {
        transcriptPublisher.cancel()
        val old = subId
        subId = null
        completionReadySession = null
        syncCompletionView()
        pendingFollowEpoch = null
        goalProjectionSeq = -1L
        presetProjectionSeq = -1L
        goalActivation.value = null
        goalActivationEpoch++
        chat.value?.takeIf { it.loadingOlder }?.let { chat.value = it.copy(loadingOlder = false) }
        stray.clear()
        return old
    }

    private fun currentFollow(c: PocketClient, address: SessionAddress, epoch: Long): Boolean =
        client === c && chat.value?.address == address && followEpoch.get() == epoch

    private suspend fun refollow(c: PocketClient, address: SessionAddress, afterSeq: Long?) {
        if (client !== c || chat.value?.address != address) return
        val sessionId = address.sessionId
        val epoch = followEpoch.incrementAndGet()
        val old = detachFollow()
        try {
            if (old != null) {
                try { c.unfollow(old) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The old subscription is already locally detached. */ }
            }
            if (!currentFollow(c, address, epoch)) return
            val attempts = (followAttempts[sessionId] ?: 0) + 1
            if (attempts > MAX_FOLLOW_ATTEMPTS) {
                errors.tryEmit("会话同步失败（已重试 $MAX_FOLLOW_ATTEMPTS 次）")
                chat.value = chat.value?.copy(loaded = true)
                return
            }
            followAttempts[sessionId] = attempts
            pendingFollowEpoch = epoch
            val sid = c.follow(address, afterSeq)
            if (!currentFollow(c, address, epoch)) {
                // A newer selection won while the Host was opening this subscription.
                try { c.unfollow(sid) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Never adopt an obsolete subscription. */ }
                return
            }
            subId = sid
            pendingFollowEpoch = null
            val buffered = stray.remove(sid)
            stray.clear()
            buffered?.forEach { if (currentFollow(c, address, epoch)) applyFrame(it["frame"]) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (currentFollow(c, address, epoch)) throw e
        } finally {
            if (pendingFollowEpoch == epoch) {
                pendingFollowEpoch = null
                stray.clear()
            }
        }
    }

    /** Called only by the foreground conversation surface, never by background following. */
    fun setViewedSession(sessionId: String?) = main.launch {
        desiredViewedSession = sessionId
        syncCompletionView()
    }

    private fun syncCompletionView() = main.launch {
        completionViewLock.withLock {
            val c = client ?: return@withLock
            if ("completion.view" !in ops.value) return@withLock
            val viewed = desiredViewedSession?.takeIf {
                it == chat.value?.sessionId && it == completionReadySession
            }
            if (completionViewClient === c && completionViewSession == viewed) return@withLock
            try {
                val snapshot = try {
                    c.call("completion.view", jsonOf("sessionId" to (viewed ?: JsonNull)))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (client !== c) throw e
                    errors.tryEmit(e.message ?: "查看状态同步失败")
                    c.call("completion.view", jsonOf("sessionId" to (viewed ?: JsonNull)))
                }
                if (client === c) {
                    completionViewClient = c
                    completionViewSession = viewed
                    completionReminders.accept(snapshot)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (client === c) {
                    errors.tryEmit(e.message ?: "查看状态同步失败")
                    completionViewClient = null
                    completionViewSession = null
                }
            }
        }
    }

    private fun initializeChat(
        sessionId: String,
        seed: FirstPrompt? = null,
        address: SessionAddress = SessionAddress(sessionId),
        preserveSelection: Boolean = false,
    ) {
        transcriptPublisher.cancel()
        continuityRepairAttempts = 0
        followAttempts.remove(sessionId)
        reducer = TranscriptReducer()
        val summary = sessions.value.firstOrNull { it.sessionId == sessionId }
        reducer.setRunning(summary?.running ?: false)
        inspectorLog = SessionInspectorLog()
        permissionProjectionSeq = -1L
        modelProjectionSeq = -1L
        modelUsesDefault = false
        if (!preserveSelection) {
            model.value = model.value - sessionId
            permission.value = permission.value - sessionId
        }
        resetFeedback()
        seed?.let { reducer.echo(it.text, it.requestId) }
        chat.value = ChatState(sessionId, items = reducer.items(), running = reducer.running,
            title = summary?.title, agentPreset = summary?.agentPreset, address = address)
        publishTeam()
    }

    fun openSession(sessionId: String) = main.launch {
        val known = teamStore.address(sessionId)
        val row = sessions.value.firstOrNull { it.sessionId == sessionId }
        val address = known ?: if (row?.origin == "subagent") {
            val parent = row.parentSessionId
            if (parent == null) {
                errors.tryEmit("子智能体缺少父会话地址")
                return@launch
            }
            SessionAddress(sessionId, parent, "unknown")
        } else SessionAddress(sessionId)
        openSession(address).join()
    }

    fun openTeammate(member: AgentTeamMember) = main.launch {
        if (member.canOpen) member.address?.let { openSession(it).join() }
    }

    fun openSession(address: SessionAddress) = openSession(address, preserveSelection = false)

    private fun openSession(address: SessionAddress, preserveSelection: Boolean) = main.launch {
        val sessionId = address.sessionId
        clearFirstPrompt()
        initializeChat(sessionId, address = address, preserveSelection = preserveSelection)
        val c = client ?: return@launch
        val openingReducer = reducer
        fun currentOpening() = client === c && reducer === openingReducer && chat.value?.sessionId == sessionId
        runCatching { refollow(c, address, null) }
            .onFailure {
                if (it is CancellationException) throw it
                if (currentOpening()) {
                    errors.tryEmit(it.message ?: "follow failed")
                    chat.value = chat.value?.copy(loaded = true)
                }
            }
        if (!currentOpening()) return@launch
        refreshParentProjections(c, address)
        if (!address.isSubagent && "permissions.get" in ops.value) runCatching {
            val current = c.permission(sessionId)
            if (currentOpening()) permission.value = permission.value + (sessionId to current)
        }
        if (!currentOpening()) return@launch
        if (!address.isSubagent && "feedback.get" in ops.value) loadFeedback(sessionId)
    }

    private suspend fun refreshParentProjections(c: PocketClient, address: SessionAddress) {
        val parent = address.parentSessionId ?: return
        if ("sessions.projections" !in ops.value) return
        try {
            val projection = c.sessionProjections(teamStore.address(parent) ?: SessionAddress(parent))
            if (client === c && chat.value?.address == address) {
                teamStore.baseline(parent, projection)
                teamStore.address(address.sessionId)?.takeIf { it.parentSessionId == parent }?.let {
                    chat.value = chat.value?.copy(address = it)
                }
                publishTeam()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (client === c && chat.value?.address == address) errors.tryEmit(e.message ?: "团队信息加载失败")
        }
    }

    /**
     * Load older turns when the user scrolls to the top and [ChatState.hasMore] is true.
     * Prepends via [TranscriptReducer.prepend] (same history.page path as desktop dsh).
     */
    fun loadOlderHistory() = main.launch {
        val st = chat.value ?: return@launch
        val c = client ?: return@launch
        val epoch = followEpoch.get()
        val historyReducer = reducer
        if (!HistoryPagingPolicy.canLoadOlder(st.hasMore, st.loadingOlder, st.firstSeq)) return@launch
        chat.value = st.copy(loadingOlder = true)
        try {
            val req = HistoryPagingPolicy.pageRequest(reducer.cursor, st.firstSeq)
            val page = c.historyPage(
                address = st.address,
                throughSeq = req.throughSeq,
                beforeSeq = req.beforeSeq,
                maxMessages = req.maxMessages,
            )
            if (!currentFollow(c, st.address, epoch) || reducer !== historyReducer) return@launch
            reducer.prepend(page["records"], page["hasMore"].bool() ?: false)
            inspectorLog.prepend(page["records"])
            chat.value = chat.value?.copy(
                items = reducer.items(),
                steps = reducer.stepRecords(),
                queuedPrompts = reducer.queueMessages(),
                turns = reducer.turnRecords(),
                turnTokenUsage = reducer.turnTokenUsage(),
                inspectorRecords = inspectorLog.snapshot(),
                composerMetrics = reducer.composerMetrics,
                hasMore = reducer.hasMore,
                firstSeq = reducer.firstSeq,
                loadingOlder = false,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!currentFollow(c, st.address, epoch) || reducer !== historyReducer) return@launch
            errors.tryEmit(e.message ?: "加载历史失败")
            chat.value = chat.value?.copy(loadingOlder = false)
        }
    }

    /** Forget only this submission's provisional echo; confirmed Host history stays intact. */
    private fun clearFirstPrompt() {
        _firstPrompt.value?.let { first ->
            reducer.discardEcho(first.requestId)
            chat.value?.takeIf { it.sessionId == firstPromptSessionId }?.let {
                chat.value = it.copy(items = reducer.items(), queuedPrompts = reducer.queueMessages())
            }
        }
        firstPromptSessionId = null
        _firstPrompt.value = null
    }

    private fun failFirstPrompt(message: String) {
        _firstPrompt.value?.takeIf { it.error == null }?.let {
            _firstPrompt.value = it.copy(error = message)
            errors.tryEmit(message)
        }
    }

    /** The UI copies [FirstPrompt.text] into its draft before dismissing the failed attempt. */
    fun dismissFailedFirstPrompt() = main.launch {
        if (_firstPrompt.value?.error == null) return@launch
        if (firstPromptSessionId != null && chat.value?.sessionId == firstPromptSessionId) {
            closeChatState()
        } else clearFirstPrompt()
    }

    fun closeChat() = main.launch { closeChatState() }

    private suspend fun closeChatState() {
        clearFirstPrompt()
        followEpoch.incrementAndGet()
        val old = detachFollow()
        chat.value = null
        publishTeam()
        resetFeedback()
        inspectorLog = SessionInspectorLog()
        if (old != null) client?.let { c ->
            try { c.unfollow(old) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The local chat is closed even if Host cleanup fails. */ }
        }
    }

    /** Desktop workspace navigation prepares an addressable blank Session before using its tools. */
    suspend fun prepareSidebarSession(
        cwd: String? = null,
        preset: String? = null,
        pendingPermission: String? = null,
        pendingModel: ModelChoice? = null,
    ): String? = withContext(main.coroutineContext.minusKey(Job)) {
        if (_firstPrompt.value != null) return@withContext null
        chat.value?.let { return@withContext it.sessionId }
        val c = requireClient()
        val epoch = followEpoch.get()
        fun current() = client === c && followEpoch.get() == epoch && chat.value == null && _firstPrompt.value == null
        val workspaceId = workspaces.value.firstOrNull { it.path == cwd }?.workspaceId
        val id = c.createSession(cwd = if (workspaceId == null) cwd else null, agentPreset = preset, workspaceId = workspaceId)
        if (!current()) return@withContext null
        pendingModel?.let { m ->
            val selected = modelChoice(c.selectModel(id, m.provider, m.model, m.effort)["selected"])
                ?: error("主机未返回已应用的模型")
            if (!current()) return@withContext null
            model.value = model.value + (id to selected)
        }
        pendingPermission?.let { p ->
            val selected = c.setPermission(id, p)
            if (!current()) return@withContext null
            permission.value = permission.value + (id to selected)
        }
        ensureActive()
        openSession(SessionAddress(id), preserveSelection = true).join()
        id.takeIf { client === c && chat.value?.sessionId == id }
    }

    /** Create a session (optionally with preset), then send [text] into it. */
    fun newSession(text: String, preset: String?, pendingPermission: String?, pendingModel: ModelChoice?, cwd: String? = null) = main.launch {
        if (_firstPrompt.value != null || chat.value != null || text.isBlank()) return@launch
        val first = FirstPrompt(UUID.randomUUID().toString(), text)
        _firstPrompt.value = first
        val c = client
        fun current() = client === c && _firstPrompt.value == first
        try {
            check(c != null) { "未连接" }
            pendingPermission?.let { p ->
                val available = permissionCatalog.value
                check(available != null && available.options.any { it.value == p } && !permissionCatalogLoading.value) {
                    "权限目录已变化，请重新选择访问模式"
                }
            }
            val workspaceId = workspaces.value.firstOrNull { it.path == cwd }?.workspaceId
            val id = c.createSession(cwd = if (workspaceId == null) cwd else null, agentPreset = preset, workspaceId = workspaceId)
            if (!current()) return@launch
            firstPromptSessionId = id
            initializeChat(id, first)
            pendingModel?.let { m ->
                val selected = modelChoice(c.selectModel(id, m.provider, m.model, m.effort)["selected"])
                    ?: error("主机未返回已应用的模型")
                if (!current()) return@launch
                model.value = model.value + (id to selected)
            }
            pendingPermission?.let { p ->
                check("permissions.set" in ops.value) { "主机不支持切换权限" }
                val selected = c.setPermission(id, p)
                if (!current()) return@launch
                permission.value = permission.value + (id to selected)
            }
            if (!current()) return@launch
            // New sessions have no feedback to fetch; follow projections own permission reads.
            refollow(c, SessionAddress(id), null)
            if (!current()) return@launch
            c.prompt(id, text, "queue", requestId = first.requestId)
            if (current()) {
                // Keep the reducer echo until the durable Host event confirms this requestId.
                firstPromptSessionId = null
                _firstPrompt.value = null
            }
        } catch (e: Exception) {
            if (current()) failFirstPrompt(e.message ?: "首条消息发送失败")
            if (e is CancellationException) throw e
        }
    }

    fun send(text: String) = sendWithFiles(text, emptyList(), emptyList())

    private suspend fun sendPrompt(
        c: PocketClient,
        st: ChatState,
        text: String,
        images: List<PromptImage> = emptyList(),
        files: List<PromptFile> = emptyList(),
    ) {
        if (!st.canControl || st.address.isSubagent && files.isNotEmpty()) {
            errors.tryEmit(if (!st.canControl) "此子智能体仅支持查看历史" else "子智能体续聊不支持文件附件")
            return
        }
        val target = reducer
        val requestId = UUID.randomUUID().toString()
        val mode = if (st.running) prefs.busySend.mode else "queue"
        fun syncEcho() {
            if (client === c && reducer === target) {
                chat.value?.takeIf { it.sessionId == st.sessionId }?.let {
                    chat.value = it.copy(items = target.items(), steps = target.stepRecords(),
                        queuedPrompts = target.queueMessages())
                }
            }
        }
        target.echo(text, requestId, mode, images = images.size, files = files.size)
        syncEcho()
        try {
            c.prompt(st.address, text, mode, images, files, requestId = requestId)
        } catch (e: Exception) {
            target.discardEcho(requestId)
            syncEcho()
            if (e is CancellationException) throw e
            errors.tryEmit(e.message ?: "发送失败")
        }
    }

    /** Queue state is owned by Host inbox projections, never by mutation acknowledgements. */
    fun updateQueuedPrompt(
        sessionId: String,
        itemId: String,
        action: QueueAction,
        onComplete: (Boolean) -> Unit = {},
    ) = main.launch {
        val c = client
        val target = reducer
        val epoch = followEpoch.get()
        fun current() = client === c && reducer === target &&
            chat.value?.sessionId == sessionId && followEpoch.get() == epoch
        if (!current()) return@launch
        try {
            check(chat.value?.canControl == true) { "此子智能体仅支持查看历史" }
            check(c != null && status.value[active.value] == Dot.Online) { "未连接到主机" }
            check("queue.update" in ops.value) { "主机不支持修改排队消息" }
            val row = target.queueMessages().firstOrNull { it.id == itemId }
                ?: error("排队消息已变化")
            check(!row.sending) { "消息仍在发送中" }
            when (action) {
                is QueueAction.Edit -> {
                    check(row.editable) { "含附件的排队消息不能编辑" }
                    check(action.text.isNotBlank()) { "消息不能为空" }
                }
                QueueAction.Steer -> check(chat.value?.running == true) { "当前没有运行中的轮次" }
                QueueAction.Remove -> Unit
            }
            c.updateQueuedPrompt(sessionId, itemId, action)
            if (current()) onComplete(true)
        } catch (e: CancellationException) {
            if (current()) onComplete(false)
            throw e
        } catch (e: Exception) {
            if (current()) {
                // Desktop treats a turn/item claimed during steer as already converged.
                val converged = action == QueueAction.Steer && e is PocketError &&
                    (e.code == "session/steer-unavailable" || e.code == "session/queue-item-not-found")
                if (!converged) errors.tryEmit(e.message ?: "修改排队消息失败")
                onComplete(converged)
            }
        }
    }

    /** dsh may title a session asynchronously after the first turn, outside its follow frames. */
    private suspend fun refreshTitles(c: PocketClient) = runCatching {
        sessions.value = c.listSessions().sortedByDescending { it.updatedAt }
        publishTeam()
        chat.value?.let { st -> sessions.value.firstOrNull { it.sessionId == st.sessionId }?.title?.let { t -> chat.value = st.copy(title = t) } }
    }

    /** Same path as desktop InputBar Stop → `sessions.cancel` / plugin `cancel`. */
    fun cancel() = main.launch {
        val st = chat.value ?: return@launch
        val c = client ?: return@launch errors.tryEmit("未连接").let { }
        runCatching { c.cancel(st.address) }
            .onFailure { errors.tryEmit(it.message ?: "终止失败") }
    }

    fun selectModel(sessionId: String?, m: ModelChoice, onComplete: (Boolean) -> Unit = {}) = main.launch {
        val c = client
        if (sessionId == null || c == null || _modelPending.value != null) {
            if (c == null) errors.tryEmit("未连接到主机")
            onComplete(false)
            return@launch
        }
        val epoch = modelSelectionEpoch.incrementAndGet()
        val follow = followEpoch.get()
        val projectionSeq = modelProjectionSeq
        _modelPending.value = m
        try {
            chat.value?.takeIf { it.sessionId == sessionId }?.address?.requireControl()
            val response = c.selectModel(sessionId, m.provider, m.model, m.effort)
            val selected = modelChoice(response["selected"]) ?: error("主机未返回已应用的模型")
            if (client === c && modelSelectionEpoch.get() == epoch) {
                // A newer durable projection outranks a delayed selection acknowledgement.
                if (followEpoch.get() == follow && modelProjectionSeq == projectionSeq) {
                    model.value = model.value + (sessionId to selected)
                    if (chat.value?.sessionId == sessionId) modelUsesDefault = false
                }
                onComplete(true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (client === c && modelSelectionEpoch.get() == epoch) {
                errors.tryEmit(e.message ?: "切换模型失败")
                onComplete(false)
            }
        } finally {
            if (client === c && modelSelectionEpoch.get() == epoch) _modelPending.value = null
        }
    }

    fun setPermission(sessionId: String?, name: String, onComplete: (Boolean) -> Unit = {}) = main.launch {
        val c = client
        if (sessionId == null || c == null || permissionChanging.value || permissionCatalogLoading.value) {
            onComplete(false)
            return@launch
        }
        if (permissionCatalog.value?.options?.none { it.value == name } != false) {
            errors.tryEmit("权限目录已变化，请重新选择访问模式")
            onComplete(false)
            return@launch
        }
        permissionChanging.value = true
        try {
            chat.value?.takeIf { it.sessionId == sessionId }?.address?.requireControl()
            val selected = c.setPermission(sessionId, name)
            if (client === c) {
                permission.value = permission.value + (sessionId to selected)
                if (selected != name) errors.tryEmit("主机未应用所选权限，当前模式为 $selected")
                onComplete(selected == name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (client === c) {
                errors.tryEmit(e.message ?: "切换权限失败")
                onComplete(false)
            }
        } finally {
            if (client === c) permissionChanging.value = false
        }
    }

    fun rename(sessionId: String, title: String, onResult: (Boolean) -> Unit = {}) = mutation(onResult) { c ->
        c.call("sessions.rename", jsonOf("sessionId" to sessionId, "title" to title))
        refreshTitles(c)
    }

    /**
     * Fork the open session at [atSeq] (desktop `message.branch`).
     * Omitting [atSeq] uses the Host's latest completed-turn prefix.
     */
    fun forkSession(atSeq: Long? = null): Job = forkSessionFrom(chat.value?.sessionId.orEmpty(), atSeq)

    fun forkSessionFrom(sessionId: String, atSeq: Long? = null) = main.launch {
        val c = client ?: run { errors.tryEmit("未连接"); return@launch }
        if (sessionId.isBlank()) { errors.tryEmit("无会话"); return@launch }
        if (!hasOp("sessions.fork")) { errors.tryEmit("桌面端未提供分叉"); return@launch }
        runCatching {
            val childId = c.forkSession(sessionId, atSeq)["sessionId"].str()
                ?: error("fork 未返回 sessionId")
            refreshTitles(c)
            openSession(childId)
        }.onFailure { errors.tryEmit(it.message ?: "无法分叉会话，请重试") }
    }

    fun answer(approvalId: String, outcome: String, onResult: (Boolean) -> Unit = {}) = mutation(onResult) { c ->
        check(c.answerApproval(approvalId, outcome)["accepted"].bool() == true) { "审批未被主机接受" }
        approvals.value = approvals.value.filter { it.approvalId != approvalId }
    }

    private fun mutation(onResult: (Boolean) -> Unit, action: suspend (PocketClient) -> Unit) = main.launch {
        try {
            action(client ?: throw PocketError("transport", "未连接"))
            onResult(true)
        } catch (e: CancellationException) {
            onResult(false)
            throw e
        } catch (e: Exception) {
            errors.tryEmit(e.message ?: "操作失败")
            onResult(false)
        }
    }

    fun hasOp(op: String) = op in ops.value

    private fun requireClient(): PocketClient = client ?: throw PocketError("transport", "未连接")

    suspend fun presetsSettings(): JsonElement {
        val c = requireClient()
        val result = c.call("presets.settings")
        if (client === c) presetRoster.value = result["presets"].arr().orEmpty().filterIsInstance<JsonObject>()
        return result
    }
    suspend fun readPreset(id: String): JsonElement = requireClient().call("presets.read", jsonOf("id" to id))

    suspend fun createWorkspace(path: String): WorkspaceView {
        val c = requireClient()
        val workspace = WorkspaceView.parse(c.call("workspace.create", jsonOf("path" to path))["workspace"])
            ?: throw PocketError("bad-reply", "missing workspace")
        refreshWorkspaceState(c)
        return workspace
    }

    suspend fun renameWorkspace(id: String, title: String): WorkspaceView {
        val c = requireClient()
        val workspace = WorkspaceView.parse(c.call("workspace.rename", jsonOf("workspaceId" to id, "title" to title))["workspace"])
            ?: throw PocketError("bad-reply", "missing workspace")
        refreshWorkspaceState(c)
        return workspace
    }

    suspend fun deleteWorkspace(id: String) {
        val c = requireClient()
        c.call("workspace.delete", jsonOf("workspaceId" to id))
        refreshWorkspaceState(c)
    }

    suspend fun moveWorkspace(id: String, beforeId: String? = null) {
        val c = requireClient()
        c.call("workspace.insertBefore", jsonOf("workspaceId" to id, "beforeWorkspaceId" to beforeId))
        refreshWorkspaceState(c)
    }

    suspend fun moveWorkspaceSession(workspaceId: String, sessionId: String, beforeSessionId: String? = null) {
        val c = requireClient()
        c.call("workspace.insertSessionBefore", jsonOf("workspaceId" to workspaceId, "sessionId" to sessionId, "beforeSessionId" to beforeSessionId))
        refreshWorkspaceState(c)
    }

    suspend fun listWorkspaceDirectories(path: String? = null): JsonElement =
        requireClient().call("workspace.directoryList", jsonOf("path" to path))

    suspend fun createWorkspaceDirectory(path: String, name: String): String =
        requireClient().call("workspace.directoryCreate", jsonOf("path" to path, "name" to name)).str()
            ?: throw PocketError("bad-reply", "missing directory path")

    suspend fun startWorkspaceSession(workspaceId: String): String {
        val c = requireClient()
        val id = c.createSession(workspaceId = workspaceId)
        refreshTitles(c)
        refreshWorkspaceState(c)
        return id
    }

    suspend fun commandCatalog(sessionId: String): List<JsonObject> =
        requireClient().call("commands.list", jsonOf("sessionId" to sessionId))["commands"].arr().orEmpty().filterIsInstance<JsonObject>()

    suspend fun getGoal(sessionId: String): JsonElement {
        val c = requireClient()
        val activationEpoch = goalActivationEpoch
        val goal = c.call("goals.get", jsonOf("sessionId" to sessionId))["goal"] ?: JsonNull
        if (client === c && chat.value?.sessionId == sessionId && goalActivationEpoch == activationEpoch) {
            goalActivation.value = goal["activation"].str()
        }
        return goal
    }

    suspend fun mutateGoal(sessionId: String, action: String, ref: JsonObject, objective: String? = null): JsonElement {
        require(action in setOf("edit", "pause", "resume", "clear"))
        val c = requireClient()
        chat.value?.takeIf { it.sessionId == sessionId }?.address?.requireControl()
        val activationEpoch = goalActivationEpoch
        val response = c.call("goals.$action", jsonOf("sessionId" to sessionId, "ref" to ref, "request" to jsonOf("objective" to objective)))
        if (client === c && chat.value?.sessionId == sessionId && goalActivationEpoch == activationEpoch) {
            goalActivation.value = response["goal"]["activation"].str()
        }
        return response
    }

    suspend fun referenceFiles(sessionId: String, query: String): List<FileReference> =
        requireClient().referenceFiles(sessionId, query)["items"].arr().orEmpty().mapNotNull(FileReference::parse)

    suspend fun referenceSessions(sessionId: String, query: String): List<SessionReference> =
        requireClient().referenceSessions(sessionId, query)["items"].arr().orEmpty().mapNotNull(SessionReference::parse)

    fun followJob(sessionId: String, jobId: String, from: Long? = null): Flow<JsonObject> =
        flow { emitAll(requireClient().followJob(sessionId, jobId, from)) }

    suspend fun terminateTerminal(sessionId: String, terminalId: String? = null) = withContext(main.coroutineContext) {
        val c = requireClient()
        val hostId = active.value ?: throw PocketError("transport", "未连接")
        val meta = terminalMeta.value?.takeIf { it.sessionId == sessionId }
        val id = terminalId ?: meta?.id
            ?: throw PocketError("terminal/not-attached", "没有已连接的终端")
        val epoch = terminalOpenEpoch.get()
        c.closeTerminal(sessionId, id)
        if (client === c && active.value == hostId) {
            val key = hostId to sessionId
            terminalRosterRevisions.getOrPut(key) { AtomicLong() }.incrementAndGet()
            prefs.forgetTerminal(hostId, sessionId, id)
            if (terminalOpenEpoch.get() == epoch && terminalMeta.value?.let {
                    it.sessionId == sessionId && it.id == id && it.attachmentId == meta?.attachmentId
                } == true) closeTerminalFollow().join()
        }
    }

    /** Live pocket client while connected; null when idle/disconnected. */
    fun clientOrNull(): PocketClient? = client

    fun executeCommand(line: String, onResult: (Boolean) -> Unit = {}) = mutation(onResult) { c ->
        val st = chat.value ?: error("无会话")
        st.address.requireControl()
        val result = c.executeCommand(st.sessionId, line)["result"]
        check(result["kind"].str() != "error") { result["text"].str() ?: "指令失败" }
        result["text"].str()?.takeIf { it.isNotBlank() }?.let { notices.tryEmit(it) }
    }

    fun recordSessionFeedback(category: String?, note: String, onResult: (Result<Unit>) -> Unit) : Job {
        val request = feedbackRequest()
        return main.launch {
            if (request == null) {
                onResult(Result.failure(PocketError("disconnected", "Host disconnected")))
                return@launch
            }
            val result = try {
                request.requireCurrent()
                val receipt = request.client.call("sessionFeedback.record", jsonOf(
                    "sessionId" to request.sessionId, "category" to category,
                    "text" to note.trim().takeIf { it.isNotEmpty() },
                ))
                check(receipt["recorded"].bool() == true) { "Feedback was not recorded" }
                Result.success(Unit)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
            if (request.sameTarget()) {
                if (request.current() && result.isSuccess) notices.tryEmit(L.thanksFeedback())
                onResult(if (request.current()) result else Result.failure(PocketError("disconnected", "Host disconnected")))
            }
        }
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

    fun sendImages(text: String, images: List<PromptImage>) = sendWithFiles(text, images, emptyList())

    fun sendWithFiles(text: String, images: List<PromptImage>, files: List<PromptFile>) = main.launch {
        if (_firstPrompt.value != null) return@launch
        val c = client ?: return@launch
        val st = chat.value ?: return@launch
        sendPrompt(c, st, text, images, files)
    }

    /** Upload a non-image file; returns receiptId or null on failure. */
    suspend fun uploadFile(sessionId: String, bytes: ByteArray, name: String?): UploadReceipt? {
        val c = client ?: return null
        if (!hasOp("attachments.upload")) return null
        return runCatching {
            check(chat.value?.takeIf { it.sessionId == sessionId }?.address?.isSubagent != true) {
                "子智能体续聊不支持文件附件"
            }
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

    fun searchSessions(query: String, onResult: (Result<List<SessionSummary>>) -> Unit) = main.launch {
        try {
            val c = client ?: throw PocketError("transport", "未连接")
            val items = if (query.isBlank()) emptyList() else c.searchSessions(query)["items"].arr().orEmpty().mapNotNull(SessionSummary::parse)
            onResult(Result.success(items))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { onResult(Result.failure(e)) }
    }

    fun archiveSession(sessionId: String, stopActivity: Boolean = false, onResult: (Boolean) -> Unit = {}, onActivity: (JsonElement) -> Unit = {}) = mutation(onResult) { c ->
        try { c.archiveSession(sessionId, stopActivity) }
        catch (e: PocketError) {
            if (e.code == "workspace/session-active") onActivity(e.details ?: JsonNull)
            throw e
        }
        lastArchivedId = sessionId
        refreshWorkspaceState(c)
        notices.tryEmit(L.archivedToast())
    }

    fun unarchiveSession(sessionId: String, onResult: (Boolean) -> Unit = {}) = mutation(onResult) { c ->
        c.unarchiveSession(sessionId)
        refreshWorkspaceState(c)
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

    private fun resetFeedback(clearItems: Boolean = true) {
        feedbackGeneration++
        feedbackLoaded = null
        feedbackStatus.value = FeedbackLoadStatus.Cold
        feedbackPending.value = emptyMap()
        feedbackFailures.value = emptyMap()
        if (clearItems) feedback.value = emptyMap()
    }

    private fun feedbackRequest(): FeedbackRequest? {
        val c = client ?: return null
        val sessionId = chat.value?.sessionId ?: return null
        return FeedbackRequest(c, sessionId, feedbackGeneration, active.value)
    }

    private suspend fun loadFeedback(sessionId: String) {
        val request = feedbackRequest()?.takeIf { it.sessionId == sessionId } ?: return
        feedbackLock.withLock { if (request.current()) ensureFeedback(request) }
    }

    /** Reads and writes share one lock: a late list must not overwrite a newer committed rating. */
    private suspend fun ensureFeedback(request: FeedbackRequest): Result<Unit> {
        if (feedbackLoaded == request && feedbackStatus.value == FeedbackLoadStatus.Ready) return Result.success(Unit)
        feedbackStatus.value = FeedbackLoadStatus.Loading
        return try {
            val rows = request.client.getFeedback(request.sessionId)["items"].arr()
                ?: throw PocketError("bad-reply", "Host did not return feedback items")
            request.requireCurrent()
            feedback.value = rows.associate { row ->
                val item = MessageFeedback.parse(row) ?: throw PocketError("bad-reply", "Invalid feedback item")
                item.messageId to item
            }
            feedbackLoaded = request
            feedbackStatus.value = FeedbackLoadStatus.Ready
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (request.current()) feedbackStatus.value = FeedbackLoadStatus.Error
            Result.failure(e)
        }
    }

    private fun feedbackAction(
        messageId: String,
        onResult: (Result<Unit>) -> Unit,
        queue: Boolean = false,
        action: suspend (FeedbackRequest) -> Unit,
    ): Job {
        val request = feedbackRequest()
        return main.launch {
            if (request == null || !request.current() || status.value[active.value] != Dot.Online) {
                if (request == null || request.sameTarget()) onResult(Result.failure(PocketError("disconnected", "Host disconnected")))
                return@launch
            }
            if (!queue && messageId in feedbackPending.value) return@launch
            feedbackPending.update { it + (messageId to ((it[messageId] ?: 0) + 1)) }
            feedbackFailures.value = feedbackFailures.value - messageId
            val result = try {
                feedbackLock.withLock { request.requireCurrent(); action(request) }
                Result.success(Unit)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
            finally {
                if (request.current()) feedbackPending.update {
                    val remaining = (it[messageId] ?: 1) - 1
                    if (remaining == 0) it - messageId else it + (messageId to remaining)
                }
            }
            if (request.sameTarget()) {
                onResult(if (request.current()) result else Result.failure(PocketError("disconnected", "Host disconnected")))
            }
        }
    }

    /** A cold click waits for the stored rating before deciding whether to open or retract. */
    fun chooseMessageFeedback(messageId: String, rating: String, onDialog: () -> Unit) =
        feedbackAction(messageId, { result ->
            result.exceptionOrNull()?.let { error ->
                feedbackFailures.value = feedbackFailures.value + (messageId to
                    if ((error as? PocketError)?.code == "version-conflict") "version-conflict" else "error")
            }
        }) { request ->
            val loaded = ensureFeedback(request)
            request.requireCurrent()
            if (loaded.isFailure || feedback.value[messageId]?.rating != rating) onDialog()
            else commitMessageFeedback(request, messageId, null, null, null)
        }

    fun setMessageFeedback(
        messageId: String,
        rating: String,
        category: String? = null,
        note: String? = null,
        onResult: (Result<Unit>) -> Unit,
    ) = feedbackAction(messageId, onResult, queue = true) { request ->
        ensureFeedback(request).getOrThrow()
        commitMessageFeedback(request, messageId, rating, category, note?.trim()?.takeIf { it.isNotEmpty() })
        notices.tryEmit(L.thanksFeedback())
    }

    private suspend fun commitMessageFeedback(
        request: FeedbackRequest, messageId: String, rating: String?, category: String?, note: String?,
    ) {
        try {
            val result = request.client.setFeedback(request.sessionId, messageId, rating, category, note, feedback.value[messageId]?.version)
            request.requireCurrent()
            feedback.value = if (rating == null) feedback.value - messageId else {
                val next = MessageFeedback.parse(result) ?: throw PocketError("bad-reply", "Host did not return the saved feedback")
                feedback.value + (messageId to next)
            }
        } catch (e: PocketError) {
            if (request.current() && e.code == "version-conflict") {
                val current = e.details["current"]
                    ?: throw PocketError("bad-reply", "Feedback conflict did not include the current item")
                feedback.value = if (current == JsonNull) feedback.value - messageId else {
                    val item = MessageFeedback.parse(current)
                        ?: throw PocketError("bad-reply", "Invalid current feedback item")
                    feedback.value + (messageId to item)
                }
            }
            throw e
        }
    }

    fun answerUserQuestion(callId: String, answers: List<JsonObject>, onResult: (Boolean) -> Unit = {}) = mutation(onResult) { c ->
        val st = chat.value ?: error("无会话")
        st.address.requireControl()
        check(c.answerUserQuestion(st.sessionId, callId, answers)["accepted"].bool() == true) { "提问已变化或答案未被接受" }
        if (client === c) {
            questionStore.submitted(st.sessionId, callId)
            userQuestions.value = questionStore.questions()
        }
    }

    /** Insert a mention token into the open composer (`@path` / session mention). */
    fun insertComposer(token: String) {
        if (token.isNotBlank()) composerInsert.tryEmit(token)
    }

    /** Clear a typed `/` trigger after a slash-menu pick (desktop consume-token). */
    fun consumeComposerSlash() {
        composerInsert.tryEmit("")
    }

    /** Replace the draft with a claimed slash command token (e.g. `/目标 `). */
    fun claimComposerCommand(token: String) {
        val t = token.trim()
        if (t.isNotEmpty()) composerInsert.tryEmit(if (t.endsWith(' ')) t else "$t ")
    }

    /** Load the announced summary for this session, without consulting the currently open chat. */
    suspend fun fetchChangesSummary(sessionId: String, seq: Long): WorkspaceChangesSummary? {
        val c = client ?: return null
        if (!hasOp("workspaceChanges.summary")) return null
        return WorkspaceChangesSummary.parse(c.workspaceChangesSummary(sessionId, seq)["summary"])
    }

    /** File indices refer to the unmodified Host summary list for the same session and event. */
    suspend fun fetchChangesDiff(sessionId: String, seq: Long, index: Int): WorkspaceFileDiff? {
        val c = client ?: return null
        if (!hasOp("workspaceChanges.diff")) return null
        return WorkspaceFileDiff.parse(c.workspaceChangesDiff(sessionId, seq, index)["diff"])
    }

    suspend fun listFiles(sessionId: String, path: String) = client?.listFiles(sessionId, path)
        ?: throw PocketError("transport", "未连接")
    suspend fun readFile(sessionId: String, path: String, offset: Int? = null, limit: Int? = null) =
        client?.readFile(sessionId, path, offset, limit)
    suspend fun readFileBytes(sessionId: String, path: String) = client?.readFileBytes(sessionId, path)
        ?: throw PocketError("transport", "未连接")
    suspend fun listJobs(sessionId: String) = client?.listJobs(sessionId)
        ?: throw PocketError("transport", "未连接")
    fun killJob(sessionId: String, jobId: String, onResult: (Boolean) -> Unit = {}) = mutation(onResult) { c ->
        c.killJob(sessionId, jobId)
    }

    /** Host-authoritative roster across this profile's current and previously attached owners. */
    suspend fun terminalSessions(sessionId: String): TerminalSessionCatalog = withContext(main.coroutineContext) {
        val c = requireClient()
        val hostId = active.value ?: throw PocketError("transport", "未连接")
        val owners = (listOf(sessionId) + prefs.terminalOwnerIds(hostId)).distinct()
        val entries = owners.flatMap { owner ->
            terminalOwnerLocks.getOrPut(hostId to owner) { Mutex() }.withLock {
                listTerminalOwner(c, hostId, owner)
            }
        }
        TerminalSessionCatalog(entries, _terminalPlatform.value)
    }

    private suspend fun listTerminalOwner(c: PocketClient, hostId: String, sessionId: String): List<TerminalSessionEntry> {
        val key = hostId to sessionId
        val revision = terminalRosterRevisions.getOrPut(key) { AtomicLong() }.get()
        val result = c.listTerminals(sessionId)
        if (client !== c || active.value != hostId)
            throw CancellationException("Terminal connection superseded")
        val rows = result["terminals"].arr() ?: throw PocketError("bad-reply", "未返回终端列表")
        val entries = rows.map { TerminalSessionEntry.parse(sessionId, it) }
        _terminalPlatform.value = result["platform"].str()
        if (terminalRosterRevisions[key]?.get() == revision)
            prefs.reconcileTerminals(hostId, sessionId, entries.map { it.id })
        return entries
    }

    /** Attach an existing identity; only a fresh owner or explicit New allocates a shell. */
    fun openTerminal(sessionId: String, forceNew: Boolean = false, terminalId: String? = null): Job =
        main.launch(start = CoroutineStart.UNDISPATCHED) {
            val epoch = terminalOpenEpoch.incrementAndGet()
            val c = client
            val hostId = active.value
            val oldSubId = terminalSubId
            terminalSubId = null
            terminalReady?.cancel()
            val ready = CompletableDeferred<Unit>()
            terminalReady = ready
            terminalOpening = true
            terminalStray.clear()
            terminalMeta.value = null
            terminalHostId = hostId
            terminal.clear()
            terminal.resize(wantCols, wantRows)
            terminalRevision.value = terminal.revision
            var followed: String? = null
            var attached = false
            fun current() = client === c && active.value == hostId && terminalOpenEpoch.get() == epoch
            try {
                if (c == null || hostId == null) throw PocketError("transport", "未连接")
                if (!hasOp("terminal.list") || !hasOp("terminal.follow"))
                    throw PocketError("unsupported", "桌面端未提供终端")
                if (oldSubId != null) {
                    try { c.unfollow(oldSubId) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Already detached locally. */ }
                }
                if (!current()) return@launch
                val key = hostId to sessionId
                val info = terminalOwnerLocks.getOrPut(key) { Mutex() }.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!current()) throw CancellationException("Terminal attachment superseded")
                    val saved = prefs.selectedTerminalId(hostId, sessionId)
                    val existing = if (forceNew) null else selectTerminal(
                        listTerminalOwner(c, hostId, sessionId), saved, terminalId)
                    if (!current()) throw CancellationException("Terminal attachment superseded")
                    existing ?: run {
                        if (!hasOp("terminal.create")) throw PocketError("unsupported", "桌面端未提供新建终端")
                        // Keep a discovery hint for ambiguous transport outcomes. It is not a
                        // selected/restored process; the next Host list decides whether it exists.
                        val requestedId = UUID.randomUUID().toString()
                        terminalRosterRevisions.getOrPut(key) { AtomicLong() }.incrementAndGet()
                        prefs.discoverTerminal(hostId, sessionId, requestedId)
                        withContext(NonCancellable) {
                            val result = c.createTerminal(sessionId, cols = wantCols, rows = wantRows, id = requestedId)
                            TerminalSessionEntry.parse(sessionId, result["terminal"]).also {
                                terminalRosterRevisions.getOrPut(key) { AtomicLong() }.incrementAndGet()
                                prefs.discoverTerminal(hostId, sessionId, it.id)
                                if (current()) _terminalPlatform.value = result["platform"].str()
                            }
                        }
                    }
                }
                // NonCancellable RPC blocks keep the same dispatcher, so leaving them does not
                // necessarily check the cancelled caller before the next non-cancellable RPC.
                currentCoroutineContext().ensureActive()
                if (!current()) return@launch
                // Finish obtaining the receipt even if this panel is disposed during the request,
                // so its subscription can always be unfollowed on the captured client.
                var attachmentId: String? = null
                withContext(NonCancellable) {
                    val follow = c.followTerminal(sessionId, info.id)
                    followed = follow["subId"].str() ?: throw PocketError("bad-reply", "no terminal subscription")
                    attachmentId = follow["attachmentId"].str() ?: throw PocketError("bad-reply", "no attachmentId")
                }
                currentCoroutineContext().ensureActive()
                if (!current()) return@launch
                terminalSubId = followed
                terminalMeta.value = TerminalMeta(sessionId, info.id, attachmentId!!, info.title, info.state, info.cwd)
                terminalOpening = false
                val buffered = terminalStray.remove(followed)
                terminalStray.clear()
                buffered?.forEach(::onPush)
                withTimeout(60_000) { ready.await() }
                if (!current()) return@launch
                attached = true
                terminal.resize(wantCols, wantRows)
                terminalRevision.value = terminal.revision
                if (terminalMeta.value?.state == "running")
                    c.resizeTerminal(sessionId, info.id, attachmentId!!, wantCols, wantRows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current()) errors.tryEmit(e.message ?: "打开终端失败")
            } finally {
                if (current()) {
                    terminalOpening = false
                    terminalStray.clear()
                    if (!attached) {
                        terminalSubId = null
                        terminalMeta.value = null
                        terminalHostId = null
                    }
                }
                if (!attached && followed != null && c != null)
                    withContext(NonCancellable) { runCatching { c.unfollow(followed!!) } }
            }
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

    fun closeTerminalFollow(): Job {
        // Detach before suspending so an older unfollow cannot erase a newly opened panel.
        terminalOpenEpoch.incrementAndGet()
        terminalOpening = false
        terminalStray.clear()
        val c = client
        val old = terminalSubId
        terminalSubId = null
        terminalReady?.cancel()
        terminalReady = null
        terminalHostId = null
        terminalMeta.value = null
        // Keep the last screen copyable while offline. The next open clears it before attaching.
        return main.launch {
            if (c != null && old != null) runCatching { c.unfollow(old) }
        }
    }

    /** Pair using an already-parsed [PairingLink] (host:port WebSocket). */
    suspend fun pair(name: String, link: PairingLink): Result<Profile> =
        pairHost(name, link.host, link.port, link.code)

    /** Pair over Tailscale / LAN WebSocket (host:port). */
    suspend fun pairHost(name: String, host: String, port: Int, code: String): Result<Profile> = withContext(Dispatchers.IO) {
        if (host.isBlank() || port !in 1..65535) {
            return@withContext Result.failure(Exception("地址无效：需要 host:port（Tailscale IP 或局域网地址）"))
        }
        val c = newClient()
        try {
            val draft = Profile("", name.ifBlank { "电脑" }, host = host, port = port)
            val factories = factoriesFor(draft)
            if (factories.isEmpty()) return@withContext Result.failure(Exception("无法建立 WebSocket 连接"))
            val hello = c.connect(factories, Auth.Pair(code, Build.MODEL ?: "Android"), PAIR_TIMEOUT_MS)
            val mismatch = pairCompatibilityIssue(hello)
            if (mismatch != null) {
                return@withContext Result.failure(Exception(compatibilityMessage(mismatch)))
            }
            val token = hello.issuedToken ?: error("服务端未返回令牌")
            val p = Profile(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { host },
                host = host,
                port = port,
                deviceId = hello.deviceId,
                instanceId = hello.raw["instanceId"].str().orEmpty(),
                lastUsedAt = System.currentTimeMillis(),
            )
            tokens.put(p.id, token)
            withContext(main.coroutineContext) {
                compatibility.value = null
                upsertProfile(p)
            }
            Result.success(p)
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception(L.pairTimeout()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: PocketError) {
            Result.failure(Exception(when {
                e.code.contains("expired") -> "配对码已过期"
                e.code.startsWith("pair/") -> "配对码错误或未开启配对"
                e.code == "transport" -> "无法连上桌面端，请确认电脑已打开配对且手机可达该 host:port（同一 Tailscale 或局域网）"
                else -> e.message
            }))
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: L.pairFailed()))
        } finally { c.close() }
    }

    /** Version handshake check for pair — fail the sheet instead of saving a dead profile. */
    internal fun pairCompatibilityIssue(hello: HelloResult): CompatibilityIssue? {
        val handshake = hello.handshakeVersions()
            ?: return CompatibilityIssue(CompatibilityIssueKind.PROTOCOL_UNSUPPORTED)
        return VersionCompatibility.evaluate(BuildConfig.APP_VERSION, handshake)
    }

    internal fun compatibilityMessage(issue: CompatibilityIssue): String = when (issue.kind) {
        CompatibilityIssueKind.PLUGIN_TOO_OLD -> L.pluginTooOld(issue.requiredVersion)
        CompatibilityIssueKind.APP_TOO_OLD -> L.appTooOld(issue.requiredVersion)
        CompatibilityIssueKind.PROTOCOL_UNSUPPORTED -> L.protocolUnsupported()
    }

    private fun upsertProfile(p: Profile) {
        val withoutDup = if (p.instanceId.isNotBlank()) {
            profiles.value.filterNot { it.instanceId == p.instanceId }
        } else {
            profiles.value
        }
        profiles.value.filter { old -> withoutDup.none { it.id == old.id } }.forEach { tokens.remove(it.id) }
        profiles.value = withoutDup + p
        prefs.profiles = profiles.value
    }

    fun removeProfile(id: String) = main.launch {
        if (active.value == id) {
            connJob?.cancel(); client?.close(); client = null
            clearFirstPrompt()
            prefs.hostSettings.clear()
            followEpoch.incrementAndGet()
            detachFollow()
            active.value = null; prefs.activeProfile = null
            sessions.value = emptyList(); chat.value = null
            resetFeedback()
            draftWorkspacePath.value = null
            workspaces.value = emptyList(); presetRoster.value = emptyList(); ops.value = emptySet()
            questionStore.clear()
            approvals.value = emptyList(); userQuestions.value = emptyList()
            archivedIds.value = emptySet(); pinnedIds.value = emptySet()
            closeTerminalFollow()
            _terminalPlatform.value = null
            invalidatePermissionCatalog()
            invalidateModelCatalog()
            permission.value = emptyMap()
            permissionChanging.value = false
        }
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

    /**
     * Soft probe for one inactive profile: TCP reachability, then treat missing token as revoked.
     * Used by [DeviceStatusPoller] (max 2 concurrent, deduped).
     */
    fun probeOne(p: Profile): DeviceStatusPoller.ProbeOutcome {
        if (p.needsRepair || tokens.get(p.id) == null) {
            return DeviceStatusPoller.ProbeOutcome(reachable = false, revoked = true)
        }
        val ok = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(p.host, p.port), 2500) }
        }.isSuccess
        return DeviceStatusPoller.ProbeOutcome(reachable = ok, revoked = false)
    }

    fun applyProbeResult(requested: Profile, outcome: Result<DeviceStatusPoller.ProbeOutcome>) {
        val current = profiles.value.firstOrNull { it.id == requested.id } ?: return
        if (current.id == active.value) return
        val result = outcome.getOrNull()
        val next = PairedDeviceStatusPolicy.applyProbe(
            currentDot = status.value[current.id],
            current = current,
            requested = requested,
            reachable = result?.reachable == true,
            revoked = result?.revoked == true || outcome.isFailure && outcome.exceptionOrNull()?.message?.contains("4403") == true,
        )
        setDot(current.id, next)
    }

    /** Soft probe: TCP check for inactive host:port profiles (legacy entry; prefer poller). */
    fun probe() = main.launch {
        profiles.value.filter { it.id != active.value }.forEach { p ->
            if (p.needsRepair || tokens.get(p.id) == null) {
                setDot(p.id, Dot.Unauthorized)
                return@forEach
            }
            launch(Dispatchers.IO) {
                val outcome = probeOne(p)
                if (p.id != active.value) {
                    setDot(p.id, when {
                        outcome.revoked -> Dot.Unauthorized
                        outcome.reachable -> Dot.Online
                        else -> Dot.Offline
                    })
                }
            }
        }
    }
}

data class TerminalMeta(val sessionId: String, val id: String, val attachmentId: String, val title: String? = null, val state: String? = null, val cwd: String? = null)

data class TerminalSessionCatalog(val entries: List<TerminalSessionEntry>, val platform: String? = null)

data class TerminalSessionEntry(
    val sessionId: String,
    val id: String,
    val title: String? = null,
    val state: String? = null,
    val cwd: String? = null,
) {
    companion object {
        internal fun parse(sessionId: String, value: JsonElement?): TerminalSessionEntry = TerminalSessionEntry(
            sessionId, value["id"].str()?.takeIf { it.isNotBlank() }
                ?: throw PocketError("bad-reply", "no terminal id"),
            value["title"].str(), value["state"].str(), value["cwd"].str(),
        )
    }
}

/** Missing explicit/saved identities never silently become a different shell. */
internal fun selectTerminal(
    entries: List<TerminalSessionEntry>,
    savedId: String?,
    explicitId: String?,
): TerminalSessionEntry? {
    val preferred = explicitId ?: savedId
    if (preferred != null) return entries.firstOrNull { it.id == preferred }
        ?: throw PocketError("terminal/unavailable", "终端已退出或已由主机回收，请选择其他会话或新建")
    return entries.firstOrNull { it.state == "running" } ?: entries.firstOrNull()
}
