package dev.dsh.pocket.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Copy from dsh-provider-qoder's Models footer, not transport diagnostics. */
internal enum class QoderText(val zh: String, val en: String) {
    Title("Qoder CN", "Qoder CN"),
    Intro("使用个人访问令牌连接。VPC 实例留空时使用公网服务", "Connect with a personal access token. Leave the VPC instance empty to use the public service."),
    Configured("PAT 已配置", "PAT configured"),
    Missing("PAT 未配置", "PAT not configured"),
    Unknown("PAT 状态不可用", "PAT status unavailable"),
    Edit("编辑连接", "Edit connection"),
    Cancel("取消", "Cancel"),
    Close("关闭", "Close"),
    Save("保存", "Save"),
    Saving("正在保存…", "Saving…"),
    Pat("个人访问令牌（PAT）", "Personal access token (PAT)"),
    PatHint("留空保留现有令牌，已保存的令牌不会回显", "Leave empty to keep the existing token. Stored tokens are never displayed."),
    Vpc("VPC 实例（可选）", "VPC instance (optional)"),
    VpcHint("填写实例 ID，而不是工作区网址；清空后使用公网服务", "Enter the instance ID, not a workspace URL. Clear it to use the public service."),
    Advanced("高级端点覆盖", "Advanced endpoint overrides"),
    Gateway("Gateway URL（可选）", "Gateway URL (optional)"),
    OpenApi("OpenAPI URL（可选）", "OpenAPI URL (optional)"),
    EndpointHint("留空时根据 VPC 实例或公网服务自动生成端点", "Leave empty to derive the endpoints from the VPC instance or public service."),
    ReadOnly("当前会话的连接设置为只读，请在本机 Host 中编辑", "Connection settings are read-only in this session. Open the local Host to edit them."),
    CredentialReadOnly("此令牌由启动环境等只读凭据来源管理，请到对应来源中修改", "This token is managed by a read-only credential source, such as the launch environment. Update it there instead."),
    LoadFailed("无法读取令牌状态，请重试后再保存新令牌", "Could not read the token status. Retry before saving a new token."),
    CredentialFailed("令牌保存失败，输入已保留；请重试或检查凭据来源", "The token could not be saved. Your input is kept; retry or check the credential source."),
    SettingsFailed("连接保存失败，输入已保留；请检查填写内容后重试", "The connection could not be saved. Your input is kept; check the values and retry."),
    Conflict("这些设置已在其他地方修改。请取消并重新打开编辑器以加载最新值，当前草稿尚未丢弃", "These settings changed elsewhere. Cancel and reopen this editor to load the latest values; your draft has not been discarded."),
    Partial("连接设置已保存，但令牌未保存；关闭编辑器不会撤销已保存的设置", "Connection settings were saved, but the token was not. Closing will not undo the saved settings."),
    Retry("重试", "Retry"),
    Public("公网服务", "Public service");

    fun text() = st(zh, en)
}

