package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Files remain in Host order: their list index identifies the corresponding diff. */
data class WorkspaceChangedFile(
    val path: String,
    val display: String,
    val added: Int,
    val deleted: Int,
    val binary: Boolean = false,
    val oversized: Boolean = false,
)

data class WorkspaceChangesSummary(
    val turn: Int,
    val cwd: String,
    val files: List<WorkspaceChangedFile>,
    val total: Int,
    val added: Int,
    val deleted: Int,
) {
    companion object {
        fun parse(value: JsonElement?): WorkspaceChangesSummary? {
            val obj = value.obj() ?: return null
            val turn = obj["turn"].changeCount() ?: return null
            val cwd = obj["cwd"].str() ?: return null
            val total = obj["total"].changeCount()?.takeIf { it > 0 } ?: return null
            val added = obj["added"].changeCount() ?: return null
            val deleted = obj["deleted"].changeCount() ?: return null
            val entries = obj["files"].arr() ?: return null
            if (entries.size > total) return null
            // Never drop malformed entries: doing so would shift every later Host diff index.
            val files = entries.map { entry ->
                val file = entry.obj() ?: return null
                WorkspaceChangedFile(
                    path = file["path"].str() ?: return null,
                    display = file["display"].str() ?: return null,
                    added = file["added"].changeCount() ?: return null,
                    deleted = file["deleted"].changeCount() ?: return null,
                    binary = if ("binary" in file) file["binary"].bool() ?: return null else false,
                    oversized = if ("oversized" in file) file["oversized"].bool() ?: return null else false,
                )
            }
            return WorkspaceChangesSummary(turn, cwd, files, total, added, deleted)
        }
    }
}

data class WorkspaceDiffHunk(val oldStart: Int, val newStart: Int, val lines: List<String>)

data class WorkspaceFileDiff(
    val kind: String,
    val path: String,
    val display: String,
    val before: Boolean = true,
    val after: Boolean = true,
    val hunks: List<WorkspaceDiffHunk> = emptyList(),
    val coarse: Boolean = false,
) {
    companion object {
        fun parse(value: JsonElement?): WorkspaceFileDiff? {
            val obj = value.obj() ?: return null
            val kind = obj["kind"].str() ?: return null
            val path = obj["path"].str() ?: return null
            val display = obj["display"].str() ?: return null
            if (kind == "binary" || kind == "oversized") return WorkspaceFileDiff(kind, path, display)
            if (kind != "text") return null
            val before = obj["before"].bool() ?: return null
            val after = obj["after"].bool() ?: return null
            val coarse = obj["coarse"].bool() ?: return null
            val hunks = (obj["hunks"].arr() ?: return null).map { hunk ->
                WorkspaceDiffHunk(
                    oldStart = hunk["oldStart"].changeCount() ?: return null,
                    newStart = hunk["newStart"].changeCount() ?: return null,
                    lines = (hunk["lines"].arr() ?: return null).map { it.str() ?: return null },
                )
            }
            return WorkspaceFileDiff(kind, path, display, before, after, hunks, coarse)
        }
    }
}

private fun JsonElement?.changeCount(): Int? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }
