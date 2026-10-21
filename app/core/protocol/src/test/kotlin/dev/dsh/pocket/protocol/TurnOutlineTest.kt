package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TurnOutlineTest {
    private fun json(value: String) = PocketJson.parseToJsonElement(value)
    private fun entry(turn: Int, seq: Long, prompt: String = "", response: String = "") =
        """{"turn":$turn,"seq":$seq,"prompt":"$prompt","response":"$response"}"""
    private fun projection(r: TranscriptReducer, seq: Long, value: String): Boolean =
        r.apply(json("""{"type":"projection","key":"turnOutline","seq":$seq,"value":$value}"""))
    private fun opening(type: String = "snapshot", seq: Long = 100, values: String = "", records: String = "") =
        json("""{"type":"$type","cursor":$seq,"hasMore":true,"records":[$records],"projections":{"asOfSeq":$seq,"values":{$values}}}""")
    private fun start(seq: Long, turn: Int) =
        """{"type":"event","event":{"type":"turn/start","seq":$seq,"time":$seq,"data":{"turn":$turn}}}"""

    @Test fun latestSnapshotImmediatelyIncludesTurnsOutsideTheLoadedTail() {
        val r = TranscriptReducer()
        val all = "[${entry(0, 0, "first", "answer")},${entry(4, 40)},${entry(9, 90, "latest")}]"
        r.apply(opening(values = """"turnOutline":$all""", records = start(90, 9)))
        assertEquals(listOf(9), r.turnRecords().map { it.turn })
        assertEquals(listOf(
            TurnOutlineEntry(0, 0, "first", "answer"),
            TurnOutlineEntry(4, 40),
            TurnOutlineEntry(9, 90, "latest"),
        ), r.turnOutline())
        assertTrue(r.hasMore)
        assertEquals(90L, r.firstSeq)
    }

    @Test fun baselineWithoutAsOfSeqUsesTheOpeningCursor() {
        val r = TranscriptReducer()
        r.apply(json("""{"type":"snapshot","cursor":30,"records":[],"projections":{"values":{"turnOutline":[${entry(2, 20)}]}}}"""))
        assertFalse(projection(r, 29, "[]"))
        assertFalse(projection(r, 30, "[]"))
        assertTrue(projection(r, 31, "[${entry(2, 20, "ready")} ]"))
        assertEquals("ready", r.turnOutline().single().prompt)
    }

    @Test fun resumeSuppliesWholeLogOutlineWithoutLoadingEarlierRecords() {
        val r = TranscriptReducer()
        r.apply(opening(seq = 20, records = start(20, 2)))
        r.apply(opening(type = "resume", seq = 30,
            values = """"turnOutline":[${entry(0, 0)},${entry(2, 20)},${entry(3, 30)}]""",
            records = start(30, 3)))
        assertEquals(listOf(2, 3), r.turnRecords().map { it.turn })
        assertEquals(listOf(0, 2, 3), r.turnOutline().map { it.turn })
        assertEquals(20L, r.firstSeq)
        assertEquals(30L, r.cursor)
    }

    @Test fun perKeyWatermarkIsIndependentOfEventCursorAndDropsEqualOrOlderUpdates() {
        val r = TranscriptReducer()
        r.apply(opening(seq = 1000))
        assertTrue(projection(r, 10, "[${entry(1, 5, "first")} ]"))
        val accepted = r.turnOutline()
        assertFalse(projection(r, 10, "[]"))
        assertFalse(projection(r, 9, "null"))
        assertSame(accepted, r.turnOutline())
        assertEquals(1000L, r.cursor)
        assertTrue(projection(r, 11, "[${entry(1, 5, "first", "settled")},${entry(2, 11)}]"))
        assertEquals(listOf(1, 2), r.turnOutline().map { it.turn })
        assertEquals("settled", r.turnOutline().first().response)
    }

    @Test fun oldSnapshotAndResumeCannotReplaceOrWithdrawANewerOutline() {
        val r = TranscriptReducer()
        projection(r, 120, "[${entry(1, 0, "new", "answer")},${entry(2, 100)}]")
        val latest = r.turnOutline()
        r.apply(opening(seq = 100, values = """"turnOutline":[${entry(1, 0, "old")}]"""))
        assertSame(latest, r.turnOutline())
        r.apply(opening(type = "resume", seq = 110))
        assertSame(latest, r.turnOutline())
        r.apply(opening(seq = 119, values = """"turnOutline":null"""))
        assertSame(latest, r.turnOutline())
        r.apply(opening(type = "resume", seq = 121,
            values = """"turnOutline":[${entry(1, 0, "new", "answer")},${entry(2, 100, "second")}]"""))
        assertEquals("second", r.turnOutline().last().prompt)
    }

    @Test fun newerOpeningsClearAnAbsentCapabilityAndLiveWithdrawalRetainsItsWatermark() {
        val r = TranscriptReducer()
        projection(r, 20, "[${entry(1, 1)}]")
        r.apply(opening(type = "resume", seq = 20))
        assertEquals(emptyList(), r.turnOutline())
        projection(r, 21, "[${entry(2, 21)}]")
        assertTrue(projection(r, 22, "null"))
        assertFalse(projection(r, 21, "[${entry(2, 21)}]"))
        assertEquals(emptyList(), r.turnOutline())
        r.apply(opening(seq = 23, values = """"turnOutline":[${entry(3, 23)}]"""))
        assertEquals(listOf(3), r.turnOutline().map { it.turn })
        r.apply(opening(seq = 24))
        assertEquals(emptyList(), r.turnOutline())
    }

    @Test fun reconnectClearsThePreviousHostGenerationAndAcceptsRestartedWatermarks() {
        val r = TranscriptReducer()
        projection(r, 1000, "[${entry(5, 900)}]")
        r.clearComposerProjections()
        assertEquals(emptyList(), r.turnOutline())
        r.apply(opening(type = "resume", seq = 3, values = """"turnOutline":[${entry(0, 0)}]"""))
        assertEquals(listOf(TurnOutlineEntry(0, 0)), r.turnOutline())
        assertTrue(projection(r, 4, "[${entry(0, 0)},${entry(1, 4)}]"))
        assertEquals(listOf(0, 1), r.turnOutline().map { it.turn })
    }

    @Test fun prependAndDuplicateHistoryDoNotRebuildOrOverwriteTheLatestOutline() {
        val r = TranscriptReducer()
        r.apply(opening(seq = 30, values = """"turnOutline":[${entry(0, 0, "authoritative")},${entry(3, 30)}]""",
            records = start(30, 3)))
        projection(r, 31, "[${entry(0, 0, "authoritative", "latest response")},${entry(3, 30)}]")
        val latest = r.turnOutline()
        val page = json("[${start(0, 0)},${start(10, 1)},${start(20, 2)}]")
        assertTrue(r.prepend(page, false))
        assertEquals(listOf(0, 1, 2, 3), r.turnRecords().map { it.turn })
        assertSame(latest, r.turnOutline())
        assertFalse(r.prepend(page, false))
        assertSame(latest, r.turnOutline())
        // An old page's outline cut must not displace the live whole-log answer either.
        r.apply(opening(type = "resume", seq = 20,
            values = """"turnOutline":[${entry(0, 0, "old page")}]"""))
        assertSame(latest, r.turnOutline())
    }

    @Test fun outlineUsesTheRealArrayWireViewNotPersistedFoldState() {
        val r = TranscriptReducer()
        for ((index, value) in listOf(
            """{"turns":[${entry(1, 1)}],"draft":"private"}""",
            """{"entries":[${entry(1, 1)}]}""",
            """"not an array"""",
            "42", "true", "null", "[]",
        ).withIndex()) {
            projection(r, index * 2L, "[${entry(1, 1)}]")
            assertTrue(projection(r, index * 2L + 1, value))
            assertEquals(emptyList(), r.turnOutline())
        }
    }

    @Test fun malformedCoordinatesDropOnlyTheirEntryWhileMissingDecorationsKeepTheTurn() {
        val r = TranscriptReducer()
        val value = """[
            null, [], false, "bad",
            {"turn":1}, {"seq":1},
            {"turn":"1","seq":1}, {"turn":1,"seq":"1"},
            {"turn":-1,"seq":1}, {"turn":1,"seq":-1},
            {"turn":1.5,"seq":1}, {"turn":1,"seq":1.5},
            {"turn":2147483648,"seq":1}, {"turn":1,"seq":9007199254740992},
            {"turn":1,"seq":-0}, {"turn":1,"seq":-0.0},
            {"turn":1,"seq":1e999}, {"turn":null,"seq":1},
            {"turn":1,"seq":null}, {"turn":true,"seq":1},
            {"turn":0,"seq":0},
            {"turn":2,"seq":2,"prompt":false,"response":{"text":"wrong type"}},
            {"turn":3,"seq":3,"prompt":null,"response":"kept"},
            {"turn":4e0,"seq":4e1,"prompt":"exponent"},
            {"turn":2147483647,"seq":9007199254740991,"response":"last safe seq"}
        ]"""
        assertTrue(projection(r, 1, value))
        assertEquals(listOf(
            TurnOutlineEntry(0, 0),
            TurnOutlineEntry(2, 2),
            TurnOutlineEntry(3, 3, response = "kept"),
            TurnOutlineEntry(4, 40, "exponent"),
            TurnOutlineEntry(Int.MAX_VALUE, 9_007_199_254_740_991L, response = "last safe seq"),
        ), r.turnOutline())
    }

    @Test fun desktopMergeOrderSortsMarksAndKeepsTheLastValidDuplicate() {
        val r = TranscriptReducer()
        projection(r, 10, """[
            ${entry(3, 30, "third")},
            ${entry(1, 10, "old")},
            ${entry(2, 20, "second")},
            ${entry(1, 11, "replacement", "answer")},
            {"turn":1,"seq":-1,"prompt":"invalid duplicate"}
        ]""")
        assertEquals(listOf(
            TurnOutlineEntry(1, 11, "replacement", "answer"),
            TurnOutlineEntry(2, 20, "second"),
            TurnOutlineEntry(3, 30, "third"),
        ), r.turnOutline())
    }
}
