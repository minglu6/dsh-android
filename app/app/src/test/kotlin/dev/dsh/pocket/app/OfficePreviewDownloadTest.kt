package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.PocketError
import dev.dsh.pocket.protocol.jsonOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfficePreviewDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = ByteArray(128 * 1024 * 3 + 7) { (it % 251).toByte() }
    private fun metadata() = jsonOf("bytes" to bytes.size, "absolutePath" to "/project/a.docx",
        "version" to "v1", "generation" to "g1", "chunkBytes" to 128 * 1024, "missingFonts" to listOf("Aptos"))
    private fun page(offset: Long, length: Int): JsonObject {
        val end = minOf(bytes.size, offset.toInt() + length)
        return jsonOf("bytes" to bytes.size, "absolutePath" to "/project/a.docx", "offset" to offset,
            "version" to "v1", "generation" to "g1", "encoding" to "base64", "eof" to (end == bytes.size),
            "data" to Base64.getEncoder().encodeToString(bytes.copyOfRange(offset.toInt(), end)))
    }

    @Test fun writesAllPagesOnceAndKeepsFileUntilOwnerCloses() = runBlocking {
        val offsets = mutableListOf<Long>()
        val download = downloadOfficePreview(metadata(), temporary.root) { offset, length ->
            assertTrue(length in 1..128 * 1024)
            offsets += offset
            page(offset, length)
        }
        assertEquals(listOf(0L, 131072L, 262144L, 393216L), offsets)
        assertContentEquals(bytes, download.file.readBytes())
        assertEquals("v1", download.version)
        assertEquals("g1", download.generation)
        assertEquals(listOf("Aptos"), download.missingFonts)
        assertTrue(download.file.exists())
        download.close()
        download.close()
        assertFalse(download.file.exists())
    }

    @Test fun incompleteAndMismatchedPagesAreRejectedAndDeleted() = runBlocking {
        val invalid = listOf(
            "offset" to jsonOf("offset" to 1),
            "version" to jsonOf("version" to "v2"),
            "generation" to jsonOf("generation" to "g2"),
            "absolutePath" to jsonOf("absolutePath" to "/other/a.docx"),
            "bytes" to jsonOf("bytes" to bytes.size + 1),
            "eof" to jsonOf("eof" to true),
            "encoding" to jsonOf("encoding" to "utf8"),
            "data" to jsonOf("data" to ""),
            "data" to jsonOf("data" to "%%invalid%%"),
        )
        for ((field, replacement) in invalid) {
            assertFailsWith<PocketError> {
                downloadOfficePreview(metadata(), temporary.root) { offset, length ->
                    JsonObject(page(offset, length).toMutableMap().apply { put(field, replacement.getValue(field)) })
                }
            }
            assertTrue(temporary.root.listFiles().orEmpty().isEmpty(), field)
        }
    }

    @Test fun finalPageMustDeclareEof() = runBlocking {
        assertFailsWith<PocketError> {
            downloadOfficePreview(metadata(), temporary.root) { offset, length ->
                JsonObject(page(offset, length).toMutableMap().apply { put("eof", jsonOf("v" to false).getValue("v")) })
            }
        }
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationAndReadFailureRemovePartialFiles() = runBlocking {
        for (cancelled in listOf(true, false)) {
            val reader: suspend (Long, Int) -> JsonElement = { offset, length ->
                if (offset > 0) {
                    if (cancelled) throw CancellationException("preview closed")
                    throw PocketError("transport", "disconnected")
                }
                page(offset, length)
            }
            if (cancelled) assertFailsWith<CancellationException> { downloadOfficePreview(metadata(), temporary.root, reader) }
            else assertFailsWith<PocketError> { downloadOfficePreview(metadata(), temporary.root, reader) }
            assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
        }
    }
}
