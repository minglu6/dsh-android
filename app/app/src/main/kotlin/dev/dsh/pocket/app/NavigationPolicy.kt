package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.serialization.json.JsonElement

internal fun navText(zh: String, en: String): String =
    if (Pocket.prefs.language == AppLanguage.En) en else zh

/** Match the desktop's automatic title; paths remain unchanged for Host operations. */
internal fun workspaceDisplayTitle(
    title: String?, path: String,
    localizedDefault: String = navText("默认工作区", "Default workspace"),
): String {
    val label = title?.takeIf { it.isNotBlank() } ?: path.trimEnd('/', '\\')
        .substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
    return if (label == "default-workspace") localizedDefault else label
}

/** Host paths are identities, never their display basenames. Preserve absent cwd as ungrouped. */
internal fun workspacePathKey(path: String?): String =
    if (path.isNullOrBlank()) "" else path.trimEnd('/').ifEmpty { "/" }

internal fun visibleDrawerSession(s: SessionSummary, current: String?, archived: Set<String>, filter: ArchivedFilter): Boolean =
    s.origin != "subagent" && (!s.blank || s.sessionId == current) && when (filter) {
        ArchivedFilter.Default -> s.sessionId !in archived && s.archived != true
        ArchivedFilter.Show -> true
        ArchivedFilter.Only -> s.sessionId in archived || s.archived == true
    }

/** Keep hidden archives in the same order account; insert ordinary forks before their source. */
internal fun reconcileSessionOrder(sessions: List<SessionSummary>, saved: List<String>): List<String> {
    val members = sessions.associateBy { it.sessionId }
    val order = saved.filter { it in members }.distinct().toMutableList()
    val missing = sessions.sortedByDescending { it.updatedAt }.filter { it.sessionId !in order }
    fun insert(s: SessionSummary, visiting: Set<String>) {
        if (s.sessionId in order) return
        val parent = members[s.parentSessionId]
        if (parent != null && parent.sessionId !in visiting && parent.sessionId !in order) insert(parent, visiting + s.sessionId)
        val at = order.indexOf(s.parentSessionId)
        if (at >= 0 && s.origin != "subagent") order.add(at, s.sessionId) else order.add(s.sessionId)
    }
    missing.forEach { insert(it, emptySet()) }
    return order
}

internal data class WorkspaceBranch(val path: String, val children: List<WorkspaceBranch>)

/** A path-segment tree, so /a/project never absorbs /b/project or /a/project-other. */
internal fun workspaceBranches(paths: List<String>): List<WorkspaceBranch> {
    val keys = paths.map(::workspacePathKey).filter { it.isNotEmpty() }.distinct()
    fun children(parent: String?): List<WorkspaceBranch> = keys.filter { path ->
        val ancestor = keys.filter { it != path && path.startsWith(if (it == "/") it else "$it/") }.maxByOrNull { it.length }
        ancestor == parent
    }.map { WorkspaceBranch(it, children(it)) }
    return children(null)
}

internal fun archiveActivityDescription(details: JsonElement?): String =
    details["activity"].arr().orEmpty().joinToString("\n") { activity ->
        val kind = activity["kind"].str().orEmpty()
        val family = when (kind) {
            "turn" -> navText("进行中的回合", "Turn in progress")
            "subagent" -> navText("子智能体", "Subagents")
            "job" -> navText("后台任务", "Background jobs")
            "schedule" -> navText("定时提醒", "Scheduled reminders")
            else -> kind
        }
        val names = activity["items"].arr().orEmpty().mapNotNull { it["label"].str() ?: it["id"].str() }
        if (names.isEmpty()) family else "$family: ${names.joinToString(", ")}"
    }
