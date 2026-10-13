package dev.dsh.pocket.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

private fun panelText(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

/** One bounded end panel for session tools and the local browser. */
@Composable
internal fun RightPanelFrame(
    title: String,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onBack ?: onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOff,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(T.Scrim).clickable(
                onClickLabel = panelText("收起右侧边栏", "Collapse right sidebar"),
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ))
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = 480.dp)
                    .fillMaxWidth().fillMaxHeight().testTag("rightPanel").semantics { paneTitle = title },
                color = T.Surface,
            ) {
                Column(Modifier.systemBarsPadding().imePadding()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onBack != null) {
                            IconButton(onBack) { Icon(D.chevronLeft, panelText("返回", "Back"), tint = T.Text2, modifier = Modifier.size(20.dp)) }
                        } else {
                            Icon(ImageVector.vectorResource(R.drawable.dsh_compass), null, tint = T.Text3,
                                modifier = Modifier.padding(horizontal = 12.dp).size(16.dp))
                        }
                        Text(title, color = T.Ink, fontSize = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
                        actions()
                        IconButton(onDismiss) {
                            Icon(D.panelRight, panelText("收起右侧边栏", "Collapse right sidebar"), tint = T.Text2, modifier = Modifier.size(15.dp))
                        }
                    }
                    HorizontalDivider(color = T.Border, thickness = 0.5.dp)
                    content()
                }
            }
        }
    }
}

/** Official GuideBody: compass watermark and a centered column of entry capsules. */
@Composable
fun PanelSheet(
    onDismiss: () -> Unit,
    onFiles: () -> Unit,
    onTerminal: () -> Unit,
    onBrowser: () -> Unit,
    onInspector: () -> Unit,
) {
    val ops by Pocket.ops.collectAsState()
    val files = "files.list" in ops
    val terminal = "terminal.create" in ops && "terminal.follow" in ops
    RightPanelFrame(panelText("开始", "Start"), onDismiss) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val availableHeight = maxHeight
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .heightIn(min = availableHeight).padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(ImageVector.vectorResource(R.drawable.dsh_compass), null,
                    tint = if (ThemeState.dark) Color(0xFF3C3C3D) else Color(0xFFE5E5E5),
                    modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(30.dp))
                if (files) GuideEntry(panelText("工作区文件", "Workspace files"),
                    panelText("浏览会话工作区的文件", "Browse files in this session's workspace"), onFiles) {
                    Image(painterResource(R.drawable.dsh_guide_files), contentDescription = null, modifier = Modifier.size(26.dp))
                }
                if (files && terminal) Spacer(Modifier.height(14.dp))
                if (terminal) GuideEntry(panelText("终端", "Terminal"),
                    panelText("在会话工作区运行命令", "Run commands in the Session workspace"), onTerminal) {
                    PluginArtwork("terminal", Modifier.size(26.dp))
                }
                Spacer(Modifier.height(14.dp))
                GuideEntry(panelText("浏览器", "Browser"),
                    panelText("浏览网页", "Browse web pages"), onBrowser) {
                    Image(painterResource(R.drawable.dsh_guide_browser), contentDescription = null, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.height(14.dp))
                GuideEntry(panelText("会话数据诊断", "Session data inspector"),
                    panelText("在侧边栏分析当前会话原始日志和聊天分组数据", "Analyze raw logs and grouped chat messages for the current session in the sidebar."), onInspector) {
                    Icon(D.inspect, null, tint = T.Text2, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.height(56.dp))
            }
        }
    }
}

@Composable
private fun GuideEntry(title: String, description: String, onClick: () -> Unit, artwork: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = T.R20,
        color = T.Surface,
        border = BorderStroke(0.5.dp, ThemeState.colors.BorderL3),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Guide entries carry fixed colors; the monochrome file/PTY icons belong to controls.
            artwork()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = T.Ink, fontSize = 14.sp, lineHeight = 20.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(description, color = T.Text3, fontSize = 11.sp, lineHeight = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
