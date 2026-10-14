package dev.dsh.pocket.protocol

import kotlin.test.*

class WorkspaceChangesTest {
    private fun j(value: String) = PocketJson.parseToJsonElement(value)
    private val summary = """{"turn":7,"cwd":"/work","total":12,"added":150,"deleted":80,"files":[
        {"path":"z.bin","display":"z.bin","added":0,"deleted":0,"binary":true},
        {"path":"../a.kt","display":"../a.kt","added":4,"deleted":2},
        {"path":"big.dat","display":"big.dat","added":0,"deleted":0,"oversized":true}
    ]}"""

    @Test fun preservesHostTotalsOrderPathsAndUncountedFileFlags() {
        val parsed = assertNotNull(WorkspaceChangesSummary.parse(j(summary)))
        assertEquals(7, parsed.turn)
        assertEquals("/work", parsed.cwd)
        assertEquals(12, parsed.total)
        assertEquals(150, parsed.added)
        assertEquals(80, parsed.deleted)
        assertEquals(listOf("z.bin", "../a.kt", "big.dat"), parsed.files.map { it.path })
        assertTrue(parsed.files[0].binary)
        assertFalse(parsed.files[0].oversized)
        assertEquals(WorkspaceChangedFile("../a.kt", "../a.kt", 4, 2), parsed.files[1])
        assertTrue(parsed.files[2].oversized)
    }

    @Test fun missingOrEmptySummaryDoesNotBecomeZeroFileCard() {
        for (value in listOf("null", "{}", "[]", """{"turn":1,"cwd":"/work","total":0,"added":0,"deleted":0,"files":[]}""")) {
            assertNull(WorkspaceChangesSummary.parse(j(value)))
        }
        assertNull(WorkspaceChangesSummary.parse(null))
        // A list cap can omit every file; the full Host totals still remain authoritative.
        val capped = assertNotNull(WorkspaceChangesSummary.parse(j("""{"turn":1,"cwd":"/work","total":2,"added":8,"deleted":4,"files":[]}""")))
        assertEquals(2, capped.total)
        assertTrue(capped.files.isEmpty())
    }

    @Test fun malformedEntryRejectsWholeSummaryInsteadOfShiftingDiffIndices() {
        assertNull(WorkspaceChangesSummary.parse(j(summary.replace("\"path\":\"z.bin\"", "\"path\":null"))))
        assertNull(WorkspaceChangesSummary.parse(j(summary.replace("\"display\":\"../a.kt\"", "\"display\":12"))))
        assertNull(WorkspaceChangesSummary.parse(j(summary.replace("\"added\":4", "\"added\":-1"))))
        assertNull(WorkspaceChangesSummary.parse(j(summary.replace("\"total\":12", "\"total\":2147483648"))))
        assertNull(WorkspaceChangesSummary.parse(j(summary.replace("\"total\":12", "\"total\":2"))))
    }

    @Test fun textDiffKeepsPrefixesEmptyLinesExistenceAndCoarseFlag() {
        val parsed = assertNotNull(WorkspaceFileDiff.parse(j("""{
            "kind":"text","path":"src/a.kt","display":"src/a.kt","before":false,"after":true,"coarse":true,
            "hunks":[{"oldStart":1,"oldLines":0,"newStart":5,"newLines":3,"lines":[" context","-old","+new","+", ""]}]
        }""")))
        assertFalse(parsed.before)
        assertTrue(parsed.after)
        assertTrue(parsed.coarse)
        assertEquals(WorkspaceDiffHunk(1, 5, listOf(" context", "-old", "+new", "+", "")), parsed.hunks.single())
        val deleted = assertNotNull(WorkspaceFileDiff.parse(j("""{"kind":"text","path":"gone","display":"gone","before":true,"after":false,"coarse":false,"hunks":[]}""")))
        assertTrue(deleted.before)
        assertFalse(deleted.after)
        assertTrue(deleted.hunks.isEmpty())
    }

    @Test fun binaryAndOversizedDiffsDoNotRequireTextFields() {
        for (kind in listOf("binary", "oversized")) {
            val parsed = assertNotNull(WorkspaceFileDiff.parse(j("""{"kind":"$kind","path":"a","display":"a"}""")))
            assertEquals(kind, parsed.kind)
            assertTrue(parsed.hunks.isEmpty())
        }
    }

    @Test fun missingUnknownAndMalformedDiffsRemainUnavailable() {
        for (value in listOf("null", "{}", """{"kind":"other","path":"a","display":"a"}""", """{"kind":"text","path":"a","display":"a"}""")) {
            assertNull(WorkspaceFileDiff.parse(j(value)))
        }
        assertNull(WorkspaceFileDiff.parse(j("""{"kind":"text","path":"a","display":"a","before":true,"after":true,"coarse":false,
            "hunks":[{"oldStart":1,"newStart":1,"lines":["+valid",null]}]}""")))
    }
}
