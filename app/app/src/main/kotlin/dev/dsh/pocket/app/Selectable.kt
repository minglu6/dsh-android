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

/**
 * Phone soft-wrap helper: insert zero-width spaces so long paths / URLs / tokens
 * can break mid-string. Prefer removing [horizontalScroll] so Text gets a finite
 * max width — horizontalScroll alone is what forced single-line overflow.
 */
fun String.breakAnywhere(every: Int = 10): String = buildString(length + length / every + 8) {
    var run = 0
    for (c in this@breakAnywhere) {
        append(c)
        when {
            c.isWhitespace() || c == '\u200B' -> run = 0
            c == '/' || c == '\\' || c == '_' || c == '-' || c == '.' ||
                c == ',' || c == ';' || c == ':' || c == '?' || c == '&' ||
                c == '=' || c == '#' || c == '@' || c == '%' || c == '+' ||
                c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}' -> {
                append('\u200B')
                run = 0
            }
            else -> {
                run++
                if (run >= every) {
                    append('\u200B')
                    run = 0
                }
            }
        }
    }
}
