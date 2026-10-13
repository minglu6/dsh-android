package dev.dsh.pocket.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.PermissionCatalog
import dev.dsh.pocket.protocol.PermissionOption

/** Localize conventional built-ins without overwriting user-configured Host labels. */
fun permissionLabel(value: String, option: PermissionOption? = null): String {
    if (value == "auto") return "自动审查"
    val name = option?.name ?: value
    val builtIn = when (value) {
        "read-only" -> "Read Only" to "仅可查看"
        "workspace-write" -> "Workspace Write" to "工作区内修改"
        "danger-full-access" -> "Full access" to "完全权限"
        else -> null
    }
    return if (builtIn != null && (name == value || name == builtIn.first)) builtIn.second else name
}

/** Only the complete, current Host directory can advertise selectable permissions. */
@Composable
fun PermissionPickerSheet(
    current: String,
    catalog: PermissionCatalog?,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    enabled: Boolean = true,
    forDefaults: Boolean = false,
    busy: Boolean = false,
) {
    var picked by remember(catalog, current, loading, error) { mutableStateOf(false) }
    val options = if (loading || error != null) emptyList() else {
        if (forDefaults) catalog?.defaultOptions.orEmpty() else catalog?.options.orEmpty()
    }
    val interactive = enabled && !busy && !loading && error == null && !picked
    PocketSheet(onDismiss) {
        SheetTitle(if (forDefaults) "默认权限" else "访问模式")
        if (forDefaults) {
            Text("选择新会话的默认权限模式；自动审查仅适用于当前会话。", color = T.Text3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        }
        when {
            loading -> Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = T.Text3, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("加载权限模式…", color = T.Text3, fontSize = 14.sp)
            }
            error != null -> Text(error, color = T.Err, fontSize = 14.sp, modifier = Modifier.padding(22.dp))
            catalog == null -> Text("此主机的权限目录不可用", color = T.Text3, fontSize = 14.sp, modifier = Modifier.padding(22.dp))
            options.isEmpty() -> Text("没有可用的权限模式", color = T.Text3, fontSize = 14.sp, modifier = Modifier.padding(22.dp))
        }
        options.forEach { option ->
            OptionRow(
                title = permissionLabel(option.value, option),
                desc = if (option.value == "auto") "无沙箱运行；每次原生工具调用和 PTC 内层调用前由同一模型进行实验性审查。" else option.description,
                icon = D.permission(option.value),
                selected = current == option.value,
                enabled = interactive,
                trailing = if (option.value == "auto") "EXP" else null,
            ) {
                if (!picked) {
                    picked = true
                    if (current == option.value) onDismiss() else onPick(option.value)
                }
            }
        }
        if (!loading && (catalog == null || error != null)) {
            TextButton(onClick = onRefresh, enabled = enabled && !busy, modifier = Modifier.padding(horizontal = 12.dp)) { Text("重新加载") }
        }
    }
}

/** Acknowledgement is scoped to this visible sheet, never persisted across hosts or sessions. */
@Composable
fun PermissionRiskSheet(
    preset: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    enabled: Boolean = true,
    forDefaults: Boolean = false,
) {
    var acknowledged by remember(preset, forDefaults) { mutableStateOf(false) }
    var submitted by remember(preset, forDefaults) { mutableStateOf(false) }
    val auto = preset == "auto"
    val supported = preset == "danger-full-access" || (auto && !forDefaults)
    val interactive = enabled && supported && !submitted
    LaunchedEffect(enabled) { if (!enabled) acknowledged = false }
    PocketSheet(onDismiss) {
        Box(Modifier.padding(horizontal = 22.dp).size(44.dp).clip(CircleShape).background(T.WarnTint), contentAlignment = Alignment.Center) {
            Icon(D.warning, null, tint = T.WarnText)
        }
        Spacer(Modifier.height(12.dp))
        SheetTitle(if (auto) "确认启用自动审查（实验）？" else "确认启用完全权限？")
        val description = when {
            auto -> "自动审查不使用沙箱。每次原生工具调用和 PTC 内层调用前，都会由与当前 agent 相同的模型进行审查；审查拒绝的调用由你批准或拒绝。此功能仍属实验性，可能误放行或误拒绝，并会消耗额外 token。"
            forDefaults -> "启用完全权限后，新会话将减少确认步骤，并且可以直接执行更多操作，包括敏感操作、文件修改或外部命令。仅建议在你信任后续任务时使用。"
            else -> "启用完全权限后，智能体将减少确认步骤，并且可以直接执行更多操作，包括敏感操作、文件修改或外部命令。仅建议在你信任当前任务时使用。"
        }
        Text(description, fontSize = 14.sp, color = T.Text2, lineHeight = 22.sp, modifier = Modifier.padding(horizontal = 22.dp))
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp).clip(T.R8)
                .clickable(enabled = interactive) { acknowledged = !acknowledged }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(acknowledged, { acknowledged = it }, enabled = interactive, colors = CheckboxDefaults.colors(checkedColor = T.Ink))
            Text(if (auto) "我已了解这些风险，并愿意继续" else "我已了解风险，并愿意继续", fontSize = 14.sp)
        }
        Row(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onDismiss, Modifier.weight(1f).height(50.dp), shape = T.R12, border = BorderStroke(1.dp, T.Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink)) {
                Text("取消")
            }
            Button(
                onClick = {
                    if (interactive && acknowledged) {
                        submitted = true
                        acknowledged = false
                        onConfirm()
                    }
                },
                modifier = Modifier.weight(1f).height(50.dp),
                enabled = interactive && acknowledged,
                shape = T.R12,
                colors = PrimaryColors(),
            ) { Text(if (auto) "启用自动审查" else "启用完全权限") }
        }
    }
}
