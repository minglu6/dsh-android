package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.WorkspaceDiffHunk
import dev.dsh.pocket.protocol.WorkspaceFileDiff
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

    @Test fun comparisonArgumentPreservesSnapshotHunksAndEscapesScriptPayloads() {
        val path = "src/</script>\\\"file.json"
        val lines = listOf("-\\\"old\\\": 1,", "+\\\"new\\\": 2,\u2028\u2029", "+<img src=x onerror=alert(1)>", "+")
        val diff = WorkspaceFileDiff("text", path, path, before = false, coarse = true,
            hunks = listOf(WorkspaceDiffHunk(4, 8, lines)))
        val script = codeComparisonDocumentScript(diff)
        val value = JSONObject(script.removePrefix("window.codePreview.setComparison(").removeSuffix(");"))
        assertEquals(path, value.getString("path"))
        assertFalse(value.getBoolean("before"))
        assertEquals(true, value.getBoolean("coarse"))
        val hunk = value.getJSONArray("hunks").getJSONObject(0)
        assertEquals(4, hunk.getInt("oldStart"))
        assertEquals(8, hunk.getInt("newStart"))
        assertEquals(lines, (0 until lines.size).map { hunk.getJSONArray("lines").getString(it) })
        assertFalse(script.contains('\u2028'))
        assertFalse(script.contains('\u2029'))
    }

    @Test fun oneSidedCopyIsSourceButMixedCopyIsARealMultiHunkPatch() {
        val additions = WorkspaceFileDiff("text", "src/a.ts", "a.ts", before = false,
            hunks = listOf(WorkspaceDiffHunk(0, 1, listOf("+const a = 1;", "+", "+const b = 2;"))))
        assertEquals("const a = 1;\n\nconst b = 2;", codeComparisonCopyText(additions))
        val deletions = additions.copy(before = true, after = false,
            hunks = listOf(WorkspaceDiffHunk(8, 0, listOf("-old", "-"))))
        assertEquals("old\n", codeComparisonCopyText(deletions))
        val mixed = additions.copy(before = true, hunks = listOf(
            WorkspaceDiffHunk(4, 8, listOf(" context", "-old", "+new", " next")),
            WorkspaceDiffHunk(20, 24, listOf("-gone", "+replacement")),
        ))
        assertEquals("--- a/src/a.ts\n+++ b/src/a.ts\n@@ -4,3 +8,3 @@\n context\n-old\n+new\n next\n@@ -20,1 +24,1 @@\n-gone\n+replacement\n",
            codeComparisonCopyText(mixed))
        assertEquals("", codeComparisonCopyText(mixed.copy(hunks = emptyList())))
    }

}
