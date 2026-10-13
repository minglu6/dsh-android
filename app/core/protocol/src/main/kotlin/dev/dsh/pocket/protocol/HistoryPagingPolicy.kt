package dev.dsh.pocket.protocol

/** Arguments for one `history.page` call. */
data class HistoryPageRequest(
    val throughSeq: Long,
    val beforeSeq: Long?,
    val maxMessages: Int,
)

/**
 * Pure paging policy for loading older transcript turns.
 * Matches the desktop dsh near-top scroll path used by the phone client.
 */
object HistoryPagingPolicy {
    const val PAGE_SIZE = 100
    const val FOLLOW_MAX_MESSAGES = 50

    fun canLoadOlder(hasMore: Boolean, loadingOlder: Boolean, firstSeq: Long): Boolean =
        hasMore && !loadingOlder && firstSeq >= 0

    fun pageRequest(cursor: Long, firstSeq: Long, maxMessages: Int = PAGE_SIZE): HistoryPageRequest {
        require(firstSeq >= 0) { "firstSeq must be known before paging" }
        require(maxMessages > 0) { "maxMessages must be positive" }
        return HistoryPageRequest(
            throughSeq = cursor.coerceAtLeast(firstSeq),
            beforeSeq = firstSeq,
            maxMessages = maxMessages,
        )
    }
}
