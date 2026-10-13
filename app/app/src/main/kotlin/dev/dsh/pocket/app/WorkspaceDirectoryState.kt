package dev.dsh.pocket.app

import androidx.compose.runtime.*
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement

internal data class WorkspaceDirectoryEntry(val name: String, val path: String, val hidden: Boolean)

internal data class WorkspaceDirectoryLevel(
    val path: String,
    val home: String,
    val crumbs: List<WorkspaceDirectoryEntry>,
    val entries: List<WorkspaceDirectoryEntry>,
    val truncated: Boolean,
) {
    val separator get() = if ('\\' in home) '\\' else '/'
    val directory get() = if (path.endsWith(separator)) path else "$path$separator"
    fun displayCrumbs(): List<WorkspaceDirectoryEntry> {
        val index = crumbs.indexOfFirst { it.path == home }
        return if (index < 0) crumbs else crumbs.drop(index)
    }

    companion object {
        fun parse(value: JsonElement): WorkspaceDirectoryLevel {
            fun entries(key: String) = value[key].arr().orEmpty().map { entry ->
                WorkspaceDirectoryEntry(entry["name"].str().orEmpty(),
                    requireNotNull(entry["path"].str()), entry["hidden"].bool() == true)
            }
            return WorkspaceDirectoryLevel(requireNotNull(value["path"].str()),
                requireNotNull(value["home"].str()), entries("crumbs"), entries("entries"), value["truncated"].bool() == true)
        }
    }
}

/** A completed frame owns both panes and the commit target; draft text never becomes a target. */
internal class WorkspaceDirectoryState(
    private val scope: CoroutineScope,
    private val list: suspend (String?) -> WorkspaceDirectoryLevel,
) {
    var parent by mutableStateOf<WorkspaceDirectoryLevel?>(null)
        private set
    var child by mutableStateOf<WorkspaceDirectoryLevel?>(null)
        private set
    var selected by mutableStateOf<WorkspaceDirectoryEntry?>(null)
        private set
    var draft by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var showHidden by mutableStateOf(false)
    private var scan: Job? = null
    private var scannedDirectory: Pair<String, String>? = null
    private val level get() = child ?: parent
    val target get() = selected?.path ?: parent?.path
    val crumbs get() = level?.displayCrumbs().orEmpty()

    private fun supersede() { scan?.cancel(); loading = false }

    fun navigate(path: String? = null, base: WorkspaceDirectoryLevel? = null) {
        supersede()
        loading = true
        error = null
        scan = scope.launch {
            try {
                land(path, base, preview = false)
                draft = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { if (currentCoroutineContext().isActive) loading = false }
        }
    }

    private suspend fun land(path: String?, base: WorkspaceDirectoryLevel?, preview: Boolean) = coroutineScope {
        val next = list(path)
        ensureActive()
        val ancestorPath = if (next.path == next.home) null else next.crumbs.dropLast(1).lastOrNull()?.path
        val ancestor = ancestorPath?.let { ancestorPath ->
            async {
                try { base?.takeIf { it.path == ancestorPath } ?: list(ancestorPath) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null } // An unreadable parent must not prevent opening a readable directory.
            }
        }
        fun commit(previous: WorkspaceDirectoryLevel?) {
            val entry = previous?.entries?.find { it.path == next.path }
            parent = if (entry != null) previous else next
            selected = entry
            child = if (entry != null) next else null
            if (preview && path != null) scannedDirectory = path to next.path
        }
        val previous = if (preview) ancestor?.await() else withTimeoutOrNull(200) { ancestor?.await() }
        ensureActive()
        commit(previous)
        loading = false
        if (!preview) draft = null
        // A slow parent upgrades the same target in place, without blocking Open.
        if (previous == null && ancestor != null) {
            val late = ancestor.await()
            ensureActive()
            if (late != null) commit(late)
        }
    }

    fun beginEdit() {
        supersede()
        error = null
        draft = level?.directory.orEmpty()
    }

    fun edit(value: String) {
        // Android also reports caret/composition changes: they must not supersede an Enter submission.
        if (draft == null || value == draft) return
        supersede()
        draft = value
        error = null
        val current = level ?: return
        val directory = directoryPart(current, value) ?: return
        if (answers(current, directory)) return
        scan = scope.launch {
            delay(250)
            loading = true
            try { land(directory, null, preview = true) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* An incomplete path leaves the last readable frame intact. */ }
            finally { if (currentCoroutineContext().isActive) loading = false }
        }
    }

    fun cancelEdit() {
        supersede()
        draft = null
        error = null
        if (parent == null) navigate()
    }

    fun visibleEntries(level: WorkspaceDirectoryLevel): List<WorkspaceDirectoryEntry> {
        val directory = draft?.let { directoryPart(level, it) }
        val prefix = if (level == this.level && directory != null && answers(level, directory))
            draft!!.substring(directory.length) else ""
        fun matches(entry: WorkspaceDirectoryEntry) =
            (showHidden || !entry.hidden || prefix.startsWith('.')) && entry.name.startsWith(prefix, ignoreCase = true)
        val narrow = prefix.isNotEmpty() && level.entries.any(::matches)
        return level.entries.filter { entry ->
            entry.path == selected?.path || if (narrow) matches(entry) else showHidden || !entry.hidden
        }
    }

    private fun answers(level: WorkspaceDirectoryLevel, directory: String) =
        directory == level.directory || (scannedDirectory?.first == directory && scannedDirectory?.second == level.path)

    private fun directoryPart(level: WorkspaceDirectoryLevel, value: String): String? {
        val cut = if (level.separator == '\\') maxOf(value.lastIndexOf('\\'), value.lastIndexOf('/')) else value.lastIndexOf('/')
        return if (cut < 0) null else value.substring(0, cut + 1)
    }

    fun dispose() = supersede()
}
