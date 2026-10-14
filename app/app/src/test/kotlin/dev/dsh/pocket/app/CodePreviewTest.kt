package dev.dsh.pocket.app

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodePreviewTest {
    @Test fun assetPolicyOnlyServesExactOfflineNamesAndOneInitialDocument() {
        val assets = setOf("index.html", "preview.js", "preview.css", "grammar-ABC123.js")
        val base = "https://code-preview.invalid/code-preview/"
        assertEquals("index.html", codePreviewAssetFor(base + "index.html", true, assets))
        assertEquals("preview.js", codePreviewAssetFor(base + "preview.js", false, assets))
        assertEquals("grammar-ABC123.js", codePreviewAssetFor(base + "grammar-ABC123.js", false, assets))
        assertEquals("@meslo", codePreviewAssetFor("https://code-preview.invalid/font/meslo.ttf", false, assets))
        listOf(
            "https://example.com/preview.js", "http://code-preview.invalid/code-preview/preview.js",
            "file:///data/user/0/dev.dsh.pocket/files/private", "content://private/file",
            "javascript:alert(1)", "data:text/html,<script>alert(1)</script>",
            base + "../preview.js", base + "%2e%2e/preview.js", base + "preview.js?url=secret",
            base + "preview.js#fragment", base + "unlisted.js", base + "vendor/LICENSE",
            "https://code-preview.invalid.evil/code-preview/preview.js",
        ).forEach { assertNull(codePreviewAssetFor(it, false, assets), it) }
        assertNull(codePreviewAssetFor(base + "preview.js", true, assets))
        assertNull(codePreviewAssetFor("https://code-preview.invalid/font/meslo.ttf", true, assets))
    }

    @Test fun javascriptArgumentRoundTripsAttackStringsAndOriginalNewlines() {
        val path = "C:\\source\\attack.js"
        val source = "</script><img src=x onerror=alert(1)>\r\n\t\"; globalThis.attacked=true; //\n\u2028\u2029"
        val script = codePreviewDocumentScript(path, source)
        val json = script.removePrefix("window.codePreview.setDocument(").removeSuffix(");")
        val document = JSONObject(json)
        assertEquals(path, document.getString("path"))
        assertEquals(source, document.getString("text"))
        assertFalse(script.contains('\u2028'))
        assertFalse(script.contains('\u2029'))
    }

    @Test fun extensionRulesMatchOfficialTableRatherThanIconClassification() {
        assertEquals("javascript", previewCodeLanguage("dist/index.js"))
        assertEquals("kotlin", previewCodeLanguage("C:\\src\\Main.KT"))
        assertEquals("powershell", previewCodeLanguage("scripts/main.PS1"))
        assertEquals("dotenv", previewCodeLanguage(".env"))
        assertEquals("groovy", previewCodeLanguage("build.gradle"))
        listOf("Makefile", "Dockerfile", ".gitignore", "README", "dir.py/unknown", "file.constructor", "file.lock")
            .forEach { assertNull(previewCodeLanguage(it), it) }
    }

}
