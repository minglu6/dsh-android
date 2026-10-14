package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.FileReference
import dev.dsh.pocket.protocol.SessionReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MentionMenuTest {
    @Test fun failedFileLookupDoesNotDiscardSessions() = runBlocking {
        val session = SessionReference("@[Design](dsh-session:other)", "Design")
        val result = loadMentionCandidates(false,
            files = { error("file discovery unavailable") }, sessions = { listOf(session) })
        assertTrue(result.files.isEmpty())
        assertEquals(listOf(session), result.sessions)
    }

    @Test fun failedSessionLookupDoesNotDiscardFiles() = runBlocking {
        val file = FileReference("src/Main.kt", "file")
        val result = loadMentionCandidates(false,
            files = { listOf(file) }, sessions = { error("session discovery unavailable") })
        assertEquals(listOf(file), result.files)
        assertTrue(result.sessions.isEmpty())
    }

    @Test fun quotedQueryNeverRequestsSessions() = runBlocking {
        var requested = false
        val result = loadMentionCandidates(true, files = { emptyList() }, sessions = {
            requested = true
            emptyList()
        })
        assertFalse(requested)
        assertTrue(result.sessions.isEmpty())
    }

    @Test fun cancelledQueryDoesNotBecomeEmptySuccess() {
        assertFailsWith<CancellationException> {
            runBlocking {
                loadMentionCandidates(false,
                    files = { throw CancellationException("query changed") }, sessions = { emptyList() })
            }
        }
    }

    @Test fun directoryPickSettlesReferenceWhileDrillLeavesQuotedQueryOpen() {
        val row = mentionFileRow(FileReference("design docs", "directory"), false, true)!!
        assertEquals("design docs/", row.title)
        assertEquals("@\"design docs/\"", row.mention)
        assertEquals("@\"design docs/", row.drill)
        val forcedQuote = mentionFileRow(FileReference("src", "directory"), true, true)!!
        assertEquals("@\"src/\"", forcedQuote.mention)
        assertEquals("@\"src/", forcedQuote.drill)
    }

    @Test fun drilledBreadcrumbCanReturnToEveryAncestorAndRoot() {
        val crumbs = mentionCrumbs("design docs/nested/", true, true)
        assertEquals(listOf("", "design docs", "nested"), crumbs.map { it.label })
        assertEquals(listOf("@\"", "@\"design docs/", "@\"design docs/nested/"), crumbs.map { it.drill })
        assertTrue(mentionCrumbs("design docs/nested/", true, false).isEmpty())
    }

    @Test fun fileLocationOnlyNamesParentAndIsHiddenWhenBreadcrumbCarriesIt() {
        val file = FileReference("src/Main.kt", "file")
        assertEquals("src", mentionFileRow(file, false, true)?.description)
        assertEquals(null, mentionFileRow(file, false, false)?.description)
        assertEquals(null, mentionFileRow(FileReference("README.md"), false, true)?.description)
    }
}
