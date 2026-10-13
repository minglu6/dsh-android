package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.jsonOf
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Native Compose ports of desktop Settings pages that the phone previously hid
 * (models / plugins / schedules / credentials). Nav is list → full-screen detail;
 * each detail row places its label above its control. Host calls go through pocket
 * RPCs in [plugin/src/settings-ops.js].
 *
 * Mobile list→detail chrome follows the dsh-mobile main-panel pattern (Apache-2.0;
 * see docs/THIRD_PARTY_NOTICES.md) without WebView.
 */

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onBack) {
                Icon(D.chevronLeft, "返回", tint = cs.onSurface)
            }
            Text(title, fontSize = 20.sp, color = cs.onSurface)
        }
        content()
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(T.R16)
            .border(1.dp, cs.outlineVariant, T.R16),
        content = content,
    )
}

@Composable
private fun SettingsNavRow(
    title: String,
    icon: ImageVector? = null,
    value: String? = null,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(title, fontSize = 16.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
        value?.let {
            Text(it, fontSize = 15.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp))
        }
        Icon(D.chevronRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
    if (divider) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
}

/** Label above control — phone adaptation of desktop settings field layout. */
@Composable
fun LabeledField(label: String, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
fun SettingsTextField(value: String, onChange: (String) -> Unit, hint: String = "", mono: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(T.R12)
            .background(cs.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        if (value.isEmpty()) Text(hint, color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 15.sp)
        BasicTextField(
            value,
            onChange,
            singleLine = true,
            textStyle = (if (mono) T.Mono else MaterialTheme.typography.bodyMedium).copy(
                fontSize = 15.sp,
                color = cs.onSurface,
            ),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ErrorOrEmpty(message: String, onRetry: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = cs.onSurfaceVariant, fontSize = 14.sp)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onRetry) { Text(L.retry()) }
        }
    }
}

// ---------- Models ----------

@Composable
fun ModelsSettingsScreen(onBack: () -> Unit) {
    val ops by Pocket.ops.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var detail by remember { mutableStateOf<JsonElement?>(null) }
    var keyDraft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        if ("models.providers" !in ops) {
            loading = false
            error = L.unavailable()
            return
        }
        loading = true
        error = null
        scope.launch {
            runCatching { Pocket.clientOrNull()?.modelsProviders() }
                .onSuccess { r ->
                    rows = r["rows"].arr().orEmpty()
                    loading = false
                }
                .onFailure {
                    error = it.message ?: L.unavailable()
                    loading = false
                }
        }
    }
    LaunchedEffect(ops) { reload() }

    if (detail != null) {
        val row = detail!!
        val provider = row["provider"].str().orEmpty()
        val cred = row["credential"]
        val configured = cred["configured"].bool() == true
        SettingsScaffold(L.providerDetail(), onBack = { detail = null; keyDraft = "" }) {
            SettingsCard {
                LabeledField(L.modelsNav()) {
                    Text(row["displayName"].str() ?: provider, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                LabeledField(L.apiKey()) {
                    Text(
                        if (configured) L.credentialConfigured() else L.credentialMissing(),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingsTextField(keyDraft, { keyDraft = it }, hint = if (configured) L.maskedSecret() else L.apiKeyPlaceholder(), mono = true)
                    Text(L.keyBlankKeep(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            val ref = row["apiKeyEnv"].str()
            if (ref != null && "credentials.set" in ops) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (keyDraft.isBlank()) return@Button
                            busy = true
                            scope.launch {
                                runCatching { Pocket.clientOrNull()?.credentialsSet(ref, keyDraft) }
                                keyDraft = ""
                                busy = false
                                reload()
                                detail = rows.find { it["provider"].str() == provider }
                            }
                        },
                        enabled = !busy && keyDraft.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text(L.save()) }
                    if (configured && "credentials.unset" in ops) {
                        OutlinedButton(
                            onClick = {
                                busy = true
                                scope.launch {
                                    runCatching { Pocket.clientOrNull()?.credentialsUnset(ref) }
                                    busy = false
                                    reload()
                                    detail = rows.find { it["provider"].str() == provider }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(L.delete()) }
                    }
                }
            }
            val models = row["profile"]["models"].arr().orEmpty()
            if (models.isNotEmpty()) {
                Text(L.modelsCatalog(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 4.dp))
                SettingsCard {
                    models.forEachIndexed { i, m ->
                        LabeledField(m["id"].str() ?: m["name"].str() ?: "model") {
                            Text(m["name"].str() ?: m["id"].str().orEmpty(), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        if (i < models.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
        return
    }

    SettingsScaffold(L.modelsTitle(), onBack) {
        Text(L.modelsIntro(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
        when {
            loading -> ErrorOrEmpty(L.loading())
            error != null -> ErrorOrEmpty(error!!, onRetry = ::reload)
            rows.isEmpty() -> ErrorOrEmpty(L.unavailable(), onRetry = ::reload)
            else -> SettingsCard {
                rows.forEachIndexed { i, row ->
                    val name = row["displayName"].str() ?: row["provider"].str().orEmpty()
                    val configured = row["credential"]["configured"].bool() == true
                    SettingsNavRow(
                        title = name,
                        value = if (configured) L.credentialConfigured() else L.credentialMissing(),
                        divider = i < rows.lastIndex,
                        onClick = { detail = row },
                    )
                }
            }
        }
    }
}

// ---------- Plugins ----------

@Composable
fun PluginsSettingsScreen(onBack: () -> Unit) {
    val ops by Pocket.ops.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var bundles by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var plugins by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var detail by remember { mutableStateOf<JsonElement?>(null) }
    var detailIsBundle by remember { mutableStateOf(true) }

    fun reload() {
        if ("plugins.list" !in ops) {
            loading = false
            error = L.unavailable()
            return
        }
        loading = true
        error = null
        scope.launch {
            runCatching { Pocket.clientOrNull()?.pluginsList() }
                .onSuccess { r ->
                    bundles = r["bundles"].arr().orEmpty()
                    plugins = r["plugins"].arr().orEmpty()
                    loading = false
                }
                .onFailure {
                    error = it.message ?: L.unavailable()
                    loading = false
                }
        }
    }
    LaunchedEffect(ops) { reload() }

    if (detail != null) {
        val row = detail!!
        val title = row["meta"]["title"]["zh"].str()
            ?: row["meta"]["title"]["en"].str()
            ?: row["name"].str()
            ?: row["moduleName"].str()
            ?: row["entryId"].str().orEmpty()
        val enabled = row["enabled"].bool() == true
        val readOnly = row["readOnlyReason"].str() != null
        SettingsScaffold(title, onBack = { detail = null }) {
            SettingsCard {
                LabeledField(L.pluginsTitle()) {
                    Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    row["description"].str()?.let {
                        Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    row["version"].str()?.let {
                        Text("v$it", style = T.Mono.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), modifier = Modifier.padding(top = 4.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                LabeledField(if (enabled) L.enabled() else L.disabled()) {
                    Switch(
                        enabled,
                        onCheckedChange = { next ->
                            if (readOnly || "plugins.setEnabled" !in ops) return@Switch
                            scope.launch {
                                runCatching {
                                    if (detailIsBundle) {
                                        Pocket.clientOrNull()?.pluginsSetEnabled(next, bundle = row["name"].str())
                                    } else {
                                        Pocket.clientOrNull()?.pluginsSetEnabled(next, id = row["entryId"].str())
                                    }
                                }
                                reload()
                                detail = null
                            }
                        },
                        enabled = !readOnly && "plugins.setEnabled" in ops,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                            checkedThumbColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            }
            val parts = row["rows"].arr().orEmpty()
            if (parts.isNotEmpty()) {
                Text(L.builtinPluginsNav(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp, top = 12.dp))
                SettingsCard {
                    parts.forEachIndexed { i, p ->
                        LabeledField(p["moduleName"].str() ?: p["rowId"].str().orEmpty()) {
                            Text(p["rowId"].str().orEmpty(), style = T.Mono.copy(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant))
                        }
                        if (i < parts.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
        return
    }

    SettingsScaffold(L.pluginsTitle(), onBack) {
        Text(L.pluginsIntro(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
        when {
            loading -> ErrorOrEmpty(L.loading())
            error != null -> ErrorOrEmpty(error!!, onRetry = ::reload)
            bundles.isEmpty() && plugins.isEmpty() -> ErrorOrEmpty(L.emptyPlugins(), onRetry = ::reload)
            else -> {
                if (bundles.isNotEmpty()) {
                    Text("${bundles.size} ${L.countUnit()}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp, top = 8.dp))
                    SettingsCard {
                        bundles.forEachIndexed { i, b ->
                            val name = b["meta"]["title"]["zh"].str()
                                ?: b["meta"]["title"]["en"].str()
                                ?: b["name"].str().orEmpty()
                            SettingsNavRow(
                                title = name,
                                value = if (b["enabled"].bool() == true) L.enabled() else L.disabled(),
                                divider = i < bundles.lastIndex,
                                onClick = { detailIsBundle = true; detail = b },
                            )
                        }
                    }
                }
                if (plugins.isNotEmpty()) {
                    Text(L.builtinPluginsNav(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp, top = 12.dp))
                    SettingsCard {
                        plugins.forEachIndexed { i, p ->
                            SettingsNavRow(
                                title = p["moduleName"].str() ?: p["entryId"].str().orEmpty(),
                                value = if (p["enabled"].bool() == true) L.enabled() else L.disabled(),
                                divider = i < plugins.lastIndex,
                                onClick = { detailIsBundle = false; detail = p },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- Scheduled tasks ----------

@Composable
fun ScheduleSettingsScreen(onBack: () -> Unit) {
    val ops by Pocket.ops.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var detail by remember { mutableStateOf<JsonElement?>(null) }
    var history by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var afterSec by remember { mutableStateOf("3600") }
    var filter by remember { mutableStateOf("all") } // all | active | inactive

    fun reload() {
        if ("schedule.catalog" !in ops) {
            loading = false
            error = L.unavailable()
            return
        }
        loading = true
        error = null
        scope.launch {
            runCatching { Pocket.clientOrNull()?.scheduleCatalog() }
                .onSuccess { r ->
                    records = r.arr().orEmpty()
                    loading = false
                }
                .onFailure {
                    error = it.message ?: L.unavailable()
                    loading = false
                }
        }
    }
    LaunchedEffect(ops) { reload() }

    if (creating) {
        SettingsScaffold(L.createSchedule(), onBack = { creating = false }) {
            SettingsCard {
                LabeledField(L.scheduleTitleField()) { SettingsTextField(title, { title = it }) }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                LabeledField(L.schedulePromptField()) { SettingsTextField(prompt, { prompt = it }) }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                LabeledField(L.scheduleAfterSeconds()) { SettingsTextField(afterSec, { afterSec = it }, mono = true) }
            }
            val sessionId = Pocket.chat.value?.sessionId ?: Pocket.sessions.value.firstOrNull()?.sessionId
            Button(
                onClick = {
                    if (sessionId == null || title.isBlank() || prompt.isBlank()) return@Button
                    val sec = afterSec.toIntOrNull() ?: return@Button
                    scope.launch {
                        runCatching {
                            Pocket.clientOrNull()?.scheduleCreate(
                                jsonOf(
                                    "sessionId" to sessionId,
                                    "title" to title,
                                    "prompt" to prompt,
                                    "after_seconds" to sec,
                                ),
                            )
                        }
                        creating = false
                        title = ""; prompt = ""; afterSec = "3600"
                        reload()
                    }
                },
                enabled = sessionId != null && title.isNotBlank() && prompt.isNotBlank() && "schedule.create" in ops,
                modifier = Modifier.fillMaxWidth().padding(18.dp),
            ) { Text(L.save()) }
        }
        return
    }

    if (detail != null) {
        val row = detail!!
        val id = row["id"].str().orEmpty()
        val sessionId = row["sessionId"].str().orEmpty()
        val status = row["status"].str() ?: "active"
        var confirmDelete by remember(id) { mutableStateOf(false) }
        SettingsScaffold(row["title"].str() ?: id, onBack = { detail = null; history = emptyList() }) {
            SettingsCard {
                LabeledField(L.scheduleTitleField()) {
                    Text(row["title"].str().orEmpty(), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                LabeledField(L.schedulePromptField()) {
                    Text(row["prompt"].str().orEmpty(), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                LabeledField(L.nextRun()) {
                    Text(row["scheduledAt"].str().orEmpty(), style = T.Mono.copy(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant))
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                LabeledField(if (status == "active") L.statusActive() else L.statusInactive()) {
                    Text(status, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if ("schedule.history" in ops) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    Pocket.clientOrNull()?.scheduleHistory(sessionId, id)
                                }.onSuccess { h ->
                                    history = h["records"].arr().orEmpty()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(L.deliveryHistory()) }
                }
                if ("schedule.delete" in ops) {
                    if (confirmDelete) {
                        PocketSheet({ confirmDelete = false }) {
                            SheetTitle(L.confirmDelete())
                            Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton({ confirmDelete = false }, Modifier.weight(1f)) { Text(L.cancel()) }
                                Button(
                                    {
                                        confirmDelete = false
                                        scope.launch {
                                            runCatching { Pocket.clientOrNull()?.scheduleDelete(sessionId, id) }
                                            detail = null
                                            reload()
                                        }
                                    },
                                    Modifier.weight(1f),
                                ) { Text(L.delete()) }
                            }
                        }
                    }
                    Button({ confirmDelete = true }, Modifier.weight(1f)) { Text(L.delete()) }
                }
            }
            if (history.isNotEmpty()) {
                Text(L.deliveryHistory(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp))
                SettingsCard {
                    history.forEachIndexed { i, h ->
                        LabeledField(h["deliveredAt"].str() ?: h["scheduledAt"].str().orEmpty()) {
                            Text(h["prompt"].str() ?: "—", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (i < history.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
        return
    }

    val filtered = records.filter {
        when (filter) {
            "active" -> it["status"].str() == "active"
            "inactive" -> it["status"].str() == "inactive"
            else -> true
        }
    }

    SettingsScaffold(L.scheduleTitle(), onBack) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to L.scheduleListAria(), "active" to L.statusActive(), "inactive" to L.statusInactive()).forEach { (id, label) ->
                FilterChip(
                    selected = filter == id,
                    onClick = { filter = id },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
            Spacer(Modifier.weight(1f))
            if ("schedule.create" in ops) {
                TextButton({ creating = true }) { Text(L.add()) }
            }
        }
        when {
            loading -> ErrorOrEmpty(L.loading())
            error != null -> ErrorOrEmpty(error!!, onRetry = ::reload)
            filtered.isEmpty() -> ErrorOrEmpty(L.emptySchedules(), onRetry = ::reload)
            else -> SettingsCard {
                filtered.forEachIndexed { i, row ->
                    SettingsNavRow(
                        title = row["title"].str() ?: row["id"].str().orEmpty(),
                        value = if (row["status"].str() == "active") L.statusActive() else L.statusInactive(),
                        divider = i < filtered.lastIndex,
                        onClick = { detail = row },
                    )
                }
            }
        }
    }
}

// ---------- Credentials ----------

@Composable
fun CredentialsSettingsScreen(onBack: () -> Unit) {
    val ops by Pocket.ops.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var detail by remember { mutableStateOf<JsonElement?>(null) }
    var adding by remember { mutableStateOf(false) }
    var refDraft by remember { mutableStateOf("") }
    var valueDraft by remember { mutableStateOf("") }

    fun reload() {
        if ("credentials.list" !in ops) {
            loading = false
            error = L.unavailable()
            return
        }
        loading = true
        error = null
        scope.launch {
            runCatching { Pocket.clientOrNull()?.credentialsList() }
                .onSuccess { r ->
                    items = r["items"].arr().orEmpty()
                    loading = false
                }
                .onFailure {
                    error = it.message ?: L.unavailable()
                    loading = false
                }
        }
    }
    LaunchedEffect(ops) { reload() }

    if (adding) {
        SettingsScaffold(L.addCredential(), onBack = { adding = false; refDraft = ""; valueDraft = "" }) {
            SettingsCard {
                LabeledField(L.refName()) { SettingsTextField(refDraft, { refDraft = it }, mono = true) }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                LabeledField(L.apiKey()) { SettingsTextField(valueDraft, { valueDraft = it }, hint = L.apiKeyPlaceholder(), mono = true) }
            }
            Button(
                onClick = {
                    if (refDraft.isBlank() || valueDraft.isBlank()) return@Button
                    scope.launch {
                        runCatching { Pocket.clientOrNull()?.credentialsSet(refDraft, valueDraft) }
                        adding = false
                        refDraft = ""; valueDraft = ""
                        reload()
                    }
                },
                enabled = refDraft.isNotBlank() && valueDraft.isNotBlank() && "credentials.set" in ops,
                modifier = Modifier.fillMaxWidth().padding(18.dp),
            ) { Text(L.save()) }
        }
        return
    }

    if (detail != null) {
        val row = detail!!
        val ref = row["ref"].str().orEmpty()
        val configured = row["configured"].bool() == true
        SettingsScaffold(ref, onBack = { detail = null; valueDraft = "" }) {
            SettingsCard {
                LabeledField(L.refName()) {
                    Text(ref, style = T.Mono.copy(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface))
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                LabeledField(L.apiKey()) {
                    Text(
                        if (configured) "${L.credentialConfigured()} · ${L.maskedSecret()}" else L.credentialMissing(),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingsTextField(valueDraft, { valueDraft = it }, hint = L.apiKeyPlaceholder(), mono = true)
                }
            }
            Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (valueDraft.isBlank()) return@Button
                        scope.launch {
                            runCatching { Pocket.clientOrNull()?.credentialsSet(ref, valueDraft) }
                            valueDraft = ""
                            reload()
                            detail = items.find { it["ref"].str() == ref }
                        }
                    },
                    enabled = valueDraft.isNotBlank() && "credentials.set" in ops,
                    modifier = Modifier.weight(1f),
                ) { Text(L.save()) }
                if (configured && "credentials.unset" in ops) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching { Pocket.clientOrNull()?.credentialsUnset(ref) }
                                detail = null
                                reload()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(L.delete()) }
                }
            }
        }
        return
    }

    SettingsScaffold(L.credentialsTitle(), onBack) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L.credentialsNav(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if ("credentials.set" in ops) TextButton({ adding = true }) { Text(L.add()) }
        }
        when {
            loading -> ErrorOrEmpty(L.loading())
            error != null -> ErrorOrEmpty(error!!, onRetry = ::reload)
            items.isEmpty() -> ErrorOrEmpty(L.emptyCredentials(), onRetry = ::reload)
            else -> SettingsCard {
                items.forEachIndexed { i, row ->
                    SettingsNavRow(
                        title = row["ref"].str().orEmpty(),
                        value = if (row["configured"].bool() == true) L.maskedSecret() else L.credentialMissing(),
                        divider = i < items.lastIndex,
                        onClick = { detail = row },
                    )
                }
            }
        }
    }
}
