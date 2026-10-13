package dev.dsh.pocket.app

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FilePreviewTest {
    // Actual PDF page rendering and descriptor lifetime are covered by PdfPreviewInstrumentedTest.
    @Test fun htmlAllowsEmbeddedSubresourcesButNeverNavigationOrExternalResources() {
        assertTrue(allowStaticHtmlResource(false, "data"))
        assertTrue(allowStaticHtmlResource(false, "DATA"))
        assertFalse(allowStaticHtmlResource(true, "data"))
        listOf("http", "https", "file", "content", "javascript", "blob", null).forEach { scheme ->
            assertFalse(allowStaticHtmlResource(false, scheme), "Unexpected scheme: $scheme")
        }
    }

    @Test fun zoomingOutAndRotatingClampPreviouslyPannedImage() {
        val viewport = IntSize(400, 800)
        val edgeAtEight = clampImageTranslation(Offset(5000f, -5000f), 8f, viewport)
        assertEquals(Offset(1400f, -2800f), edgeAtEight)
        assertEquals(Offset(200f, -400f), clampImageTranslation(edgeAtEight, 2f, viewport))
        assertEquals(Offset.Zero, clampImageTranslation(edgeAtEight, 1f, viewport))
        assertEquals(Offset(100f, -200f), clampImageTranslation(edgeAtEight, 2f, IntSize(200, 400)))
        assertEquals(Offset.Zero, clampImageTranslation(edgeAtEight, 2f, IntSize.Zero))
    }


    @Test fun officialArtworkRendersWithoutFallbackInEveryCategory() {
        val categories = listOf("code", "excel", "folder", "html", "image", "markdown", "other", "pdf", "ppt", "video", "word",
            "angular", "c", "clojure", "cmake", "cpp", "csharp", "css", "dart", "docker", "elixir", "env", "erlang", "flutter",
            "git", "go", "graphql", "haskell", "ini", "java", "javascript", "json", "kotlin", "lua", "makefile", "node",
            "objective-c", "perl", "php", "powershell", "protobuf", "python", "r", "react", "ruby", "rust", "scala", "shell",
            "solidity", "sql", "svelte", "swift", "toml", "typescript", "vue", "wasm", "xml", "yaml", "zig")
        categories.forEach { category ->
            val bitmap = android.graphics.Bitmap.createBitmap(28, 28, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).drawPicture(fileTypePicture(category))
            val pixels = IntArray(28 * 28)
            bitmap.getPixels(pixels, 0, 28, 0, 0, 28, 28)
            assertTrue(pixels.any { Color.alpha(it) != 0 }, category)
            bitmap.recycle()
        }
    }

    @Test fun officeAndArchivesNeverPretendToBeText() {
        assertEquals(PreviewKind.Unsupported, previewKindFor("report.DOCX"))
        assertEquals(PreviewKind.Unsupported, previewKindFor("table.xlsx"))
        assertEquals(PreviewKind.Unsupported, previewKindFor("archive.zip"))
        assertEquals(PreviewKind.Text, previewKindFor("notes.md"))
        assertEquals(PreviewKind.Pdf, previewKindFor("report.PDF"))
    }

    @Test fun tablePreservesQuotedDelimitersNewlinesAndEmptyCells() {
        assertEquals(
            listOf(listOf("name", "description", ""), listOf("A,B", "first\nsecond \"quoted\"", "")),
            parseDelimitedPreview("name,description,\r\n\"A,B\",\"first\nsecond \"\"quoted\"\"\",\r\n", ','),
        )
        assertEquals(listOf(listOf("a", "b")), parseDelimitedPreview("a\tb", '\t'))
        assertEquals(listOf(listOf("")), parseDelimitedPreview("\"\"", ','))
        assertEquals(emptyList(), parseDelimitedPreview("", ','))
    }

    @Test fun malformedAndOversizedGridsFailWithoutSilentTruncation() {
        assertFailsWith<IllegalArgumentException> { parseDelimitedPreview("a,\"unfinished", ',') }
        assertFailsWith<IllegalArgumentException> { parseDelimitedPreview("x,".repeat(128), ',') }
    }

    @Test fun officialFilenameRulesPrecedeExtensionRules() {
        assertEquals("node", classifyFileType("/project/package.json"))
        assertEquals("docker", classifyFileType("Dockerfile.dev"))
        assertEquals("angular", classifyFileType("src/home.component.html"))
        assertEquals("env", classifyFileType(".env.production"))
        assertEquals("markdown", classifyFileType("README"))
        assertEquals("excel", classifyFileType("TABLE.CSV"))
        assertEquals("kotlin", classifyFileType("C:\\project\\Main.kt"))
        assertEquals("other", classifyFileType("unknown.blob"))
    }

}
