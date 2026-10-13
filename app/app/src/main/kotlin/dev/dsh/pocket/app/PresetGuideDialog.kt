package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal enum class PresetGuidePage { Explanation, Usage }

private fun presetGuideKey(id: String): String? = when (id) {
    "standard" -> "Standard"
    "ptc" -> "Ptc"
    "minimal" -> "Minimal"
    "cordis" -> "Cordis"
    else -> null
}

/** The caller also checks the roster's system trust; an ID alone cannot establish that. */
internal fun presetGuideAvailable(id: String): Boolean = presetGuideKey(id) != null

private data class PresetGuideSection(val title: String, val body: String)

/** The official dictionary contains level-three sections, including every example. */
private fun guideSections(text: String): List<PresetGuideSection> =
    text.split(Regex("(?m)(?=^### )")).filter(String::isNotBlank).map { section ->
        PresetGuideSection(
            title = section.substringBefore('\n').removePrefix("### "),
            body = section.substringAfter('\n').trim(),
        )
    }

/** Read-only local help; neither tab navigation nor dismissal writes any preference. */
@Composable
internal fun PresetGuideDialog(
    presetId: String,
    initialPage: PresetGuidePage,
    onDismiss: () -> Unit,
) {
    val guideKey = presetGuideKey(presetId) ?: return
    val strings = if (Pocket.prefs.language == AppLanguage.En) PresetGuideLocales.en else PresetGuideLocales.zh
    val title = strings.getValue("preset${guideKey}Name")
    val explanation = remember(guideKey, strings) { guideSections(strings.getValue("guide${guideKey}Explanation")) }
    val usage = remember(guideKey, strings) { guideSections(strings.getValue("guide${guideKey}Usage")) }
    key(presetId, initialPage) {
        var page by rememberSaveable { mutableStateOf(initialPage) }
        val explanationScroll = rememberScrollState()
        val usageScroll = rememberScrollState()
        val initialTabFocus = remember { FocusRequester() }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                val horizontalPadding = if (maxWidth >= 480.dp) 28.dp else 20.dp
                Surface(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().height(minOf(600.dp, maxHeight))
                        .testTag("presetGuideDialog")
                        .semantics { paneTitle = title }
                        .onPreviewKeyEvent {
                            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                                onDismiss()
                                true
                            } else false
                        },
                    shape = T.R28,
                    color = T.Surface2,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, T.Border),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 24.dp, bottom = 20.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(title, color = T.Ink, fontSize = 20.sp, lineHeight = 28.sp,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("presetGuideClose")) {
                                    Icon(D.close, contentDescription = strings.getValue("close"), tint = T.Text3, modifier = Modifier.size(18.dp))
                                }
                            }
                            Text(strings.getValue("guide${guideKey}Intro"), color = T.Text2, fontSize = 14.sp, lineHeight = 22.sp)
                        }
                        Row(
                            Modifier.padding(horizontal = horizontalPadding).fillMaxWidth()
                                .clip(T.R8).background(T.Fill2).padding(3.dp)
                                .selectableGroup().semantics { contentDescription = strings.getValue("guideSections") },
                        ) {
                            PresetGuidePage.entries.forEach { tab ->
                                val selected = page == tab
                                Box(
                                    Modifier.weight(1f).clip(T.R8)
                                        .background(if (selected) T.Surface2 else T.Fill2)
                                        .then(if (selected) Modifier.border(0.5.dp, T.Border, T.R8) else Modifier)
                                        .then(if (tab == initialPage) Modifier.focusRequester(initialTabFocus) else Modifier)
                                        .selectable(selected = selected, role = Role.Tab, onClick = { page = tab })
                                        .heightIn(min = 42.dp).padding(horizontal = 8.dp, vertical = 6.dp)
                                        .testTag("presetGuideTab${tab.name}"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(strings.getValue(if (tab == PresetGuidePage.Explanation) "modeExplanation" else "howToUse"),
                                        color = if (selected) T.Ink else T.Text2, fontSize = 14.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                        // Both states live above the conditional page, preserving each page's scroll offset.
                        Column(
                            Modifier.weight(1f).fillMaxWidth().padding(top = 22.dp)
                                .verticalScroll(if (page == PresetGuidePage.Explanation) explanationScroll else usageScroll)
                                .padding(start = horizontalPadding, end = horizontalPadding, bottom = 24.dp)
                                .testTag("presetGuidePanel${page.name}"),
                            verticalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                            if (page == PresetGuidePage.Explanation) {
                                explanation.forEach { section ->
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        GuideHeading(section.title)
                                        MarkdownView(section.body, bodyFontSize = 16.sp)
                                    }
                                }
                            } else {
                                usage.forEach { section -> GuideExample(section, strings.getValue("guideExampleTask")) }
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) { initialTabFocus.requestFocus() }
            }
        }
    }
}

@Composable
private fun GuideHeading(title: String) {
    Text(title, color = T.Ink, fontSize = 16.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideExample(section: PresetGuideSection, exampleLabel: String) {
    val paragraphs = remember(section.body) { section.body.split("\n\n") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GuideHeading(section.title)
            Row(Modifier.clip(T.R8).background(T.Fill2).padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Official IconPlanOutlineRegular shares the IconListPenOutlineRegular artwork.
                Icon(D.plan, contentDescription = null, tint = T.Text2, modifier = Modifier.size(12.dp))
                Text(exampleLabel, color = T.Text2, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        paragraphs.forEach { paragraph ->
            if (paragraph.startsWith("> ")) {
                Surface(color = T.Fill2, shape = T.R16, modifier = Modifier.fillMaxWidth()) {
                    MarkdownView(paragraph.lineSequence().joinToString("\n") { it.removePrefix("> ") },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), bodyFontSize = 16.sp)
                }
            } else {
                SelectableOutput {
                    Text(paragraph, color = T.Text2, fontSize = 14.sp, lineHeight = 22.sp)
                }
            }
        }
    }
}
