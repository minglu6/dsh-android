package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

/** Host-owned reminders: a stale RPC response must not undo a newer pushed acknowledgement. */
internal class CompletionReminders {
    private val unread = MutableStateFlow<Set<String>>(emptySet())
    val unreadIds = unread.asStateFlow()
    private var epoch: String? = null
    private var revision = -1L

    fun reset() {
        epoch = null
        revision = -1L
        unread.value = emptySet()
    }

    fun accept(snapshot: JsonElement?) {
        val nextEpoch = snapshot["epoch"].str()?.takeIf { it.isNotBlank() } ?: return
        val nextRevision = snapshot["revision"].long()?.takeIf { it >= 0 } ?: return
        val ids = snapshot["unreadIds"].arr() ?: return
        if (epoch != null && epoch != nextEpoch || nextRevision <= revision) return
        val values = ids.map { it.str()?.takeIf(String::isNotBlank) ?: return }.toSet()
        epoch = nextEpoch
        revision = nextRevision
        unread.value = values
    }
}
