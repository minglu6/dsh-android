package dev.dsh.pocket.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class ReferenceProjectionTest {
    @Test fun mixedReferencesKeepCanonicalTextAndShortLabels() {
        val session = "@[研发\\] 讨论](dsh-session:InMxIg)"
        val text = "请看 @\"目录 name/设计.kt\" @src/main/ $session 然后继续"
        val projection = referenceProjection(TextFieldValue(text, TextRange(text.length)))
        assertEquals(text, projection.original)
        assertEquals(listOf("设计.kt", "main/", "研发] 讨论"), projection.references.map { it.reference.label })
        assertEquals(listOf(ComposerReferenceKind.File, ComposerReferenceKind.Directory, ComposerReferenceKind.Session), projection.references.map { it.reference.kind })
        assertEquals(session, projection.references.last().reference.canonical)
        assertEquals("目录 name/设计.kt", projection.references.first().reference.path)
        assertFalse(projection.display.contains("dsh-session:"))
        assertFalse(projection.display.contains("目录 name/"))
    }

    @Test fun activePlainAndQuotedQueriesRemainEditableAndEmailIsNotAReference() {
        for (text in listOf("@src/", "看 @\"目录 name/", "mail@example.com", "path/src", "/goal")) {
            val projection = referenceProjection(TextFieldValue(text, TextRange(text.length)))
            assertEquals(text, projection.display)
            assertTrue(projection.references.isEmpty())
        }
        assertNull(composerTrigger("@\"folder name/a.kt\"", 19))
        assertNull(composerTrigger("@[hello](dsh-session:InMxIg)", 28))
    }

    @Test fun quotedAndSessionTokensAreCompleteEvenAtEndOfDraft() {
        for (text in listOf("@\"folder name/a.kt\"", "@[会话](dsh-session:InMxIg)")) {
            assertEquals(1, referenceProjection(TextFieldValue(text, TextRange(text.length))).references.size)
        }
    }

    @Test fun offsetMappingNeverExposesHiddenUtf16PositionsAndPreservesSurroundingChinese() {
        val text = "中\uD83D\uDE00 @\"文件 夹/代码.kt\" 后 @src/ 完"
        val projection = referenceProjection(TextFieldValue(text, TextRange(text.length)))
        var previous = -1
        for (offset in 0..text.length) {
            val mapped = projection.originalToTransformed(offset)
            assertTrue(mapped >= previous)
            assertTrue(mapped in 0..projection.display.length)
            previous = mapped
        }
        previous = -1
        for (offset in 0..projection.display.length) {
            val mapped = projection.transformedToOriginal(offset)
            assertTrue(mapped >= previous)
            assertTrue(mapped in 0..text.length)
            assertFalse(projection.references.any { mapped > it.reference.start && mapped < it.reference.end })
            previous = mapped
        }
        for (span in projection.references) {
            assertEquals(span.start, projection.originalToTransformed(span.reference.start))
            assertEquals(span.end, projection.originalToTransformed(span.reference.end))
            assertEquals(span.reference.start, projection.transformedToOriginal(span.start))
            assertEquals(span.reference.end, projection.transformedToOriginal(span.end))
        }
        val after = text.indexOf("后")
        assertEquals(after, projection.transformedToOriginal(projection.originalToTransformed(after)))
    }

    @Test fun deletingOneUtf16UnitOfAChipDeletesItsEntireCanonicalReference() {
        val text = "前 @\"目录 name/文件.kt\" 后"
        val value = TextFieldValue(text, TextRange(text.length))
        val projection = referenceProjection(value)
        val ref = projection.references.single().reference
        val backspace = TextFieldValue(text.removeRange(ref.end - 1, ref.end), TextRange(ref.end - 1))
        val result = normalizeReferenceEdit(value.copy(selection = TextRange(ref.end)), backspace, projection)
        assertEquals("前  后", result.text)
        assertEquals(TextRange(ref.start), result.selection)
        val delete = TextFieldValue(text.removeRange(ref.start, ref.start + 1), TextRange(ref.start))
        assertEquals(result, normalizeReferenceEdit(value.copy(selection = TextRange(ref.start)), delete, projection))
    }

    @Test fun reverseSelectionExpandsBothEdgesForCanonicalCopyAndCut() {
        val text = "@a.kt 与 @[会话](dsh-session:InMxIg) end"
        val value = TextFieldValue(text, TextRange(text.length))
        val projection = referenceProjection(value)
        val last = projection.references.last().reference
        val next = value.copy(selection = TextRange(last.end - 2, 2))
        val selected = normalizeReferenceEdit(value, next, projection)
        assertEquals(TextRange(last.end, 0), selected.selection)
        assertEquals(text.substring(0, last.end), selected.text.substring(selected.selection.min, selected.selection.max))
        val cut = normalizeReferenceEdit(selected, TextFieldValue(" end", TextRange(0)), projection)
        assertEquals(" end", cut.text)
    }

    @Test fun replacingReferenceWithSharedPrefixKeepsTheEntirePastedReplacement() {
        val text = "@old/file.kt rest"
        val projection = referenceProjection(TextFieldValue(text, TextRange(text.length)))
        val ref = projection.references.single().reference
        val selected = TextFieldValue(text, TextRange(ref.start, ref.end))
        val next = TextFieldValue("@old/other.kt rest", TextRange(13))
        assertEquals(next, normalizeReferenceEdit(selected, next, projection))
    }

    @Test fun activeImeCompositionIsNotCompacted() {
        val text = "@中文.kt "
        val value = TextFieldValue(text, TextRange(4), TextRange(1, 4))
        assertEquals(text, referenceProjection(value).display)
    }

    @Test fun cursorMovesAndShiftArrowsCrossWholeReference() {
        val text = "@src/a.kt tail"
        val value = TextFieldValue(text, TextRange(text.length))
        val projection = referenceProjection(value)
        val ref = projection.references.single().reference
        val right = referenceKeyEdit(value.copy(selection = TextRange(ref.start)), projection, Key.DirectionRight)!!
        assertEquals(TextRange(ref.end), right.selection)
        val left = referenceKeyEdit(right, projection, Key.DirectionLeft)!!
        assertEquals(TextRange(ref.start), left.selection)
        val selected = referenceKeyEdit(left, projection, Key.DirectionRight, shift = true)!!
        assertEquals(TextRange(ref.start, ref.end), selected.selection)
        assertEquals(TextRange(ref.end), normalizeReferenceEdit(left, left.copy(selection = TextRange(2)), projection).selection)
        assertEquals(TextRange(ref.start), normalizeReferenceEdit(right, right.copy(selection = TextRange(ref.end - 1)), projection).selection)
        assertNull(referenceKeyEdit(left, projection, Key.Enter))
        assertNull(referenceKeyEdit(left, projection, Key.Tab))
    }

    @Test fun removingTrailingSpaceDoesNotTurnCommittedReferenceBackIntoQuery() {
        val original = TextFieldValue("@src/a.kt ", TextRange(10))
        val projection = referenceProjection(original)
        val next = TextFieldValue("@src/a.kt", TextRange(9))
        val retained = referenceProjection(next, projection)
        assertEquals(1, retained.references.size)
        assertEquals("", referenceKeyEdit(next, retained, Key.Backspace)!!.text)
    }

    @Test fun insertingChineseBeforeReferenceRetainsItsIdentityAndCanonicalSessionPayload() {
        val session = "@[中文](dsh-session:InMxIg)"
        val before = TextFieldValue("$session tail", TextRange(0))
        val projection = referenceProjection(before)
        val next = TextFieldValue("说明 $session tail", TextRange(3))
        val after = referenceProjection(next, projection)
        assertEquals(session, after.references.single().reference.canonical)
        assertEquals(3, after.references.single().reference.start)
    }

    @Test fun changingSupplementaryCharacterOutsideReferencePreservesUtf16Selection() {
        val text = "\uD83D\uDE00 @src/a.kt tail"
        val before = TextFieldValue(text, TextRange(2))
        val next = TextFieldValue("\uD83D\uDE01 @src/a.kt tail", TextRange(2))
        assertEquals(next, normalizeReferenceEdit(before, next, referenceProjection(before)))
    }

    @Test fun canonicalFormattingPreservesExplicitQuotesAndDirectorySuffix() {
        assertEquals("@\"src/a.kt\"", fileReferenceToken("src/a.kt", false, preserveQuote = true))
        assertEquals("@\"目录 name/\"", fileReferenceToken("目录 name/", true))
        val replaced = replaceComposerToken("检查 @\"目录 na", 11, "@\"目录 name/文件.kt\"")
        assertEquals("检查 @\"目录 name/文件.kt\" ", replaced.first)
        assertEquals(replaced.first.length, replaced.second)
    }

    @Test fun middleOfTokenReplacementRemovesOldSuffixAndDrillDoesNotAddSpace() {
        val text = "read @src/old.kt after"
        val pick = replaceComposerToken(text, text.indexOf("old"), "@src/new.kt")
        assertEquals("read @src/new.kt  after", pick.first)
        val quoted = "read @\"design notes/old.md\" after"
        val drill = replaceComposerToken(quoted, quoted.indexOf("notes"), "@\"new notes/", appendSpace = false)
        assertEquals("read @\"new notes/ after", drill.first)
        assertEquals("read @\"new notes/".length, drill.second)
        val open = "read @\"design notes/old.md"
        assertEquals("read @\"new notes/", replaceComposerToken(open, open.indexOf("notes"), "@\"new notes/", appendSpace = false).first)
    }

    @Test fun reversedSelectionReplacementUsesTheEntireSelectedRange() {
        val text = "read @src/old.kt after"
        val start = text.indexOf('@')
        val end = text.indexOf(" after")
        val picked = replaceComposerToken(text, start, "@src/new.kt", selectionStart = end)
        assertEquals("read @src/new.kt  after", picked.first)
        assertEquals("read @src/new.kt ".length, picked.second)
    }

    @Test fun completeSessionWithAtInLabelNeverReopensQueryAtAnyCaretPosition() {
        val text = "@[讨论 @review 后续](dsh-session:InMxIg)"
        for (caret in 1..text.length) assertNull(composerTrigger(text, caret))
        val projection = referenceProjection(TextFieldValue("$text ", TextRange(text.length + 1)))
        val deletion = TextFieldValue(text.dropLast(1) + " ", TextRange(text.length - 1))
        assertEquals(" ", normalizeReferenceEdit(TextFieldValue("$text ", TextRange(text.length)), deletion, projection).text)
    }

    @Test fun caretInsideUncommittedQuotedPathKeepsTheWholePathEditable() {
        val text = "@\"folder name/file.kt\""
        assertEquals(text, referenceProjection(TextFieldValue(text, TextRange(8))).display)
    }

    @Test fun adjacentImeTypingKeepsReferenceIdentityAndCompositionOffsets() {
        val token = "@\"folder name/a.kt\""
        val before = TextFieldValue(token, TextRange(token.length))
        val next = TextFieldValue(token + "中", TextRange(token.length + 1), TextRange(token.length, token.length + 1))
        val result = normalizeReferenceEdit(before, next, referenceProjection(before))
        assertEquals("$token 中", result.text)
        assertEquals(TextRange(token.length + 2), result.selection)
        assertEquals(TextRange(token.length + 1, token.length + 2), result.composition)
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ReferenceComposerFieldTest {
    @get:Rule val rule = createComposeRule()

    @Test fun nativeClipboardCopiesCanonicalMentionRatherThanDisplayedTitle() {
        val session = "@[可读标题](dsh-session:InMxIg)"
        val draft = mutableStateOf(TextFieldValue("$session ", TextRange(session.length + 1)))
        lateinit var clipboard: ClipboardManager
        rule.setContent { PocketTheme {
            clipboard = LocalClipboardManager.current
            ReferenceComposerField(draft.value, { draft.value = it }, Modifier.testTag("references"))
        } }
        val field = rule.onNodeWithTag("references")
        field.performClick()
        field.performTextInputSelection(TextRange(0, session.length))
        field.performSemanticsAction(SemanticsActions.CopyText) { it() }
        rule.runOnIdle { assertEquals(session, clipboard.getText()?.text) }
        field.performTextInputSelection(TextRange(draft.value.text.length))
        field.performSemanticsAction(SemanticsActions.PasteText) { it() }
        rule.runOnIdle { assertEquals("$session $session", draft.value.text) }
    }

    @Test fun hardwareDeleteAndTypingKeepTheOriginalFieldAndCanonicalDraft() {
        val token = "@\"目录 name/文件.kt\""
        val draft = mutableStateOf(TextFieldValue("$token 后", TextRange(0)))
        rule.setContent { PocketTheme {
            ReferenceComposerField(draft.value, { draft.value = it }, Modifier.testTag("references"))
        } }
        val field = rule.onNodeWithTag("references")
        field.performClick()
        field.performTextInputSelection(TextRange(0))
        field.performKeyInput { pressKey(Key.DirectionRight) }
        rule.runOnIdle { assertEquals(TextRange(token.length), draft.value.selection) }
        field.performKeyInput { pressKey(Key.Backspace) }
        rule.runOnIdle { assertEquals(" 后", draft.value.text) }
        field.performTextInput("继续")
        rule.runOnIdle { assertEquals("继续 后", draft.value.text) }
    }

    @Test fun tappingFileLabelOpensOriginalPathWithoutRebuildingItFromLabel() {
        val path = "folder name/设计.kt"
        val draft = mutableStateOf(TextFieldValue("@\"$path\" ", TextRange(path.length + 4)))
        var opened: String? = null
        rule.setContent { PocketTheme {
            ReferenceComposerField(draft.value, { draft.value = it }, Modifier.testTag("references"), { opened = it })
        } }
        val field = rule.onNodeWithTag("references")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val labelPosition = layouts.single().getBoundingBox(2).center
        field.performTouchInput { click(labelPosition) }
        rule.runOnIdle {
            assertEquals(path, opened)
            assertEquals("@\"$path\" ", draft.value.text)
        }
    }
}
