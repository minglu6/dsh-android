package dev.dsh.pocket.app

import android.graphics.Typeface
import android.text.TextUtils
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

private fun chromeText(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

internal enum class TextPreviewMode { Preview, Code, Plain }

private fun previewModeLabel(mode: TextPreviewMode, richText: Boolean): String = when (mode) {
    TextPreviewMode.Preview -> chromeText("预览", "Preview")
    TextPreviewMode.Code -> if (richText) chromeText("源码", "Source") else chromeText("代码", "Code")
    TextPreviewMode.Plain -> chromeText("纯文本", "Plain text")
}

/** Both current workspace files and historical comparisons use this same preview shell. */
@Composable
internal fun FilePreviewChrome(
    path: String,
    sourceKey: Any,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    mode: TextPreviewMode,
    modes: List<TextPreviewMode>,
    richText: Boolean = false,
    onModeChange: (TextPreviewMode) -> Unit,
    wrap: Boolean,
    onWrapChange: () -> Unit,
    loading: Boolean,
    onRefresh: () -> Unit,
    language: String?,
    copyText: (() -> String)? = null,
    onPathClick: (() -> Unit)? = null,
    pathActions: @Composable RowScope.() -> Unit = {},
    codeFacts: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    var menu by remember(sourceKey, path) { mutableStateOf(false) }
    var copied by remember(sourceKey, path) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(copied, sourceKey, path) {
        if (copied) { delay(1000); copied = false }
    }
    RightPanelFrame(
        title = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path },
        onDismiss = onDismiss,
        onBack = onBack,
        titleIcon = { FileTypeIcon(path, modifier = Modifier.padding(horizontal = 12.dp).size(18.dp)) },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PreviewPathLabel(path, Modifier.weight(1f).padding(vertical = 12.dp).testTag("previewPath"),
                style = T.Mono.copy(fontSize = 12.sp), fileNameColor = T.Ink, onClick = onPathClick)
            pathActions()
            if (modes.size > 1) Box {
                TextButton(
                    onClick = { menu = true },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = T.Text2),
                    modifier = Modifier.testTag("previewMode").semantics { contentDescription = chromeText("打开方式", "Open with") },
                ) { Text(previewModeLabel(mode, richText), fontSize = 12.sp, maxLines = 1) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = T.Surface) {
                    modes.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(previewModeLabel(candidate, richText), fontSize = 13.sp) },
                            trailingIcon = { if (candidate == mode) Icon(D.check, null, Modifier.size(16.dp), tint = T.Text2) },
                            modifier = Modifier.testTag("previewMode:${candidate.name}").semantics { selected = candidate == mode },
                            colors = MenuDefaults.itemColors(textColor = T.Ink),
                            onClick = { onModeChange(candidate); menu = false },
                        )
                    }
                }
            }
            if (modes.isNotEmpty() && mode != TextPreviewMode.Preview) {
                PreviewToolIcon(if (wrap) D.nowrap else D.wrap, if (wrap) L.unwrapLines() else L.wrapLines(),
                    ariaLabel = L.wrapLines(), checked = wrap, onClick = onWrapChange)
            }
            PreviewToolIcon(D.refresh, chromeText("刷新文件", "Refresh file"), enabled = !loading, onClick = onRefresh)
        }
        HorizontalDivider(color = T.Border, thickness = 0.5.dp)
        if (modes.isNotEmpty() && mode != TextPreviewMode.Preview) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).testTag("previewCodeBar"), verticalAlignment = Alignment.CenterVertically) {
                Text(language ?: previewModeLabel(mode, richText), color = T.Text2, fontFamily = LocalFonts.code,
                    fontSize = 12.sp, modifier = Modifier.weight(1f))
                codeFacts()
                PreviewToolIcon(if (copied) D.check else D.copy, if (copied) L.copied() else L.copy(), enabled = copyText != null) {
                    if (!copied) copyText?.let { clipboard.setText(AnnotatedString(it())); copied = true }
                }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).testTag("previewContent"), content = content)
    }
}

/** Native start ellipsis measures the actual glyphs; the complete path remains accessible and copyable. */
@Composable
internal fun PreviewPathLabel(
    path: String,
    modifier: Modifier = Modifier,
    color: Color = T.Text3,
    style: TextStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
    fileNameColor: Color = color,
    onClick: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val currentPath by rememberUpdatedState(path)
    val currentClick by rememberUpdatedState(onClick)
    val label: CharSequence = remember(path, color, fileNameColor) {
        if (fileNameColor == color) path else android.text.SpannableString(path).apply {
            val split = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1
            setSpan(android.text.style.ForegroundColorSpan(fileNameColor.toArgb()), split, path.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    val typeface = LocalFontFamilyResolver.current.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal,
        style.fontStyle ?: FontStyle.Normal).value as Typeface
    val copyLabel = chromeText("复制完整路径", "Copy full path")
    AndroidView(
        modifier = modifier.semantics {
            contentDescription = path
            customActions = listOf(CustomAccessibilityAction(copyLabel) { clipboard.setText(AnnotatedString(path)); true })
        },
        factory = { context -> TextView(context).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.START
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            textDirection = android.view.View.TEXT_DIRECTION_LTR
            setOnClickListener { currentClick?.invoke() }
            setOnLongClickListener { clipboard.setText(AnnotatedString(currentPath)); true }
        } },
        update = { view ->
            view.text = label
            view.setTextColor(color.toArgb())
            view.textSize = style.fontSize.value
            view.typeface = typeface
            view.isClickable = onClick != null
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewToolIcon(
    icon: ImageVector,
    label: String,
    ariaLabel: String = label,
    enabled: Boolean = true,
    checked: Boolean? = null,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label, fontSize = 12.sp) } },
        state = rememberTooltipState(),
    ) {
        if (checked == null) {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
                Icon(icon, ariaLabel, tint = if (enabled) T.Text2 else T.Text3, modifier = Modifier.size(16.dp))
            }
        } else {
            IconToggleButton(
                checked = checked, onCheckedChange = { onClick() }, enabled = enabled,
                modifier = Modifier.size(40.dp).semantics {
                    stateDescription = if (checked) chromeText("已换行", "Wrapped") else chromeText("不换行", "Not wrapped")
                },
                colors = IconButtonDefaults.iconToggleButtonColors(contentColor = T.Text2, checkedContentColor = T.Ink),
            ) { Icon(icon, ariaLabel, modifier = Modifier.size(16.dp)) }
        }
    }
}
