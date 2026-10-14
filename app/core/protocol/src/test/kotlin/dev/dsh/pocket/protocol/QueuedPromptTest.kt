package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueuedPromptTest {
    private fun json(text: String) = PocketJson.parseToJsonElement(text)
    private fun message(id: String, rpc: String = id, text: String = "same", extra: String = "") =
        """{"id":"$id","role":"user","source":{"kind":"user","rpcId":"$rpc"},"content":[{"type":"text","text":"$text"}$extra]}"""
    private fun event(seq: Int, type: String, data: String) =
        json("""{"type":"event","event":{"seq":$seq,"type":"$type","time":${1000 + seq},"data":$data}}""")
    private fun splice(seq: Int, target: String, start: Int = 0, removed: Int = 0, inserted: String = "", canceled: Boolean = false) =
        event(seq, "agent/inbox/spliced", """{"target":"$target","start":$start,"removedCount":$removed,"inserted":[$inserted]${if (canceled) ",\"outcome\":\"canceled\"" else ""}}""")
    private fun projection(seq: Int, queue: String = "", steer: String = "") =
        json("""{"type":"projection","key":"inbox","seq":$seq,"value":{"next-turn":[$queue],"next-step":[$steer]}}""")
    private fun snapshot(seq: Int, queue: String = "", steer: String = "", records: String = "", type: String = "snapshot") =
        json("""{"type":"$type","cursor":$seq,"records":[$records],"projections":{"asOfSeq":$seq,"values":{"inbox":{"next-turn":[$queue],"next-step":[$steer]}}}}""")
    private fun TranscriptReducer.users() = items().filterIsInstance<Item.User>()

    @Test fun editingRequiresNonemptyEntirelyTextContentAcrossProjectionAndRestore() {
        val cases = listOf(
            """[{"type":"text","text":"one"},{"type":"text","text":"two"}]""" to true,
            """[{"type":"text","text":"caption"},{"type":"image"}]""" to false,
            """[{"type":"text","text":"caption"},{"type":"file"}]""" to false,
            """[{"type":"text","text":"caption"},{"type":"future-content"}]""" to false,
            "[]" to false,
        )
        cases.forEach { (content, editable) ->
            val row = """{"id":"m","role":"user","source":{"kind":"user","rpcId":"r"},"content":$content}"""
            val r = TranscriptReducer()
            r.apply(projection(1, row))
            assertEquals(editable, r.queueMessages().single().editable)
            r.apply(snapshot(2, row))
            assertEquals(editable, r.queueMessages().single().editable)
            assertFalse(r.queueMessages().single().sending)
        }
        assertTrue(QueuedPrompt("q", "text").editable)
        assertFalse(QueuedPrompt("q", "text", images = 1).editable)
        assertFalse(QueuedPrompt("q", "text", files = 1).editable)
    }

    @Test fun busyQueueWaitsForItsOwnTurnWhileSteerRemainsInTranscript() {
        val r = TranscriptReducer()
        r.apply(event(1, "turn/start", """{"turn":1}"""))
        r.echo("same", "q", "queue")
        r.echo("same", "s", "steer")
        assertEquals(listOf("s"), r.users().map { it.requestId })
        assertEquals(listOf(QueuedPrompt("q", "same", sending = true)), r.queueMessages())
        r.apply(splice(2, "next-turn", inserted = message("qm", "q")))
        r.apply(splice(3, "next-step", inserted = message("sm", "s")))
        assertFalse(r.queueMessages().single().sending)
        r.apply(splice(4, "next-step", removed = 1))
        r.apply(event(5, "user/message", message("sm", "s")))
        r.apply(event(6, "assistant/message", """{"message":{"content":[{"type":"text","text":"first reply"}]}}"""))
        r.apply(event(7, "turn/end", """{"turn":1}"""))
        r.apply(event(8, "turn/start", """{"turn":2}"""))
        r.apply(splice(9, "next-turn", removed = 1))
        assertTrue(r.queueMessages().isEmpty())
        assertEquals(listOf("s"), r.users().map { it.requestId })
        r.apply(event(10, "user/message", message("qm", "q")))
        r.apply(event(10, "user/message", message("qm", "q")))
        assertEquals(listOf("User", "Text", "User"), r.items().map { it::class.simpleName })
        assertEquals(listOf("s", "q"), r.users().map { it.requestId })
        assertTrue(r.users().none { it.pending })
    }

    @Test fun identicalTextNeverConsumesAnotherRequestEcho() {
        val r = TranscriptReducer()
        r.echo("same", "first")
        r.apply(event(1, "turn/start", "{}"))
        r.echo("same", "second")
        r.echo("same", "third")
        r.apply(event(2, "user/message", message("remote", "remote")))
        assertEquals(listOf("first", "remote"), r.users().map { it.requestId })
        assertEquals(listOf("second", "third"), r.queueMessages().map { it.id })
        r.apply(event(3, "user/message", message("first-message", "first")))
        assertEquals(2, r.users().size)
        r.discardEcho("second")
        assertEquals(listOf("third"), r.queueMessages().map { it.id })
    }

    @Test fun idleEchoKeepsItsPlaceThroughAdmissionAndClaim() {
        val r = TranscriptReducer()
        r.echo("first", "first")
        val key = r.users().single().key
        r.apply(splice(1, "next-turn", inserted = message("m", "first", "first")))
        assertTrue(r.queueMessages().isEmpty())
        r.apply(event(2, "system/message", """{"content":[{"type":"text","text":"context"}]}"""))
        r.apply(splice(3, "next-turn", removed = 1))
        r.apply(event(4, "user/message", message("m", "first", "first")))
        assertEquals(key, r.items().first().key)
        assertEquals(1, r.users().size)
        assertFalse(r.users().single().pending)
    }

    @Test fun splicesRespectPositionsCancellationAndReplacement() {
        val r = TranscriptReducer()
        r.apply(projection(1, listOf("a", "b", "c").joinToString(",") { message(it) }))
        r.apply(splice(2, "next-turn", start = 1, removed = 1, inserted = message("replacement"), canceled = true))
        assertEquals(listOf("a", "replacement", "c"), r.queueMessages().map { it.id })
        r.apply(splice(3, "next-turn", start = 2, removed = 1, canceled = true))
        r.apply(splice(4, "next-turn", removed = 1))
        assertEquals(listOf("replacement"), r.queueMessages().map { it.id })
        assertTrue(r.items().isEmpty())
        r.apply(event(5, "user/message", message("a")))
        assertEquals(listOf("a"), r.users().map { it.messageId })
    }

    @Test fun coldSnapshotAndResumeRecoverBothInboxTargets() {
        val r = TranscriptReducer()
        r.apply(snapshot(10, message("queue"), message("steer")))
        assertEquals(listOf("queue"), r.queueMessages().map { it.id })
        assertEquals(listOf("steer"), r.users().map { it.messageId })
        r.apply(snapshot(11, message("new"), type = "resume"))
        assertEquals(listOf("new"), r.queueMessages().map { it.id })
        assertTrue(r.users().isEmpty())
    }

    @Test fun consumedMessagesCannotReturnFromDelayedProjectionOrSnapshot() {
        val r = TranscriptReducer()
        val m = message("q")
        r.apply(snapshot(10, m))
        r.apply(splice(11, "next-turn", removed = 1))
        assertFalse(r.apply(projection(10, m)))
        r.apply(projection(11, m))
        assertTrue(r.queueMessages().isEmpty())
        r.apply(event(12, "user/message", m))
        r.apply(projection(12, m))
        assertTrue(r.queueMessages().isEmpty())
        assertTrue(r.apply(snapshot(10, m)))
        assertTrue(r.queueMessages().isEmpty())
        r.apply(snapshot(13, m, records = event(12, "user/message", m).toString()))
        assertTrue(r.queueMessages().isEmpty())
        assertEquals(1, r.users().size)
    }

    @Test fun replacementTailRetiresConfirmedEchoWhoseAdmissionIsOutsideTheWindow() {
        for (target in listOf("next-turn", "next-step")) {
            val r = TranscriptReducer()
            r.echo("same", "accepted", if (target == "next-step") "steer" else "queue")
            r.apply(splice(1, target, inserted = message("m", "accepted")))
            r.apply(splice(2, target, removed = 1))
            r.apply(snapshot(2))
            assertEquals(listOf("accepted"), r.users().map { it.requestId })
            r.echo("not acknowledged", "unconfirmed", "steer")
            r.apply(snapshot(4, records = event(4, "turn/end", "{}").toString()))
            assertEquals(listOf("unconfirmed"), r.users().map { it.requestId })
            assertTrue(r.queueMessages().isEmpty())
        }
    }

    @Test fun replacementTailKeepsConfirmedEchoStillPresentInInbox() {
        val r = TranscriptReducer()
        val m = message("m", "accepted")
        r.echo("same", "accepted")
        r.apply(splice(1, "next-turn", inserted = m))
        r.apply(snapshot(4, queue = m))
        assertEquals(listOf("accepted"), r.users().map { it.requestId })
        assertTrue(r.queueMessages().isEmpty())
        r.apply(event(5, "user/message", m))
        assertEquals(1, r.users().size)
        assertFalse(r.users().single().pending)
    }

    @Test fun admissionFilteringDoesNotChangeRawProjectionSplicePositions() {
        val r = TranscriptReducer()
        val a = message("a")
        val b = message("b")
        r.apply(projection(1, "$a,$b"))
        r.apply(event(2, "user/message", a))
        r.apply(projection(2, "$a,$b"))
        assertEquals(listOf("b"), r.queueMessages().map { it.id })
        r.apply(splice(3, "next-turn", start = 1, removed = 1, canceled = true))
        assertTrue(r.queueMessages().isEmpty())
        assertEquals(listOf("a"), r.users().map { it.messageId })
    }

    @Test fun delayedConsumedProjectionCannotShiftTheRemainingQueue() {
        val r = TranscriptReducer()
        val a = message("a")
        val b = message("b")
        r.apply(snapshot(10, "$a,$b"))
        r.apply(splice(11, "next-turn", removed = 1))
        r.apply(projection(11, "$a,$b"))
        assertEquals(listOf("b"), r.queueMessages().map { it.id })
        r.apply(splice(12, "next-turn", removed = 1))
        assertTrue(r.queueMessages().isEmpty())
        r.apply(event(13, "user/message", a))
        r.apply(event(14, "user/message", b))
        assertEquals(listOf("a", "b"), r.users().map { it.messageId })
    }

    @Test fun projectionBeforeItsMatchingSpliceIsNotAppliedTwice() {
        val r = TranscriptReducer()
        val a = message("a")
        val b = message("b")
        r.apply(projection(1, a))
        r.apply(splice(1, "next-turn", inserted = a))
        assertEquals(listOf("a"), r.queueMessages().map { it.id })
        r.apply(projection(2, b))
        r.apply(splice(2, "next-turn", removed = 1, inserted = b, canceled = true))
        assertEquals(listOf("b"), r.queueMessages().map { it.id })
    }

    @Test fun cancellationAfterProjectionAlsoRetiresTranscriptOwnedEcho() {
        for (target in listOf("next-turn", "next-step")) {
            val r = TranscriptReducer()
            r.echo("same", "r", if (target == "next-step") "steer" else "queue")
            r.apply(splice(1, target, inserted = message("m", "r")))
            r.apply(projection(2))
            r.apply(splice(2, target, removed = 1, canceled = true))
            assertTrue(r.items().isEmpty())
            assertTrue(r.queueMessages().isEmpty())
        }
    }

    @Test fun localQueueSurvivesReconnectUntilAcknowledgedByIdentity() {
        val r = TranscriptReducer()
        r.apply(event(1, "turn/start", "{}"))
        r.echo("same", "mine")
        r.apply(snapshot(2, message("other", "other")))
        assertEquals(listOf("other", "mine"), r.queueMessages().map { it.id })
        r.apply(snapshot(3, "${message("other", "other")},${message("mine-message", "mine")}"))
        assertEquals(listOf("other", "mine-message"), r.queueMessages().map { it.id })
        assertTrue(r.queueMessages().none { it.sending })
        assertTrue(r.items().isEmpty())
    }

    @Test fun attachmentOnlyQueueAndFailedEchoCleanup() {
        val r = TranscriptReducer()
        r.apply(event(1, "turn/start", "{}"))
        r.echo("", "attachments", images = 2, files = 1)
        assertEquals(QueuedPrompt("attachments", "", 2, 1, true), r.queueMessages().single())
        val extra = "," + """{"type":"image","attachment":{"attachmentId":"img1"}},{"type":"image","attachment":{"attachmentId":"img2"}},{"type":"file","attachment":{"attachmentId":"file1"}}"""
        r.apply(splice(2, "next-turn", inserted = message("m", "attachments", "", extra)))
        assertEquals(QueuedPrompt("m", "", 2, 1), r.queueMessages().single())
        r.echo("failure", "queued-failure")
        r.echo("failure", "steer-failure", "steer")
        r.discardEcho("queued-failure")
        r.discardEcho("steer-failure")
        r.discardEcho("attachments") // An already admitted Host occurrence is not a local failure.
        assertEquals(listOf("m"), r.queueMessages().map { it.id })
        assertTrue(r.items().isEmpty())
    }

    @Test fun historyPrependDoesNotBreakRequestOrToolIndexes() {
        val r = TranscriptReducer()
        r.apply(snapshot(5, records = event(5, "turn/start", "{}").toString()))
        r.echo("same", "steer", "steer")
        r.apply(event(6, "tool/call", """{"callId":"tool","name":"read","arguments":"{}"}"""))
        r.prepend(json("[${event(2, "user/message", message("older"))}]"), false)
        r.discardEcho("steer")
        r.apply(event(7, "tool/result", """{"message":{"toolCallId":"tool","content":[{"type":"text","text":"done"}]}}"""))
        assertEquals(listOf("older"), r.users().map { it.messageId })
        assertEquals(ToolStatus.Ok, r.items().filterIsInstance<Item.Tool>().single().status)
    }
}
