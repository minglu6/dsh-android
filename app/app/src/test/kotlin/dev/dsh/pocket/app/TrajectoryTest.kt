package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.ToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrajectoryTest {
    @Test fun groupsUserTurnWithTools() {
        val turns = buildTrajectory(listOf(
            Item.User("u1", "hello"),
            Item.Reasoning("r1", "think", 1000, false),
            Item.Tool("t1", "c1", "read", """{"path":"a.kt"}""", ToolStatus.Ok, startedAt = 0, endedAt = 40),
            Item.Text("x1", "done", false),
            Item.User("u2", "more"),
            Item.Text("x2", "ok", false),
        ))
        assertEquals(2, turns.size)
        assertEquals(1, turns[0].index)
        assertTrue(turns[0].rows.any { it.kind == "用户" })
        assertTrue(turns[0].rows.any { it.kind == "工具" && it.title.startsWith("read") })
        assertEquals(2, turns[1].index)
    }
}
