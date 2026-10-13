package dev.dsh.pocket.app

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** The bridge exposes roster snapshots; poll only while this conversation is visible. */
@Composable
internal fun JobsAction(hostId: String?, sessionId: String, available: Boolean, onClick: () -> Unit) {
    var jobs by remember(hostId, sessionId, available) { mutableStateOf<List<JsonObject>?>(null) }
    var failed by remember(hostId, sessionId, available) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(hostId, sessionId, available, lifecycle) {
        if (!available) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    try {
                        jobs = Pocket.listJobs(sessionId)["jobs"].arr().orEmpty().filterIsInstance<JsonObject>()
                        failed = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Unknown is not zero, and a stale running count must not survive a failed refresh.
                        jobs = null
                        failed = true
                    }
                    delay(3_000)
                }
            } finally {
                jobs = null
                failed = false
            }
        }
    }
    if (!available || (!failed && jobs.isNullOrEmpty())) return
    val live = jobs.orEmpty().count {
        val status = it["status"].str()
        status == "running" || status == "stopping"
    }
    val en = Pocket.prefs.language == AppLanguage.En
    val label = when {
        failed -> if (en) "Job status unavailable" else "任务状态不可用"
        live > 0 -> if (en) "$live background ${if (live == 1) "job" else "jobs"} running" else "$live 个后台任务运行中"
        else -> if (en) "${jobs!!.size} background ${if (jobs!!.size == 1) "job" else "jobs"}" else "${jobs!!.size} 个后台任务"
    }
    val color = if (failed) T.Err else T.Text2
    TextButton(onClick = onClick, modifier = Modifier.padding(end = 8.dp).testTag("jobsAction")) {
        Icon(D.clock, null, modifier = Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 12.sp)
    }
}
