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
 * Shared native settings chrome and model/credential pages. Plugin management
 * and automation live in their own screens, following the official Host/UI
 * contracts; the pocket bridge remains the only remote transport.
 */

@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(T.Bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onBack) {
                Icon(D.chevronLeft, "返回", tint = T.Ink)
            }
            Text(title, fontSize = 20.sp, color = T.Ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
        content()
    }
}

/** Desktop settings card fill/stroke (`--dsw-alias-settings-card-*`) + radius-lg. */
@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(T.R16)
            .background(T.SettingsCard)
            .border(1.dp, T.SettingsCardStroke, T.R16),
        content = content,
    )
}

@Composable
internal fun SettingsNavRow(
    title: String,
    icon: ImageVector? = null,
    value: String? = null,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, tint = T.Ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(title, fontSize = 16.sp, color = T.Ink, modifier = Modifier.weight(1f))
        value?.let {
            Text(it, fontSize = 15.sp, color = T.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp))
        }
        Icon(D.chevronRight, null, tint = T.Text3, modifier = Modifier.size(20.dp))
    }
    if (divider) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = T.Border)
}

/** Label above control — phone adaptation of desktop settings field layout. */
@Composable
fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
fun SettingsTextField(value: String, onChange: (String) -> Unit, hint: String = "", mono: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(T.R12)
            .background(T.Input)
            .border(0.5.dp, T.Border, T.R12)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        if (value.isEmpty()) Text(hint, color = T.Caption, fontSize = 15.sp)
        BasicTextField(
            value,
            onChange,
            singleLine = true,
            textStyle = (if (mono) T.Mono else MaterialTheme.typography.bodyMedium).copy(
                fontSize = 15.sp,
                color = T.Ink,
            ),
            cursorBrush = SolidColor(T.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ErrorOrEmpty(message: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = T.Text3, fontSize = 14.sp)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onRetry) { Text(L.retry(), color = T.Accent) }
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
                    Text(row["displayName"].str() ?: provider, fontSize = 16.sp, color = T.Ink)
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = T.Border)
                LabeledField(L.apiKey()) {
                    Text(
                        if (configured) L.credentialConfigured() else L.credentialMissing(),
                        fontSize = 14.sp,
                        color = T.Text3,
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingsTextField(keyDraft, { keyDraft = it }, hint = if (configured) L.maskedSecret() else L.apiKeyPlaceholder(), mono = true)
                    Text(L.keyBlankKeep(), fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(top = 6.dp))
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
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = T.R12,
                        colors = PrimaryColors(),
                    ) { Text(L.save(), color = T.OnPrimary) }
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
                Text(L.modelsCatalog(), fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 4.dp))
                SettingsCard {
                    models.forEachIndexed { i, m ->
                        LabeledField(m["id"].str() ?: m["name"].str() ?: "model") {
                            Text(m["name"].str() ?: m["id"].str().orEmpty(), fontSize = 15.sp, color = T.Ink)
                        }
                        if (i < models.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = T.Border)
                        }
                    }
                }
            }
        }
        return
    }

    SettingsScaffold(L.modelsTitle(), onBack) {
        Text(L.modelsIntro(), fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
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
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = T.Border)
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
                    Text(ref, style = T.Mono.copy(fontSize = 14.sp, color = T.Ink))
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = T.Border)
                LabeledField(L.apiKey()) {
                    Text(
                        if (configured) "${L.credentialConfigured()} · ${L.maskedSecret()}" else L.credentialMissing(),
                        fontSize = 14.sp,
                        color = T.Text3,
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
            Text(L.credentialsNav(), fontSize = 13.sp, color = T.Text3, modifier = Modifier.weight(1f))
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
