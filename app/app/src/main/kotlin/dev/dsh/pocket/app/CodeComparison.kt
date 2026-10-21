package dev.dsh.pocket.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.dsh.pocket.protocol.WorkspaceFileDiff
import org.json.JSONArray
import org.json.JSONObject

/** Only served turn-snapshot hunks enter the common offline renderer. */
internal fun codeComparisonDocumentScript(diff: WorkspaceFileDiff): String {
    val hunks = JSONArray()
    for (hunk in diff.hunks) {
        hunks.put(JSONObject().put("oldStart", hunk.oldStart).put("newStart", hunk.newStart)
            .put("lines", JSONArray(hunk.lines)))
    }
    val value = JSONObject().put("path", diff.path).put("before", diff.before).put("after", diff.after)
        .put("coarse", diff.coarse).put("hunks", hunks)
    return "window.codePreview.setComparison(" + value.toString()
        .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ");"
}

@Composable
internal fun CodeComparisonBody(diff: WorkspaceFileDiff, wrap: Boolean, plain: Boolean, modifier: Modifier) {
    val document = remember(diff) { codeComparisonDocumentScript(diff) }
    SharedCodePreviewBody(document, wrap, plain, modifier)
}

/** One-sided source is what is visible; mixed snapshots are patches, never fake full files. */
internal fun codeComparisonCopyText(diff: WorkspaceFileDiff): String {
    val added = diff.hunks.any { hunk -> hunk.lines.any { it.startsWith('+') } }
    val deleted = diff.hunks.any { hunk -> hunk.lines.any { it.startsWith('-') } }
    if (added != deleted) return buildString {
        var first = true
        for (hunk in diff.hunks) for (line in hunk.lines) {
            if ((added && line.startsWith('-')) || (deleted && line.startsWith('+'))) continue
            if (!first) append('\n')
            append(line.drop(1))
            first = false
        }
    }
    if (diff.hunks.isEmpty()) return ""
    return buildString {
        append("--- ").append(if (diff.before) "a/${diff.path}" else "/dev/null").append('\n')
        append("+++ ").append(if (diff.after) "b/${diff.path}" else "/dev/null").append('\n')
        for (hunk in diff.hunks) {
            val oldCount = hunk.lines.count { !it.startsWith('+') }
            val newCount = hunk.lines.count { !it.startsWith('-') }
            append("@@ -").append(hunk.oldStart).append(',').append(oldCount)
                .append(" +").append(hunk.newStart).append(',').append(newCount).append(" @@\n")
            for (line in hunk.lines) append(line).append('\n')
        }
    }
}
