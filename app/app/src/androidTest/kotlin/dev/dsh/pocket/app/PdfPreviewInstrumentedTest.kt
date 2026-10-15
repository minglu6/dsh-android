package dev.dsh.pocket.app

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Robolectric 4.14 has no PdfDocument/PdfRenderer native shadows; these exercise Android PDFium. */
@RunWith(AndroidJUnit4::class)
class PdfPreviewInstrumentedTest {
    private val cacheDir get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test fun pdfRendersPagesBeyondEightWithBoundedBitmapsAndClosesCache() {
        val file = File.createTempFile("preview-test-", ".pdf", cacheDir)
        try {
            file.writeBytes(pdfFixture(pageCount = 12, width = 2000, height = 3000))
            PreviewPdf(file).use { document ->
                assertEquals(12, document.pageCount)
                val bitmap = document.render(11)
                try {
                    assertTrue(bitmap.width <= 1440)
                    assertTrue(bitmap.height <= 2048)
                    assertEquals(Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                } finally {
                    bitmap.recycle()
                }
            }
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test(timeout = 15_000) fun cancellingPdfLifetimeClosesDescriptorAndRemovesItsCache() = runBlocking<Unit> {
        val bytes = pdfFixture(pageCount = 1, width = 100, height = 100)
        val opened = CompletableDeferred<PreviewPdf>()
        val lifetime = launch { holdPreviewPdf(bytes, cacheDir) { opened.complete(it) } }
        try {
            val document = opened.await()
            assertTrue(document.file.exists())
            lifetime.cancelAndJoin()
            assertFalse(document.file.exists())
            assertThrows(IllegalStateException::class.java) { document.render(0) }
        } finally {
            lifetime.cancelAndJoin()
        }
    }

    @Test(timeout = 15_000) fun cancellingBorrowedPdfClosesRendererWithoutDeletingOwnersFile() = runBlocking<Unit> {
        val file = File.createTempFile("office-preview-test-", ".pdf", cacheDir)
        file.writeBytes(pdfFixture(pageCount = 1, width = 100, height = 100))
        val opened = CompletableDeferred<PreviewPdf>()
        val lifetime = launch { holdPreviewPdf(file) { opened.complete(it) } }
        try {
            val document = opened.await()
            val bitmap = document.render(0)
            try { assertEquals(Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) }
            finally { bitmap.recycle() }
            lifetime.cancelAndJoin()
            assertTrue(file.exists())
            assertThrows(IllegalStateException::class.java) { document.render(0) }
            PreviewPdf(file, deleteOnClose = false).use { assertEquals(1, it.pageCount) }
        } finally {
            lifetime.cancelAndJoin()
            file.delete()
        }
    }

    private fun pdfFixture(pageCount: Int, width: Int, height: Int): ByteArray {
        val pdf = PdfDocument()
        try {
            repeat(pageCount) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                page.canvas.drawColor(if (index == pageCount - 1) Color.RED else Color.WHITE)
                pdf.finishPage(page)
            }
            return ByteArrayOutputStream().use { output -> pdf.writeTo(output); output.toByteArray() }
        } finally {
            pdf.close()
        }
    }
}
