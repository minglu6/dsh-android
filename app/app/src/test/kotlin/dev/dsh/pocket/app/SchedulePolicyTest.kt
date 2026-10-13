package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SchedulePolicyTest {
    private val weekly = PocketJson.parseToJsonElement("""{
        "id":"task-1","sessionId":"session-1","kind":"weekly","status":"active",
        "title":"Reminder","prompt":"Do the work","scheduledAt":"2026-10-15T01:00:00.123Z",
        "time":"09:00:00.123","timeZone":"Asia/Shanghai","weekdays":[1,3,5],
        "lastDelivery":{"scheduledAt":"2026-10-13T01:00:00.123Z","messageId":"message-1"}
    }""") as JsonObject

    @Test fun snapshotKeepsCompleteRuleButNoCatalogMetadata() {
        val expected = scheduleSnapshot(weekly)
        assertEquals(setOf("id", "kind", "title", "prompt", "scheduledAt", "time", "timeZone", "weekdays"), expected.keys)
        assertEquals(weekly["weekdays"], expected["weekdays"])
        assertEquals("09:00:00.123", expected["time"].str())
        assertEquals("2026-10-15T01:00:00.123Z", expected["scheduledAt"].str())
    }

    @Test fun contentEditOmitsTimingAndRetainsOriginalCas() {
        val draft = scheduleTimingDraft(weekly)
        val request = scheduleUpdateRequest(weekly, "New title", "Do the work", draft, draft)
        assertFalse(request.containsKey("change"))
        assertFalse(request.containsKey("prompt"))
        assertEquals("New title", request["title"].str())
        assertEquals(scheduleSnapshot(weekly), request["expected"])
        val refreshed = JsonObject(weekly + ("scheduledAt" to JsonPrimitive("2026-10-17T01:00:00.123Z")))
        assertNotEquals(scheduleSnapshot(refreshed), request["expected"])
    }

    @Test fun changedWeekdaysKeepMillisecondsAndStoredZoneSpelling() {
        val alias = JsonObject(weekly + ("timeZone" to JsonPrimitive("US/Eastern")))
        val draft = scheduleTimingDraft(alias)
        val request = scheduleUpdateRequest(alias, "Reminder", "Do the work", draft, draft.copy(weekdays = setOf(2, 4)))
        assertEquals("09:00:00.123", request["change"]["weekly"]["time"].str())
        assertEquals("US/Eastern", request["change"]["weekly"]["time_zone"].str())
        assertEquals(listOf(2, 4), request["change"]["weekly"]["weekdays"].arr()?.map { it.int() })
    }

    @Test fun onceDraftSeedsDeviceWallClockWithoutLosingMillis() {
        val row = jsonOf("id" to "once", "kind" to "at", "sessionId" to "s", "title" to "Once", "prompt" to "Do",
            "scheduledAt" to "2026-11-01T06:30:00.123Z")
        val draft = scheduleTimingDraft(row, "America/New_York")
        assertEquals("2026-11-01", draft.date)
        assertEquals("01:30:00.123", draft.time)
        assertEquals("America/New_York", draft.zone)
        // The overlap's later instant must never be reinterpreted merely to edit its title.
        assertFalse(scheduleUpdateRequest(row, "Renamed", "Do", draft, draft).containsKey("change"))
    }

    @Test fun dstGapsAndOverlapsAreSentAsLocalSelectorsForHostToResolve() {
        val draft = scheduleTimingDraft(weekly).copy(kind = "once", zone = "America/New_York", date = "2027-03-14", time = "02:30:00.123")
        val gap = scheduleTimingChange(draft)
        assertEquals("2027-03-14", gap["at"]["date"].str())
        assertEquals("02:30:00.123", gap["at"]["time"].str())
        val overlap = scheduleTimingChange(draft.copy(date = "2026-11-01", time = "01:30:00.123"))
        assertEquals("01:30:00.123", overlap["at"]["time"].str())
        assertEquals("America/New_York", overlap["at"]["time_zone"].str())
    }

    @Test fun intervalConversionIsExactAndEnforcesHostFloorAndSafeInteger() {
        assertEquals(60L, scheduleIntervalSeconds("1", "minute"))
        assertEquals(90L, scheduleIntervalSeconds("1.5", "minute"))
        assertNull(scheduleIntervalSeconds("59", "second"))
        assertNull(scheduleIntervalSeconds("60.1", "second"))
        assertNull(scheduleIntervalSeconds("9007199254740992", "second"))
        assertEquals("1.5", scheduleConvertQuantity("90", "second", "minute"))
        assertNull(scheduleConvertQuantity("61", "second", "minute"))
        val row = jsonOf("kind" to "every", "everySeconds" to 61, "scheduledAt" to "2026-10-15T00:00:00.000Z")
        val draft = scheduleTimingDraft(row, "UTC")
        assertEquals("second", draft.unit)
        assertEquals("61", draft.quantity)
    }

    @Test fun cronMatchesSupportedNumericDialect() {
        listOf("0 9 * * 1-5", "*/15 9-17/2 1,15 * 0,7", "0 0 * * *", "0 0 */2 * 1-7").forEach { assertTrue(it, scheduleCronValid(it)) }
        listOf("60 9 * * *", "0 24 * * *", "0 9 0 * *", "0 9 * 13 *", "0 9 * * MON", "@daily", "0 0 * * 8", "0/2 9 * * *", "*/0 * * * *", "0 9 * * 5-1").forEach { assertFalse(it, scheduleCronValid(it)) }
    }

    @Test fun nativeTimePickerPreservesSecondsAndFractionExactly() {
        assertEquals("10:15:37.123", schedulePickedTime("09:00:37.123", 10, 15))
        assertEquals("09:00:37.123", schedulePickedTime("09:00:37.123", 9, 0))
        assertEquals("00:05:37.1", schedulePickedTime("09:00:37.1", 0, 5))
        assertEquals("10:15", schedulePickedTime("09:00", 10, 15))
        assertEquals("10:15:00.000", schedulePickedTime("invalid", 10, 15))
        val initial = scheduleTimingDraft(weekly)
        val pickedSameMinute = initial.copy(time = schedulePickedTime(initial.time, 9, 0))
        assertFalse(scheduleUpdateRequest(weekly, "New title", "Do the work", initial, pickedSameMinute).containsKey("change"))
    }

    @Test fun zoneSelectorsRejectAbbreviationsButPreserveIanaAliases() {
        val draft = scheduleTimingDraft(weekly)
        assertThrows(IllegalArgumentException::class.java) { scheduleTimingChange(draft.copy(zone = "PST")) }
        assertThrows(IllegalArgumentException::class.java) { scheduleTimingChange(draft.copy(zone = "+08:00")) }
        assertEquals("UTC", scheduleTimingChange(draft.copy(zone = "UTC"))["weekly"]["time_zone"].str())
        assertEquals("Etc/GMT+8", scheduleTimingChange(draft.copy(zone = "Etc/GMT+8"))["weekly"]["time_zone"].str())
    }

    @Test fun updateUnionAcceptsConfirmedNoOpButNotConflictOrMalformedSuccess() {
        assertEquals(weekly, scheduleUpdatedRecord(jsonOf("id" to "task-1", "updated" to false, "record" to weekly)))
        assertEquals(weekly, scheduleUpdatedRecord(jsonOf("id" to "task-1", "updated" to true, "record" to weekly)))
        assertThrows(IllegalStateException::class.java) { scheduleUpdatedRecord(jsonOf("id" to "task-1", "updated" to false, "code" to "schedule_conflict")) }
        assertThrows(IllegalStateException::class.java) { scheduleUpdatedRecord(jsonOf("code" to "invalid_rule", "message" to "Bad date")) }
        assertThrows(IllegalStateException::class.java) { scheduleUpdatedRecord(jsonOf("updated" to true)) }
    }

    @Test fun deletionRequiresAffirmativeBusinessAcknowledgement() {
        scheduleConfirmDeleted(jsonOf("deleted" to true))
        assertThrows(IllegalStateException::class.java) { scheduleConfirmDeleted(jsonOf("deleted" to false, "code" to "schedule_not_found")) }
        assertThrows(IllegalStateException::class.java) { scheduleConfirmDeleted(jsonOf("ok" to true)) }
    }
}
