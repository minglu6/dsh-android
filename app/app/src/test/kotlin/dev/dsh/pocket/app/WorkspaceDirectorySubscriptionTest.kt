package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.PocketError
import dev.dsh.pocket.protocol.jsonOf
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class WorkspaceDirectorySubscriptionTest {
    @Test fun startsReadingOnlyAfterReadyAndReloadsOnChanges() = runBlocking {
        val order = mutableListOf<String>()
        var reads = 0
        observeWorkspaceDirectory(
            list = { order += "list"; jsonOf("version" to ++reads) },
            changes = flow {
                order += "watch"
                assertEquals(0, reads)
                emit(jsonOf("kind" to "ready"))
                emit(jsonOf("kind" to "change"))
                emit(jsonOf("kind" to "change"))
            },
            onListing = { order += "render" },
            onFailure = { throw AssertionError(it) },
        )
        assertEquals(listOf("watch", "list", "render", "list", "render", "list", "render"), order)
    }

    @Test fun retargetedAliasCannotRedirectReadsAwayFromTheWatchedDirectory() = runBlocking {
        var aliasTarget = "a"
        val files = mutableMapOf("a" to "original.txt", "b" to "unrelated.txt")
        val reads = mutableListOf<String>()
        val shown = mutableListOf<String?>()
        observeWorkspaceDirectory(
            list = { watchedPath ->
                val requested = watchedPath ?: "alias"
                val resolved = if (requested == "alias") aliasTarget else requested
                reads += resolved
                jsonOf("path" to resolved, "file" to files[resolved])
            },
            changes = flow {
                // Host resolved alias -> a when it opened the watch. The alias changes
                // before the initial ready frame reaches the phone.
                val watched = aliasTarget
                aliasTarget = "b"
                emit(jsonOf("kind" to "ready", "path" to watched))
                files["a"] = "new.txt"
                emit(jsonOf("kind" to "change", "path" to watched))
                files.remove("a")
                emit(jsonOf("kind" to "change", "path" to watched))
            },
            onListing = { shown += it["file"].str() },
            onFailure = { throw AssertionError(it) },
        )
        assertEquals(listOf("a", "a", "a"), reads)
        assertEquals(listOf("original.txt", "new.txt", null), shown)
    }

    @Test fun cancelledOldDirectoryCannotPublishItsNonCooperativeRead() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val late = CompletableDeferred<Unit>()
        var released = false
        var shown = "new-directory"
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            observeWorkspaceDirectory(
                list = {
                    started.complete(Unit)
                    withContext(NonCancellable) { late.await() }
                    jsonOf("path" to "old-directory")
                },
                changes = flow {
                    try { emit(jsonOf("kind" to "ready")); awaitCancellation() }
                    finally { released = true }
                },
                onListing = { shown = it["path"].str().orEmpty() },
                onFailure = { throw AssertionError(it) },
            )
        }
        started.await()
        old.cancel()
        late.complete(Unit)
        old.join()
        assertEquals("new-directory", shown)
        assertTrue(released)
    }

    @Test fun unsupportedWatchKeepsListingAndManualRefreshWithoutAnError() = runBlocking {
        var reads = 0
        var rendered = 0
        val unsupported = PocketError("workspace-file/watch-unsupported", "watch unsupported")
        repeat(2) {
            observeWorkspaceDirectory(
                list = { reads++; jsonOf("path" to ".") },
                changes = flow { throw unsupported },
                onListing = { rendered++ },
                onFailure = { throw AssertionError(it) },
            )
        }
        assertEquals(2, rendered)
        assertEquals(2, reads)
    }

    @Test fun watchFailureUsesDirectoryFailureCallbackWithoutReplacingTheLastListing() = runBlocking {
        val listing = jsonOf("path" to "src", "file" to "keep.txt")
        var shown: Any? = null
        var failure: Throwable? = null
        val error = PocketError("workspace-file/watch-ended", "Directory watch ended: src")
        observeWorkspaceDirectory(
            list = { listing },
            changes = flow {
                emit(jsonOf("kind" to "ready", "path" to "src"))
                throw error
            },
            onListing = { shown = it },
            onFailure = { failure = it },
        )
        assertSame(listing, shown)
        assertSame(error, failure)
    }

    @Test fun listingErrorCanRecoverOnNextInvalidation() = runBlocking {
        var reads = 0
        var errors = 0
        var listings = 0
        observeWorkspaceDirectory(
            list = { if (++reads == 1) error("temporary list failure") else jsonOf("path" to ".") },
            changes = flow { emit(jsonOf("kind" to "ready")); emit(jsonOf("kind" to "change")) },
            onListing = { listings++ },
            onFailure = { errors++ },
        )
        assertEquals(1, errors)
        assertEquals(1, listings)
    }
}
