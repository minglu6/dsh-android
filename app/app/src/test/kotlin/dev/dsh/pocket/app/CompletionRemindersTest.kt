package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.PocketJson
import org.junit.Test
import kotlin.test.assertEquals

class CompletionRemindersTest {
    @Test fun delayedStateReplyCannotRestoreAnAcknowledgedCompletion() {
        val reminders = CompletionReminders()
        reminders.accept(PocketJson.parseToJsonElement("""{"epoch":"host-a","revision":4,"unreadIds":["one"]}"""))
        assertEquals(setOf("one"), reminders.unreadIds.value)
        reminders.accept(PocketJson.parseToJsonElement("""{"epoch":"host-a","revision":5,"unreadIds":[]}"""))
        reminders.accept(PocketJson.parseToJsonElement("""{"epoch":"host-a","revision":4,"unreadIds":["one"]}"""))
        assertEquals(emptySet(), reminders.unreadIds.value)
        reminders.accept(PocketJson.parseToJsonElement("""{"epoch":"host-a","revision":6,"unreadIds":["two"]}"""))
        assertEquals(setOf("two"), reminders.unreadIds.value)
    }

    @Test fun aDifferentHostStartsFreshAndRejectsOldEpochsAndMalformedSnapshots() {
        val reminders = CompletionReminders()
        reminders.accept(PocketJson.parseToJsonElement("""{"epoch":"host-a","revision":40,"unreadIds":["one"]}"""))
        reminders.reset()
        reminders.accept(PocketJson.parseToJsonElement("""{"epoch":"host-b","revision":0,"unreadIds":["two"]}"""))
        reminders.accept(PocketJson.parseToJsonElement("""{"epoch":"host-a","revision":41,"unreadIds":[]}"""))
        reminders.accept(PocketJson.parseToJsonElement("""{"epoch":"host-b","revision":1,"unreadIds":[null]}"""))
        assertEquals(setOf("two"), reminders.unreadIds.value)
        reminders.accept(PocketJson.parseToJsonElement("""{"epoch":"host-b","revision":1,"unreadIds":[]}"""))
        assertEquals(emptySet(), reminders.unreadIds.value)
    }
}
