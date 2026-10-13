package dev.dsh.pocket.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceDirectoryStateTest {
    private val root = WorkspaceDirectoryEntry("/", "/", false)
    private val home = WorkspaceDirectoryEntry("home", "/home", false)
    private val alpha = WorkspaceDirectoryEntry("alpha", "/home/alpha", false)
    private val beta = WorkspaceDirectoryEntry("beta", "/home/beta", false)
    private val hidden = WorkspaceDirectoryEntry(".secret", "/home/.secret", true)
    private fun level(entry: WorkspaceDirectoryEntry, entries: List<WorkspaceDirectoryEntry> = emptyList()) =
        WorkspaceDirectoryLevel(entry.path, home.path,
            if (entry == home) listOf(root, home) else listOf(root, home, entry), entries, false)
    private val homeLevel get() = level(home, listOf(alpha, beta, hidden))
    private suspend fun settled(state: WorkspaceDirectoryState) = withTimeout(3000) {
        while (state.loading) yield()
    }

    @Test fun selectionAndBreadcrumbNavigationKeepParentAndTargetTogether() = runBlocking {
        val listings = mapOf(home.path to homeLevel, alpha.path to level(alpha), beta.path to level(beta))
        val state = WorkspaceDirectoryState(this) { listings.getValue(it ?: home.path) }
        state.navigate(); settled(state)
        state.navigate(alpha.path, state.parent); settled(state)
        assertEquals(home.path, state.parent?.path)
        assertEquals(alpha.path, state.child?.path)
        assertEquals(alpha.path, state.target)
        state.navigate(home.path); settled(state)
        assertNull(state.selected)
        assertNull(state.child)
        assertEquals(home.path, state.target)
        state.dispose()
    }

    @Test fun supersededNonCooperativeResponseCannotReplaceNewSelection() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stale = CompletableDeferred<WorkspaceDirectoryLevel>()
        val state = WorkspaceDirectoryState(this) {
            when (it) {
                alpha.path -> { started.complete(Unit); withContext(NonCancellable) { stale.await() } }
                beta.path -> level(beta)
                else -> homeLevel
            }
        }
        state.navigate(); settled(state)
        state.navigate(alpha.path, state.parent); started.await()
        state.navigate(beta.path, state.parent); settled(state)
        stale.complete(level(alpha)); yield()
        assertEquals(beta.path, state.target)
        assertEquals(beta.path, state.child?.path)
        assertFalse(state.loading)
        assertNull(state.error)
        state.dispose()
    }

    @Test fun unreadableTargetKeepsLastReadableFrameAndDraftForCorrection() = runBlocking {
        val state = WorkspaceDirectoryState(this) {
            if (it == alpha.path) error("Access denied") else homeLevel
        }
        state.navigate(); settled(state)
        state.beginEdit(); state.edit(alpha.path)
        state.navigate(alpha.path); settled(state)
        assertEquals(home.path, state.target)
        assertEquals(alpha.path, state.draft)
        assertEquals("Access denied", state.error)
        state.dispose()
    }

    @Test fun unreadableParentDoesNotPreventOpeningReadableDirectory() = runBlocking {
        val state = WorkspaceDirectoryState(this) {
            if (it == home.path) error("Access denied") else level(alpha)
        }
        state.navigate(alpha.path); settled(state)
        assertEquals(alpha.path, state.target)
        assertEquals(alpha.path, state.parent?.path)
        assertNull(state.child)
        assertNull(state.error)
        state.dispose()
    }

    @Test fun dotPrefixRevealsHiddenAndPrefixMissRestoresVisibleRows() = runBlocking {
        val state = WorkspaceDirectoryState(this) { if (it == hidden.path) level(hidden) else homeLevel }
        state.navigate(); settled(state)
        state.beginEdit(); state.edit("/home/.s")
        assertEquals(listOf(hidden), state.visibleEntries(homeLevel))
        state.edit("/home/not-present")
        assertEquals(listOf(alpha, beta), state.visibleEntries(homeLevel))
        state.navigate(hidden.path, state.parent); settled(state)
        assertEquals(hidden.path, state.target)
        assertTrue(hidden in state.visibleEntries(homeLevel))
        state.dispose()
    }

    @Test fun imeSelectionUpdatesCannotSupersedeSubmittedPathOrReopenClosedEditor() = runBlocking {
        val pending = CompletableDeferred<WorkspaceDirectoryLevel>()
        val started = CompletableDeferred<Unit>()
        val state = WorkspaceDirectoryState(this) {
            if (it == alpha.path) { started.complete(Unit); pending.await() } else homeLevel
        }
        state.navigate(); settled(state)
        state.beginEdit(); state.edit(alpha.path)
        state.navigate(alpha.path); started.await()
        state.edit(alpha.path)
        assertTrue(state.loading)
        pending.complete(level(alpha)); settled(state)
        assertEquals(alpha.path, state.target)
        assertNull(state.draft)
        state.edit("/home/late-ime-value")
        assertNull(state.draft)
        assertEquals(alpha.path, state.target)
        state.dispose()
    }

    @Test fun typedDirectoryMovesPanesWithoutCommittingTheDraft() = runBlocking {
        val state = WorkspaceDirectoryState(this) { if (it == "/home/alpha/") level(alpha) else homeLevel }
        state.navigate(); settled(state)
        state.beginEdit(); state.edit("/home/alpha/part")
        withTimeout(3000) { while (state.child?.path != alpha.path) { delay(5) } }
        assertEquals(home.path, state.parent?.path)
        assertEquals("/home/alpha/part", state.draft)
        state.cancelEdit()
        assertNull(state.draft)
        assertEquals(alpha.path, state.target)
        state.dispose()
    }
}
