package dev.dsh.pocket.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkbookPreviewTest {
    private fun page(bytes: ByteArray, offset: Int, length: Int, version: String = "v1", path: String = "/work/book.xlsx"): JsonObject {
        val end = minOf(bytes.size, offset + length)
        return buildJsonObject {
            put("absolutePath", path); put("version", version); put("bytes", bytes.size)
            put("offset", offset); put("eof", end == bytes.size); put("encoding", "base64")
            put("data", Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end)))
        }
    }

    @Test fun rangeReadsPreserveAllBytesAndConfirmFinalVersion() = runBlocking {
        val bytes = ByteArray(WorkbookChunkBytes * 2 + 19) { (it % 251).toByte() }
        val calls = mutableListOf<Pair<Int, Int>>()
        val result = readWorkbookChunks { offset, length -> calls.add(offset to length); page(bytes, offset, length) }
        assertContentEquals(bytes, result)
        assertEquals(listOf(0 to WorkbookChunkBytes, WorkbookChunkBytes to WorkbookChunkBytes,
            WorkbookChunkBytes * 2 to WorkbookChunkBytes, bytes.size to 1), calls)
    }

    @Test fun sourceIdentityMustRemainStableIncludingFinalProbe() = runBlocking<Unit> {
        val bytes = ByteArray(WorkbookChunkBytes + 2)
        for (change in listOf("version", "absolutePath", "bytes")) {
            assertFailsWith<IOException> {
                readWorkbookChunks { offset, length ->
                    val value = page(bytes, offset, length)
                    if (offset == 0) value else buildJsonObject {
                        value.forEach { (key, item) -> put(key, item) }
                        when (change) { "version" -> put("version", "v2"); "absolutePath" -> put("absolutePath", "/other/book.xlsx"); else -> put("bytes", bytes.size + 1) }
                    }
                }
            }
        }
        assertFailsWith<IOException> {
            readWorkbookChunks { offset, length -> page(bytes, offset, length, if (offset == bytes.size) "v2" else "v1") }
        }
    }

    @Test fun oversizeAndMalformedRangesFailRatherThanTruncate() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        for (fault in listOf("size", "offset", "eof", "encoding", "data", "empty", "extra")) {
            assertFailsWith<IOException> {
                readWorkbookChunks { _, _ -> buildJsonObject {
                    page(bytes, 0, 3).forEach { (key, value) -> put(key, value) }
                    when (fault) {
                        "size" -> put("bytes", WorkbookMaxBytes + 1)
                        "offset" -> put("offset", 1)
                        "eof" -> put("eof", false)
                        "encoding" -> put("encoding", "utf8")
                        "data" -> put("data", "!!!")
                        "empty" -> put("data", "")
                        "extra" -> put("data", Base64.getEncoder().encodeToString(ByteArray(WorkbookChunkBytes + 1)))
                    }
                } }
            }
        }
    }

    @Test fun cancellationIsNotConvertedIntoParserFailure() = runBlocking<Unit> {
        assertFailsWith<CancellationException> {
            readWorkbookChunks { _, _ -> throw CancellationException("closed") }
        }
    }

    @Test fun localResourcesAreExactAndNeverExposeAnotherDocumentOrHost() {
        val assets = setOf("index.html", "preview.js", "preview.css")
        val base = "https://workbook-preview.invalid/workbook-preview/"
        val document = "https://workbook-preview.invalid/document/1111"
        assertEquals("index.html", workbookPreviewResourceFor(base + "index.html", true, assets, document))
        assertEquals("preview.js", workbookPreviewResourceFor(base + "preview.js", false, assets, document))
        assertEquals("@document", workbookPreviewResourceFor(document, false, assets, document))
        listOf(document + "?token=x", document + "/other", "https://workbook-preview.invalid/document/2222",
            "https://example.com/preview.js", "file:///data/private", "content://private/file",
            "javascript:alert(1)", "data:text/html,attack", base + "../preview.js", base + "%2e%2e/preview.js",
            base + "preview.js?secret=x", base + "preview.js#fragment", base + "missing.js",
            "https://workbook-preview.invalid.evil/workbook-preview/preview.js",
        ).forEach { assertNull(workbookPreviewResourceFor(it, false, assets, document), it) }
        assertNull(workbookPreviewResourceFor(document, true, assets, document))
        assertNull(workbookPreviewResourceFor(base + "preview.js", true, assets, document))
    }
}
