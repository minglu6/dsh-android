package dev.dsh.pocket.app

import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Long-press text selection / copy for output surfaces.
 * Prefer wrapping whole message / payload blocks once; use [NonSelectable]
 * around icon chrome, fold chevrons, and other controls inside.
 *
 * Named [SelectableOutput] (not Selectable) to avoid clashing with
 * `androidx.compose.foundation.text.selection.Selectable`.
 */
@Composable
fun SelectableOutput(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    SelectionContainer(modifier) {
        content()
    }
}

/** Exclude interactive chrome from an enclosing [SelectableOutput] scope. */
@Composable
fun NonSelectable(content: @Composable () -> Unit) {
    DisableSelection {
        content()
    }
}
