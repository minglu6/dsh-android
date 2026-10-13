package dev.dsh.pocket.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.dsh.pocket.protocol.Route
import dev.dsh.pocket.protocol.classifyWsRoute
import dev.dsh.pocket.protocol.str
import dev.dsh.pocket.protocol.int
import dev.dsh.pocket.protocol.bool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device tokens, AES-256-GCM encrypted with a non-exportable Android Keystore key. */
class SecureTokenStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("tokens", Context.MODE_PRIVATE)
    private val alias = "dsh-pocket-tokens"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    fun put(profileId: String, token: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = c.iv + c.doFinal(token.toByteArray())
        prefs.edit().putString(profileId, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(profileId: String): String? = runCatching {
        val blob = Base64.decode(prefs.getString(profileId, null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12)) }
        String(c.doFinal(blob, 12, blob.size - 12))
    }.getOrNull()

    fun remove(profileId: String) = prefs.edit().remove(profileId).apply()
}

/**
 * Persisted desktop connection.
 *
 * [host]/[port] are required for Tailscale / LAN WebSocket.
 * Legacy fields [transport] / [nodeId] remain only so old prefs decode; profiles
 * that lack a usable host:port are [needsRepair] and must re-pair.
 */
/** Trim + truncate to 64 chars like plugin `devices.js` / `PATCH /pocket/devices`. */
internal fun clipProfileName(name: String): String {
    val n = name.trim().take(64)
    if (n.isEmpty()) error("profile name required")
    return n
}

@Serializable
data class Profile(
    val id: String,
    val name: String,
    /** Legacy; ignored. */
    val transport: String = "",
    /** Legacy iroh node id; ignored — forces re-pair when host/port missing. */
    val nodeId: String = "",
    val deviceId: String? = null,
    /** Tailscale / LAN listen address. */
    val host: String = "",
    val port: Int = 0,
    /** Desktop plugin instance id (64 hex) for LAN auto-discovery address updates. */
    val instanceId: String = "",
    /** Epoch ms; used to sort the connections list. */
    val lastUsedAt: Long = 0L,
) {
    val needsRepair: Boolean get() = host.isBlank() || port !in 1..65535

    val routeKind: Route? get() = if (needsRepair) null else classifyWsRoute(host)

    val kind: String get() = when {
        needsRepair -> "需重新配对"
        else -> when (routeKind) {
            Route.WsTailscale -> "Tailscale"
            Route.WsLan -> "LAN"
            Route.WsLocal -> "local"
            null -> "需重新配对"
        }
    }

    /** One-line subtitle on the connections list. */
    val subtitle: String get() = when {
        needsRepair -> "连接信息不完整，请重新配对"
        else -> "$host:$port"
    }
}

/**
 * Normalize legacy prefs into the current [Profile] shape.
 * - Drops unusable blank rows
 * - Marks iroh-era rows (nodeId without host:port) as needing re-pair via [Profile.needsRepair]
 * - Seeds [Profile.lastUsedAt] from list order when unset so sort-by-last-used is stable
 */
fun migrateLegacyProfiles(raw: List<Profile>, now: Long = System.currentTimeMillis()): List<Profile> {
    if (raw.isEmpty()) return emptyList()
    val cleaned = raw.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    val allUnset = cleaned.all { it.lastUsedAt <= 0L }
    return cleaned.mapIndexed { index, p ->
        val used = when {
            p.lastUsedAt > 0L -> p.lastUsedAt
            allUnset -> now - (cleaned.size - index) * 1_000L
            else -> 0L
        }
        // Strip stale transport/nodeId noise once host:port is present; keep needsRepair for iroh-only.
        if (!p.needsRepair && (p.transport.isNotEmpty() || p.nodeId.isNotEmpty())) {
            p.copy(transport = "", nodeId = "", lastUsedAt = used)
        } else {
            p.copy(lastUsedAt = used)
        }
    }
}

enum class BusySend(val label: String, val mode: String) { Queue("排队发送", "queue"), Steer("插话发送", "steer") }

/** Matches Host `ui-theme.preference`. */
enum class Appearance(val label: String) { Light("浅色"), Dark("深色"), System("跟随系统") }

