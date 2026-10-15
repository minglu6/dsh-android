package dev.dsh.pocket.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Exact fields owned by the official Host settings namespaces. Appearance stays on the device. */
internal enum class SharedSetting(val namespace: String, val field: String) {
    FontSize("ui-theme", "fontSize"),
    CodeFontSize("ui-theme", "codeFontSize"), TerminalFontSize("ui-theme", "terminalFontSize"),
    TextFontFamily("ui-theme", "textFontFamily"), CodeFontFamily("ui-theme", "codeFontFamily"),
    TerminalFontFamily("ui-theme", "terminalFontFamily"), Language("locale", "preference"),
    Transcript("ui-chat", "transcriptView"), Performance("ui-chat", "performanceUsage"),
    BusySend("ui-conversation", "busyEnter"), CodeWork("ui-settings", "enabled"),
    Permission("permission", "defaultPreset"),
    SessionLog("session-log-deepseek", "enabled"),
}

/** A live mirror, never a persisted mobile copy. A connection identity fences every response and write. */
internal class HostSettings(private val scope: CoroutineScope) {
    private data class Connection(
        val describe: suspend () -> JsonElement,
        val mutate: suspend (String, JsonArray, Int) -> JsonElement,
    )
    private var connection: Connection? = null
    private var readGeneration = 0L
    var snapshot: JsonElement? by mutableStateOf(null)
        private set
    private var pendingValue: Pair<SharedSetting, JsonElement>? = null
    var online by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set
    val ready get() = online && snapshot != null

    fun bind(describe: suspend () -> JsonElement, mutate: suspend (String, JsonArray, Int) -> JsonElement) {
        clear()
        connection = Connection(describe, mutate)
        online = true
        refresh()
    }

    fun clear() {
        connection = null
        readGeneration++
        snapshot = null
        online = false
        loading = false
        saving = false
        error = null
        pendingValue = null
    }

    private fun adopt(next: JsonElement) {
        val previous = snapshot["namespaces"].arr().orEmpty().associateBy { it["ns"].str() }
        val namespaces = next["namespaces"].arr().orEmpty().map { row ->
            val old = previous[row["ns"].str()]
            if ((old["revision"].long() ?: -1) > (row["revision"].long() ?: -1)) old!! else row
        }
        snapshot = JsonObject(next.jsonObject + ("namespaces" to JsonArray(namespaces)))
    }

    fun dismissError() { error = null }
    fun namespace(setting: SharedSetting): JsonElement? = snapshot["namespaces"].arr()?.firstOrNull { it["ns"].str() == setting.namespace }
    fun value(setting: SharedSetting): JsonElement? = namespace(setting)["value"][setting.field]
    fun writable(setting: SharedSetting): Boolean = ready && !saving && snapshot["writable"].bool() == true &&
        namespace(setting)["writable"].bool() != false && (namespace(setting)["revision"].int() ?: -1) >= 0

    fun refresh() {
        val c = connection ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) { read(c) }
    }

    private suspend fun read(c: Connection) {
        if (connection !== c) return
        val generation = ++readGeneration
        loading = true
        try {
            val next = c.describe()
            if (connection === c && generation == readGeneration) adopt(next)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (connection === c && generation == readGeneration) error = e.message ?: "无法读取远程主机设置"
        } finally {
            if (connection === c && generation == readGeneration) loading = false
        }
    }

    /** Capture both the observed revision and connection now, not when the coroutine later runs. */
    fun set(setting: SharedSetting, value: JsonElement, onSaved: () -> Unit = {}) {
        if (saving && pendingValue == (setting to value)) return
        val c = connection
        val revision = namespace(setting)["revision"].int()
        if (c == null || !writable(setting) || revision == null) {
            error = if (saving) "正在保存，请稍后再试" else "远程主机设置尚未连接、不可用或只读，未保存"
            return
        }
        saving = true
        error = null
        pendingValue = setting to value
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                check(connection === c) { "远程主机已切换，未保存" }
                val ops = buildJsonArray { add(buildJsonObject {
                    put("op", "set"); put("path", JsonArray(listOf(JsonPrimitive(setting.field)))); put("value", value)
                }) }
                val result = c.mutate(setting.namespace, ops, revision)
                requireSettingsSuccess(result)
                if (connection !== c) return@launch
                // Read the accepted effective value, including any provider override, not the draft.
                ++readGeneration
                val accepted = c.describe()
                if (connection !== c) return@launch
                adopt(accepted)
                loading = false
                check(this@HostSettings.value(setting) == value) { "远程主机未应用此值（可能被配置覆盖），请检查主机设置" }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (connection === c) {
                    // Conflicts are never retried or queued; refresh the next explicit edit's revision.
                    read(c)
                    if (connection === c) error = e.message ?: "远程主机设置保存失败"
                }
            } finally {
                if (connection === c) {
                    saving = false
                    pendingValue = null
                }
            }
        }
    }
}