@Composable
internal fun QoderConnectionCard(
    namespace: JsonElement,
    online: Boolean,
    writable: Boolean,
    interactionEnabled: Boolean = true,
    onBusy: (Boolean) -> Unit,
    onChanged: () -> Unit,
) {
    val ops by Pocket.ops.collectAsState()
    val settingsRevision by Pocket.modelSettingsRevision.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<QoderConnectionDraft?>(null) }
    var failure by remember { mutableStateOf<QoderText?>(null) }
    var partial by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val revision = (namespace["revision"] as? JsonPrimitive)?.longOrNull
    val effective = qoderConnectionDraft(namespace["value"], revision ?: 0)
    val ref = draft?.ref ?: effective.ref
    var credential by remember(ref) { mutableStateOf<JsonElement?>(null) }
    var credentialFailed by remember(ref) { mutableStateOf(false) }
    val editable = online && writable && namespace["writable"].bool() != false && revision != null && "models.settingsMutate" in ops
    LaunchedEffect(ref, online, ops, refresh, settingsRevision, revision) {
        credential = null
        credentialFailed = false
        if (!online) return@LaunchedEffect
        if ("credentials.describe" !in ops) {
            credentialFailed = true
            return@LaunchedEffect
        }
        try {
            val info = checkNotNull(Pocket.clientOrNull()).credentialsDescribe(ref)
            credential = info.takeIf { it["configured"].bool() != null && it["writable"].bool() != null }
            credentialFailed = credential == null
        } catch (t: Exception) {
            if (t is CancellationException) throw t
            credentialFailed = true
        }
    }
    fun close() {
        draft = null; failure = null; partial = false; advanced = false
        onChanged()
    }
    fun save() {
        val pending = draft ?: return
        if (!editable || busy || !interactionEnabled || failure == QoderText.Conflict) return
        val client = Pocket.clientOrNull() ?: return
        busy = true; onBusy(true); failure = null
        scope.launch {
            try {
                val result = saveQoderConnection(pending, editable,
                    describe = { client.credentialsDescribe(it) },
                    mutate = { changes, expected -> client.modelsSettingsMutate("llm-qoder", changes, expected) },
                    store = { credentialRef, pat -> client.credentialsSet(credentialRef, pat) })
                partial = partial || (result.settingsSaved && result.error != null)
                failure = result.error
                draft = if (result.error == null) null else result.draft
                if (result.error == null) { partial = false; advanced = false }
                refresh++
                if (result.settingsSaved || result.error == null || result.error == QoderText.Conflict) onChanged()
            } finally {
                busy = false; onBusy(false)
            }
        }
    }
    val fieldsEnabled = editable && interactionEnabled && !busy
    val tokenWritable = credential["writable"].bool() == true && "credentials.set" in ops
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(QoderText.Title.text(), color = T.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                QoderHint(when (credential["configured"].bool()) { true -> QoderText.Configured; false -> QoderText.Missing; null -> QoderText.Unknown })
            }
            if (draft == null) ModelFormButton(QoderText.Edit.text(), {
                draft = qoderConnectionDraft(namespace["value"], checkNotNull(revision))
                failure = null; partial = false; advanced = false
            }, enabled = fieldsEnabled, compact = true)
        }
        QoderHint(QoderText.Intro)
        if (!editable && online) QoderHint(QoderText.ReadOnly)
        if (credentialFailed) {
            Text(QoderText.LoadFailed.text(), color = T.Err, fontSize = 12.sp)
            ModelFormButton(QoderText.Retry.text(), { refresh++ }, enabled = online && !busy)
        }
        if (credential["writable"].bool() == false) QoderHint(QoderText.CredentialReadOnly)
        val current = draft
        if (current == null) {
            Text(effective.fields.vpcInstance.ifEmpty { QoderText.Public.text() }, color = T.Text2, fontSize = 12.sp)
        } else {
            ModelFormField(QoderText.Pat.text()) {
                ModelFormInput(current.pat, { draft = current.copy(pat = it) }, QoderText.Pat.text(), secret = true, enabled = fieldsEnabled && tokenWritable)
                QoderHint(QoderText.PatHint)
            }
            ModelFormField(QoderText.Vpc.text()) {
                ModelFormInput(current.fields.vpcInstance, { draft = current.copy(fields = current.fields.copy(vpcInstance = it)) }, QoderText.Vpc.text(), enabled = fieldsEnabled)
                QoderHint(QoderText.VpcHint)
            }
            Row(Modifier.clickable(enabled = !busy) { advanced = !advanced }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (advanced) "▾" else "▸", color = T.Text2, fontSize = 14.sp)
                Text(QoderText.Advanced.text(), color = T.Text2, fontSize = 14.sp)
            }
            if (advanced) {
                ModelFormField(QoderText.Gateway.text()) {
                    ModelFormInput(current.fields.baseURL, { draft = current.copy(fields = current.fields.copy(baseURL = it)) }, QoderText.Gateway.text(), enabled = fieldsEnabled)
                }
                ModelFormField(QoderText.OpenApi.text()) {
                    ModelFormInput(current.fields.openApiUrl, { draft = current.copy(fields = current.fields.copy(openApiUrl = it)) }, QoderText.OpenApi.text(), enabled = fieldsEnabled)
                }
                QoderHint(QoderText.EndpointHint)
            }
            failure?.let { Text(it.text(), color = T.Err, fontSize = 12.sp) }
            if (partial) QoderHint(QoderText.Partial)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                ModelFormButton((if (partial) QoderText.Close else QoderText.Cancel).text(), ::close, enabled = !busy)
                ModelFormButton((if (busy) QoderText.Saving else QoderText.Save).text(), ::save,
                    enabled = fieldsEnabled && failure != QoderText.Conflict && (current.pat.isBlank() || tokenWritable), primary = true)
            }
        }
    }
}

@Composable
private fun QoderHint(text: QoderText) {
    Text(text.text(), color = T.Text2, fontSize = 12.sp, lineHeight = 18.sp)
}