/** Matches Host `ui-chat.transcriptView`. */
enum class TranscriptView(val label: String) {
    Compact("简洁"), Standard("标准"), Detailed("详细"), Verbose("完全展开")
}

/** Client-only on desktop too (`settings.collapse.*`). */
enum class CollapseTiming(val label: String) {
    Completion("回答结束后"), NextInput("下次有新消息时")
}

/** Matches Host `ui-chat.performanceUsage`. */
enum class PerformanceUsage(val label: String) { Compact("简洁"), Detailed("详细") }

enum class AppLanguage(val label: String) { Zh("中文"), En("English") }

/** Wide-screen chrome: auto follows ≥600dp like desktop sidebar; phone/desktop force a mode. */
enum class LayoutMode { Auto, Phone, Desktop }

/** Desktop `ui-workspace` view store: groupBy / orderBy / archivedFilter. */
enum class SessionGroupBy { Workspace, WorkspaceTree, Flat }
enum class SessionOrderBy { Manual, Updated }
enum class ArchivedFilter { Default, Show, Only }

/** Identities are discovery hints only; the Host remains authoritative for process lifetime. */
@Serializable
internal data class SavedTerminalOwner(
    val sessionId: String,
    val ids: List<String> = emptyList(),
    val selectedId: String? = null,
)

class AppPrefs(ctx: Context) {
    internal val hostSettings = HostSettings(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    private val storage = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val version = mutableIntStateOf(0)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        version.intValue++
    }

