package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect

/** Mounted only for the visible expanded row; disposal cancels followJob and its Host subscription. */
@Composable
internal fun JobOutput(sessionId: String, job: BackgroundJob) {
    var output by remember(sessionId, job.id) { mutableStateOf("") }
    var error by remember(sessionId, job.id) { mutableStateOf<String?>(null) }
    var observed by remember(sessionId, job.id) { mutableStateOf<BackgroundJob?>(null) }
    LaunchedEffect(sessionId, job.id) {
        try {
            Pocket.followJob(sessionId, job.id).collect { frame ->
                coroutineContext.ensureActive()
                when (frame["type"].str()) {
                    "opened", "status" -> (frame["job"] as? kotlinx.serialization.json.JsonObject)?.let { observed = BackgroundJob.parse(it) }
                    "output" -> {
                        val incoming = buildString {
                            if (frame["lossy"].toString() == "true") append(jobText("\n[部分历史输出已过期]\n", "\n[Earlier output expired]\n"))
                            frame["chunks"].arr().orEmpty().forEach { chunk ->
                                if (chunk["gapBefore"].toString() == "true") append(jobText("\n[此处输出存在缺失]\n", "\n[Output missing here]\n"))
                                append(chunk["text"].str().orEmpty())
                            }
                        }
                        output += incoming
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: jobText("输出读取失败", "Output unavailable")
        }
    }
    val clipboard = LocalClipboardManager.current
    var copied by remember(job.id) { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth().clip(T.R8).background(T.Fill2)
        .testTag("jobOutput:${job.id}").padding(10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) {
                SelectableOutput { Text(job.label, style = T.Mono.copy(fontSize = 12.sp), color = T.Ink) }
            }
            Row(Modifier.clip(T.R4).clickable {
                clipboard.setText(AnnotatedString(job.label))
                copied = true
            }.testTag("jobCopy:${job.id}").padding(start = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(D.copy, null, tint = T.Text3, modifier = Modifier.size(12.dp))
                Text(if (copied) jobText("已复制", "Copied") else jobText("复制", "Copy"), color = T.Text3, fontSize = 11.sp)
            }
        }
        val current = observed ?: job
        current.detail?.let {
            SelectableOutput { Text(it, color = T.Text3, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
        error?.let { Text(jobText("实时输出流中断：$it", "live output stream interrupted: $it"), color = T.Err, fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp).testTag("jobOutputError:${job.id}")) }
        SelectableOutput {
            Column(Modifier.fillMaxWidth().heightIn(max = 288.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                Text(output.ifEmpty {
                    if (current.live && error == null) jobText("等待任务输出…", "Waiting for output…") else jobText("（无输出）", "(no output)")
                }, style = T.Mono.copy(fontSize = 12.sp), color = T.Text2, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
