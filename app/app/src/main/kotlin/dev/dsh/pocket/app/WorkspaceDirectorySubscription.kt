package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.PocketError
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Read after watch-ready so changes during the initial listing cannot be missed. */
internal suspend fun observeWorkspaceDirectory(
    list: suspend (watchedPath: String?) -> JsonElement,
    changes: Flow<JsonObject>?,
    onListing: (JsonElement) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    var watchedPath: String? = null
    suspend fun reload() {
        try {
            val result = list(watchedPath)
            currentCoroutineContext().ensureActive()
            onListing(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            onFailure(error)
        }
    }
    if (changes == null) {
        reload()
        return
    }
    var listed = false
    try {
        changes.collect { frame ->
            if (frame["kind"].str() == "ready" || frame["kind"].str() == "change") {
                watchedPath = frame["path"].str() ?: watchedPath
                reload()
                listed = true
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        // An unsupported watcher must not remove ordinary browsing or manual refresh.
        if (!listed) reload()
        currentCoroutineContext().ensureActive()
        if ((error as? PocketError)?.code != "workspace-file/watch-unsupported") onFailure(error)
    }
}
