package dev.dsh.pocket.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.PocketClient
import dev.dsh.pocket.protocol.PocketError
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.int
import dev.dsh.pocket.protocol.long
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.Base64

internal class OfficePreviewDownload(
    val file: File,
    val missingFonts: List<String>,
    val version: String,
    val generation: String,
) : AutoCloseable {
    override fun close() { file.delete() }
}

internal suspend fun loadOfficePreview(
    client: PocketClient, sessionId: String, path: String, cacheDir: File,
): OfficePreviewDownload {
    var download: OfficePreviewDownload? = null
    try {
        return client.withOfficePreview(sessionId, path) { reader ->
            downloadOfficePreview(reader.metadata, cacheDir, reader::read).also { download = it }
        }
    } catch (failure: Throwable) {
        download?.close()
        throw failure
    }
}

/** Write each bounded page once; ownership transfers only after the final page has been checked. */
internal suspend fun downloadOfficePreview(
    metadata: JsonElement,
    cacheDir: File,
    read: suspend (Long, Int) -> JsonElement,
): OfficePreviewDownload {
    var temporary: File? = null
    try {
        return withContext(Dispatchers.IO) {
            val total = metadata["bytes"].long() ?: invalidOfficeResponse()
            if (total !in 1..100L * 1024 * 1024) invalidOfficeResponse()
            val version = metadata["version"].str() ?: invalidOfficeResponse()
            val generation = metadata["generation"].str() ?: invalidOfficeResponse()
            val absolutePath = metadata["absolutePath"].str() ?: invalidOfficeResponse()
            val chunk = metadata["chunkBytes"].int() ?: invalidOfficeResponse()
            if (chunk !in 1..128 * 1024) invalidOfficeResponse()
            val fonts = metadata["missingFonts"].arr()?.map { it.str() ?: invalidOfficeResponse() }
                ?: invalidOfficeResponse()
            val file = File.createTempFile("office-preview-", ".pdf", cacheDir).also { temporary = it }
            file.outputStream().use { output ->
                var offset = 0L
                while (offset < total) {
                    currentCoroutineContext().ensureActive()
                    val length = minOf(chunk.toLong(), total - offset).toInt()
                    val page = read(offset, length)
                    if (page["offset"].long() != offset || page["bytes"].long() != total ||
                        page["version"].str() != version || page["generation"].str() != generation ||
                        page["absolutePath"].str() != absolutePath || page["encoding"].str() != "base64") invalidOfficeResponse()
                    val encoded = page["data"].str() ?: invalidOfficeResponse()
                    if (encoded.length > ((length + 2) / 3) * 4) invalidOfficeResponse()
                    val data = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { invalidOfficeResponse() }
                    if (data.isEmpty() || data.size > length) invalidOfficeResponse()
                    val next = offset + data.size
                    if (page["eof"].bool() != (next == total)) invalidOfficeResponse()
                    output.write(data)
                    offset = next
                }
            }
            currentCoroutineContext().ensureActive()
            OfficePreviewDownload(file, fonts, version, generation)
        }
    } catch (failure: Throwable) {
        temporary?.delete()
        throw failure
    }
}

private fun invalidOfficeResponse(): Nothing = throw PocketError("document-render/failed", "Invalid Office preview response")
private fun officeText(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

/** Exact official conversionErrorKey mapping; engine diagnostic messages never reach the preview. */
internal fun officePreviewErrorMessage(failure: Throwable): String {
    val error = failure as? PocketError
    val reason = when (error?.code) {
        "unknown-op" -> "unavailable"
        "timeout" -> "timeout"
        else -> error?.details?.get("reason")?.str()
    }
    return when (reason) {
        "input-too-large", "output-too-large" -> officeText("Office 文件或转换后的 PDF 超过预览大小上限，请缩小文件或调整预览配置。", "The Office file or converted PDF exceeds the preview size limit. Reduce the file size or adjust the preview configuration.")
        "invalid-document", "unsupported-format" -> officeText("无法预览此 Office 文件。文件可能已损坏、受密码保护，或与扩展名不符。", "This Office file cannot be previewed. It may be damaged, password protected, or have the wrong extension.")
        "timeout" -> officeText("Office 转换超时，请重试。", "Office conversion timed out. Try again.")
        "unavailable" -> officeText("Office 预览不可用。请在运行 DeepSeek Harness 的主机上启用文档预览服务。", "Office previews are unavailable. Enable the document preview service on the computer running DeepSeek Harness.")
        "busy" -> officeText("Office 预览任务较多，请稍后重试。", "Office preview is busy. Try again shortly.")
        "source-changed" -> officeText("文件在读取时已更改，请重新打开预览。", "The file changed while being read. Reopen the preview.")
        else -> officeText("Office 转换失败，未生成可用的 PDF。请检查该文件后重试。", "Office conversion did not produce a usable PDF. Check the file and try again.")
    }
}

/** Desktop's warning action and anchored font details, with a bounded phone-width popup. */
@Composable
internal fun OfficeFontWarning(missingFonts: List<String>) {
    if (missingFonts.isEmpty()) return
    var expanded by remember(missingFonts) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = !expanded }) {
            Icon(painterResource(R.drawable.dsh_office_warning),
                officeText("缺失 ${missingFonts.size} 种字体，点击查看", "Missing fonts: ${missingFonts.size}. Click to view."),
                Modifier.size(16.dp), tint = T.WarnText)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = T.Surface) {
            Column(Modifier.widthIn(max = 340.dp).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(officeText("缺失的字体", "Missing fonts"), Modifier.weight(1f),
                        color = T.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { expanded = false }, modifier = Modifier.size(32.dp)) {
                        Icon(D.close, officeText("关闭字体详情", "Close font details"), Modifier.size(16.dp), tint = T.Text2)
                    }
                }
                Text(officeText("本次预览无法使用以下字体，预览中的文字和排版可能与原文档不同。",
                    "These fonts are unavailable for this preview. Text and layout may differ from the original document."),
                    Modifier.padding(top = 8.dp, bottom = 16.dp), color = T.Text2, fontSize = 13.sp)
                Text(officeText("${missingFonts.size} 种字体", "Fonts: ${missingFonts.size}"), color = T.Text3, fontSize = 12.sp)
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    missingFonts.forEach { font ->
                        HorizontalDivider(color = T.Border, thickness = 0.5.dp)
                        Text(font, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = T.Ink, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
