package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

/** Desktop trajectory inspector chrome, independent of the general-purpose panel header. */
@Composable
internal fun TrajectoryInspectorFrame(
    title: String,
    location: String,
    kind: String? = null,
    onDismiss: () -> Unit,
    tabs: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOff,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(T.Scrim).clickable(
                    onClickLabel = TrajL.detailsClose,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            )
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = 480.dp)
                    .fillMaxWidth().fillMaxHeight().semantics { paneTitle = title },
                color = T.Surface,
                shape = RectangleShape,
            ) {
                Column(Modifier.systemBarsPadding().imePadding()) {
                    Box(Modifier.fillMaxWidth().height(42.dp)) {
                        Row(
                            Modifier.fillMaxSize().padding(start = 12.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (kind == null) {
                                    Box(Modifier.size(5.dp).background(T.Text2, CircleShape))
                                    Text(
                                        title, color = T.Ink, fontSize = 12.sp, lineHeight = 16.sp,
                                        fontFamily = LocalFonts.code, fontWeight = FontWeight.Normal,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.semantics { heading() },
                                    )
                                } else {
                                    TrajectoryInspectorKindBadge(title, kind)
                                }
                                Text(
                                    location, color = T.Text3, fontSize = 11.sp, lineHeight = 16.sp,
                                    fontFamily = LocalFonts.code, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                            }
                            Box(
                                Modifier.size(28.dp).clickable(role = Role.Button, onClick = onDismiss),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(D.close, TrajL.detailsClose, tint = T.Text2, modifier = Modifier.size(18.dp))
                            }
                        }
                        HorizontalDivider(
                            Modifier.align(Alignment.BottomCenter), color = T.Border, thickness = 0.5.dp,
                        )
                    }
                    tabs()
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TrajectoryInspectorTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(34.dp)) {
        Row(
            Modifier.fillMaxSize().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    Modifier.width(IntrinsicSize.Max).fillMaxHeight()
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, color = if (selected) ThemeState.colors.StateBusinessPrimary else T.Text3,
                        fontSize = 13.sp, fontWeight = FontWeight.Normal, maxLines = 1,
                    )
                    if (selected) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                                .background(
                                    ThemeState.colors.StateBusinessPrimary,
                                    RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp),
                                ),
                        )
                    }
                }
            }
        }
        HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = T.Border, thickness = 0.5.dp)
    }
}

@Composable
internal fun TrajectoryOverviewLine(
    label: String,
    value: String,
    valueColor: Color = T.Ink,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, color = T.Text3, fontSize = 13.sp, lineHeight = 22.sp,
            modifier = Modifier.width(94.dp),
        )
        SelectionContainer(Modifier.weight(1f)) {
            Row(
                modifier = if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    value, color = valueColor, fontSize = 13.sp, lineHeight = 22.sp,
                    letterSpacing = 0.sp, fontWeight = FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (onClick != null) Icon(D.chevronRight, null, tint = T.Caption, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
internal fun TrajectoryOverviewSection(
    label: String,
    onOpen: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(24.dp).padding(bottom = 3.dp), contentAlignment = Alignment.BottomStart) {
            Row(
                modifier = Modifier.semantics { heading() }
                    .then(if (onOpen == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onOpen)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(label, color = T.Text2, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                if (onOpen != null) Icon(D.chevronRight, null, tint = T.Caption, modifier = Modifier.size(12.dp))
            }
        }
        content()
    }
}

@Composable
private fun TrajectoryInspectorKindBadge(title: String, kind: String) {
    val colors = ThemeState.colors
    val (foreground, background) = remember(kind, colors) {
        when (kind) {
            TrajL.kindUser -> colors.StateSuccessPrimary to colors.StateSuccessTertiary
            TrajL.kindContext -> trajectoryColorMix(colors.StateSuccessPrimary, colors.LabelSecondary, 0.68f) to colors.StateSuccessTertiary
            TrajL.kindAssistant -> trajectoryColorMix(colors.BrandPrimaryNewColorprimaryNewColor, colors.StateErrorSecondary, 0.60f) to
                trajectoryColorMix(trajectoryColorMix(colors.BrandPrimaryNewColorprimaryNewColor, colors.StateErrorSecondary, 0.55f), colors.BgLayer1, 0.15f)
            TrajL.kindTool -> colors.StateWarnLabel to colors.StateWarnTertiary
            TrajL.kindSubtool -> trajectoryColorMix(colors.StateWarnLabel, colors.LabelTertiary, 0.62f) to
                trajectoryColorMix(colors.StateWarnTertiary, colors.BgLayer1, 0.58f)
            TrajL.kindSystem, TrajL.kindCompacted -> colors.LabelSecondary to colors.BgModulePlatform
            else -> colors.LabelTertiary to colors.BgModulePlatform
        }
    }
    Box(
        Modifier.height(19.dp).background(background, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp).semantics { heading() },
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = foreground, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
