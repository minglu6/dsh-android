package dev.dsh.pocket.app

import androidx.compose.runtime.*
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.long
import dev.dsh.pocket.protocol.str
import kotlinx.serialization.json.JsonObject

/** JobView fields, not terminal-tool or todo projections. Exit facts remain producer-owned detail. */
internal data class BackgroundJob(
    val id: String,
    val kind: String,
    val label: String,
    val status: String,
    val detail: String?,
    val startedAt: Long?,
    val finishedAt: Long?,
    val outputTotal: Long,
) {
    val live get() = status == "running" || status == "stopping"
    val observable get() = live || outputTotal > 0

    fun duration(now: Long, en: Boolean): String? {
        val start = startedAt ?: return null
        val end = if (live) now else finishedAt ?: start
        val total = ((end - start).coerceAtLeast(0) / 1_000)
        val seconds = total % 60
        val minutes = total / 60 % 60
        val hours = total / 3_600
        return when {
            hours > 0 -> if (en) "${hours}h ${minutes}m" else "${hours}小时${minutes}分"
            minutes > 0 -> if (en) "${minutes}m ${seconds}s" else "${minutes}分${seconds}秒"
            else -> if (en) "${seconds}s" else "${seconds}秒"
        }
    }

    companion object {
        fun parse(row: JsonObject): BackgroundJob? {
            val id = row["id"].str() ?: return null
            return BackgroundJob(
                id = id,
                kind = row["kind"].str().orEmpty(),
                label = row["label"].str().orEmpty(),
                status = row["status"].str().orEmpty(),
                detail = row["progress"].str() ?: row["detail"].str(),
                startedAt = row["startedAt"].long(),
                finishedAt = row["finishedAt"].long(),
                outputTotal = row["output"]["total"].long() ?: 0,
            )
        }
    }
}

internal enum class JobKillState { Armed, Pending, Failed }
internal data class JobKillPhase(val id: String, val state: JobKillState)

/** One session's roster and client-only presentation; connection epochs fence late RPC replies. */
internal class JobsPresentation {
    var jobs by mutableStateOf<List<BackgroundJob>?>(null)
        private set
    var failed by mutableStateOf(false)
        private set
    var active by mutableStateOf(false)
        private set
    var open by mutableStateOf(false)
    var expandedId by mutableStateOf<String?>(null)
        private set
    var settledOpen by mutableStateOf<Boolean?>(null)
    var killPhase by mutableStateOf<JobKillPhase?>(null)
        private set
    private var cleared by mutableStateOf<Set<String>>(emptySet())
    private var epoch = 0L

    val liveRows by derivedStateOf { jobs.orEmpty().filter { it.live } }
    val settledRows by derivedStateOf { jobs.orEmpty().filter { !it.live && it.id !in cleared } }
    val settledExpanded get() = settledOpen ?: liveRows.isEmpty()
    val visibleCount get() = liveRows.size + settledRows.size
    val observedId get() = expandedId?.takeIf { id ->
        open && active && jobs.orEmpty().any { it.id == id && it.observable && (it.live || (settledExpanded && id !in cleared)) }
    }

    fun activate(): Long {
        epoch++
        active = true
        return epoch
    }

    fun deactivate(token: Long) {
        if (epoch != token) return
        epoch++
        active = false
        jobs = null
        failed = false
        open = false
        expandedId = null
        killPhase = null
    }

    fun receive(token: Long, next: List<BackgroundJob>?) {
        if (!active || epoch != token) return
        jobs = next?.sortedWith { left, right ->
            when {
                left.live != right.live -> if (left.live) -1 else 1
                left.live -> compareValues(left.startedAt, right.startedAt)
                else -> compareValues(right.finishedAt ?: right.startedAt, left.finishedAt ?: left.startedAt)
                    .takeIf { it != 0 } ?: compareValues(left.startedAt, right.startedAt)
            }
        }
        failed = next == null
        reconcile()
    }

    private fun reconcile() {
        if (jobs.orEmpty().none { it.id == expandedId && it.observable }) expandedId = null
        if (jobs.orEmpty().none { it.id == killPhase?.id && it.status == "running" }) killPhase = null
        if (visibleCount == 0 && !failed) open = false
    }

    fun toggle(id: String) {
        if (jobs.orEmpty().none { it.id == id && it.observable }) return
        expandedId = if (expandedId == id) null else id
    }

    fun clearSettled() {
        val ids = settledRows.map { it.id }.toSet()
        cleared = cleared + ids
        if (expandedId in ids) expandedId = null
        reconcile()
    }

    /** Second press alone calls the real mutation. Admission does not manufacture a status change. */
    fun pressKill(id: String, request: ((Boolean) -> Unit) -> Unit) {
        if (!active || jobs.orEmpty().none { it.id == id && it.status == "running" }) return
        val phase = killPhase
        if (phase?.state == JobKillState.Pending) return
        if (phase == null || phase.id != id || phase.state != JobKillState.Armed) {
            killPhase = JobKillPhase(id, JobKillState.Armed)
            return
        }
        val pending = JobKillPhase(id, JobKillState.Pending)
        val token = epoch
        killPhase = pending
        request { admitted ->
            if (active && token == epoch && killPhase === pending && !admitted) {
                killPhase = JobKillPhase(id, JobKillState.Failed)
            }
        }
    }

    fun expireKill(phase: JobKillPhase) {
        if (killPhase === phase && phase.state != JobKillState.Pending) killPhase = null
    }
}