    init {
        storage.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    /** Observe reads without maintaining a second, potentially stale preference cache. */
    private val p: SharedPreferences
        get() {
            version.intValue
            return storage
        }
    private val json = Json { ignoreUnknownKeys = true }
    var profiles: List<Profile>
        get() = p.getString("profiles", null)?.let { runCatching { json.decodeFromString(ListSerializer(Profile.serializer()), it) }.getOrNull() } ?: emptyList()
        set(v) = p.edit().putString("profiles", json.encodeToString(ListSerializer(Profile.serializer()), v)).apply()
    var activeProfile: String?
        get() = p.getString("active", null)
        set(v) = p.edit().putString("active", v).apply()
    var nearbyWifiDeclined: Boolean
        get() = p.getBoolean("nearbyWifiDeclined", false)
        set(v) = p.edit().putBoolean("nearbyWifiDeclined", v).apply()
    var profilesMigratedV1: Boolean
        get() = p.getBoolean("profilesMigratedV1", false)
        set(v) = p.edit().putBoolean("profilesMigratedV1", v).apply()
    var busySend: BusySend
        get() = if (hostSettings.value(SharedSetting.BusySend).str() == "steer") BusySend.Steer else BusySend.Queue
        set(v) = hostSettings.set(SharedSetting.BusySend, JsonPrimitive(v.mode))
    var defaultPermission: String
        get() = hostSettings.value(SharedSetting.Permission).str() ?: "workspace-write"
        set(v) = hostSettings.set(SharedSetting.Permission, JsonPrimitive(v))
    var approvalNotify: Boolean
        get() = p.getBoolean("approvalNotify", true)
        set(v) = p.edit().putBoolean("approvalNotify", v).apply()
    var language: AppLanguage
        get() = if ((hostSettings.value(SharedSetting.Language).str() ?: java.util.Locale.getDefault().language).startsWith("zh")) AppLanguage.Zh else AppLanguage.En
        set(v) = hostSettings.set(SharedSetting.Language, JsonPrimitive(v.name.lowercase()))
    var appearance: Appearance
        get() = when (hostSettings.value(SharedSetting.Appearance).str()) { "light" -> Appearance.Light; "dark" -> Appearance.Dark; else -> Appearance.System }
        set(v) = hostSettings.set(SharedSetting.Appearance, JsonPrimitive(v.name.lowercase()))
    var fontSize: Int
        get() = hostSettings.value(SharedSetting.FontSize).int() ?: 14
        set(v) = hostSettings.set(SharedSetting.FontSize, JsonPrimitive(v.coerceIn(10, 22)))
    var codeFontSize: Int
        get() = hostSettings.value(SharedSetting.CodeFontSize).int() ?: 11
        set(v) = hostSettings.set(SharedSetting.CodeFontSize, JsonPrimitive(v.coerceIn(10, 16)))
    var terminalFontSize: Int
        get() = hostSettings.value(SharedSetting.TerminalFontSize).int() ?: 13
        set(v) = hostSettings.set(SharedSetting.TerminalFontSize, JsonPrimitive(v.coerceIn(10, 20)))
    var textFontFamily: String
        get() = hostSettings.value(SharedSetting.TextFontFamily).str().orEmpty()
        set(v) = hostSettings.set(SharedSetting.TextFontFamily, JsonPrimitive(normalizeFontFamily(v)))
    var codeFontFamily: String
        get() = hostSettings.value(SharedSetting.CodeFontFamily).str().orEmpty()
        set(v) = hostSettings.set(SharedSetting.CodeFontFamily, JsonPrimitive(normalizeFontFamily(v)))
    var terminalFontFamily: String
        get() = hostSettings.value(SharedSetting.TerminalFontFamily).str().orEmpty()
        set(v) = hostSettings.set(SharedSetting.TerminalFontFamily, JsonPrimitive(normalizeFontFamily(v)))
    var showCodeWorkView: Boolean
        get() = hostSettings.value(SharedSetting.CodeWork).bool() ?: true
        set(v) = hostSettings.set(SharedSetting.CodeWork, JsonPrimitive(v))
    var codeWordWrap: Boolean
        get() = p.getBoolean("codeWordWrap", false)
        set(v) = p.edit().putBoolean("codeWordWrap", v).apply()
    var transcriptView: TranscriptView
        get() = when (hostSettings.value(SharedSetting.Transcript).str()) {
            "compact" -> TranscriptView.Compact
            "detailed", "normal", "expanded" -> TranscriptView.Detailed
            "verbose" -> TranscriptView.Verbose
            else -> TranscriptView.Standard
        }
        set(v) = hostSettings.set(SharedSetting.Transcript, JsonPrimitive(v.name.lowercase()))
    /** Like desktop, collapse timing is scoped to this client instance, not persisted. */
    var collapseTiming by mutableStateOf(CollapseTiming.Completion)
    var performanceUsage: PerformanceUsage
        get() = if (hostSettings.value(SharedSetting.Performance).str() == "detailed") PerformanceUsage.Detailed else PerformanceUsage.Compact
        set(v) = hostSettings.set(SharedSetting.Performance, JsonPrimitive(v.name.lowercase()))
    var layoutMode: LayoutMode
        get() = runCatching { LayoutMode.valueOf(p.getString("layoutMode", null) ?: "Auto") }.getOrDefault(LayoutMode.Auto)
        set(v) = p.edit().putString("layoutMode", v.name).apply()

    /** Persist drawer session list view options (desktop `dsh.workspace.view.v5`). */
    var sessionGroupBy: SessionGroupBy
        get() = runCatching { SessionGroupBy.valueOf(p.getString("sessionGroupBy", null) ?: "Workspace") }.getOrDefault(SessionGroupBy.Workspace)
        set(v) = p.edit().putString("sessionGroupBy", v.name).apply()
    var sessionOrderBy: SessionOrderBy
        get() = runCatching { SessionOrderBy.valueOf(p.getString("sessionOrderBy", null) ?: "Updated") }.getOrDefault(SessionOrderBy.Updated)
        set(v) = p.edit().putString("sessionOrderBy", v.name).apply()
    var archivedFilter: ArchivedFilter
        get() = runCatching { ArchivedFilter.valueOf(p.getString("archivedFilter", null) ?: "Default") }.getOrDefault(ArchivedFilter.Default)
        set(v) = p.edit().putString("archivedFilter", v.name).apply()
    var sidebarCollapsed: Boolean
        get() = p.getBoolean("sidebarCollapsed", false)
        set(v) = p.edit().putBoolean("sidebarCollapsed", v).apply()

    fun collapsedWorkspaces(hostId: String): Set<String> =
        p.getStringSet("collapsedWorkspaces:$hostId", emptySet()).orEmpty().toSet()

    fun setCollapsedWorkspaces(hostId: String, value: Set<String>) {
        p.edit().putStringSet("collapsedWorkspaces:$hostId", value.toSet()).apply()
    }

    fun manualSessionOrder(hostId: String): List<String> =
        p.getString("manualSessionOrder:$hostId", null)?.let {
            runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
        }.orEmpty()

    fun setManualSessionOrder(hostId: String, value: List<String>) {
        p.edit().putString("manualSessionOrder:$hostId", json.encodeToString(ListSerializer(String.serializer()), value.distinct())).apply()
    }

    private fun terminalOwners(hostId: String): List<SavedTerminalOwner> =
        p.getString("terminalOwners:$hostId", null)?.let {
            runCatching { json.decodeFromString(ListSerializer(SavedTerminalOwner.serializer()), it) }.getOrNull()
        }.orEmpty()

    private fun saveTerminalOwners(hostId: String, owners: List<SavedTerminalOwner>) {
        p.edit().putString("terminalOwners:$hostId",
            json.encodeToString(ListSerializer(SavedTerminalOwner.serializer()), owners)).apply()
    }

    @Synchronized
    fun terminalOwnerIds(hostId: String): List<String> = terminalOwners(hostId).map { it.sessionId }

    @Synchronized
    fun selectedTerminalId(hostId: String, sessionId: String): String? =
        terminalOwners(hostId).firstOrNull { it.sessionId == sessionId }?.selectedId

    /** Discovery hint for a create request; only the Host roster can confirm process existence. */
    @Synchronized
    fun discoverTerminal(hostId: String, sessionId: String, terminalId: String) =
        recordTerminal(hostId, sessionId, terminalId, selected = false)

    /** Only a received snapshot proves the follow actually attached successfully. */
    @Synchronized
    fun rememberTerminal(hostId: String, sessionId: String, terminalId: String) =
        recordTerminal(hostId, sessionId, terminalId, selected = true)

    private fun recordTerminal(hostId: String, sessionId: String, terminalId: String, selected: Boolean) {
        val owners = terminalOwners(hostId)
        val owner = owners.firstOrNull { it.sessionId == sessionId } ?: SavedTerminalOwner(sessionId)
        saveTerminalOwners(hostId, owners.filterNot { it.sessionId == sessionId } +
            owner.copy(ids = (owner.ids + terminalId).distinct(), selectedId = if (selected) terminalId else owner.selectedId))
    }

    /** Call only after a successful, validated authoritative list, never on transport failure. */
    @Synchronized
    fun reconcileTerminals(hostId: String, sessionId: String, ids: List<String>) {
        val owners = terminalOwners(hostId)
        val selected = owners.firstOrNull { it.sessionId == sessionId }?.selectedId?.takeIf { it in ids }
        saveTerminalOwners(hostId, owners.filterNot { it.sessionId == sessionId } +
            if (ids.isEmpty()) emptyList() else listOf(SavedTerminalOwner(sessionId, ids.distinct(), selected)))
        if (ids.isEmpty()) {
            val prefix = terminalPanelOwnerPrefix(hostId)
            val editor = p.edit()
            p.all.filter { (key, value) -> key.startsWith(prefix) && value == sessionId }
                .keys.forEach(editor::remove)
            editor.apply()
        }
    }

    @Synchronized
    fun forgetTerminal(hostId: String, sessionId: String, terminalId: String) {
        val owner = terminalOwners(hostId).firstOrNull { it.sessionId == sessionId } ?: return
        reconcileTerminals(hostId, sessionId, owner.ids.filterNot { it == terminalId })
    }

    private fun terminalPanelOwnerPrefix(hostId: String) = "terminalPanelOwner:${hostId.length}:$hostId:"

    fun terminalPanelOwner(hostId: String, contextSessionId: String): String? =
        p.getString(terminalPanelOwnerPrefix(hostId) + contextSessionId, null)

    fun setTerminalPanelOwner(hostId: String, contextSessionId: String, ownerSessionId: String?) {
        p.edit().putString(terminalPanelOwnerPrefix(hostId) + contextSessionId, ownerSessionId).apply()
    }
}
