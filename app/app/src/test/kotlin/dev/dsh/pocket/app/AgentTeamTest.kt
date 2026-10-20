package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class AgentTeamTest {
    private fun json(text: String) = PocketJson.parseToJsonElement(text)
    private val root = SessionAddress("lead")
    private val child = SessionAddress("worker", "lead", "continuable")
    private val baseline = json("""{"asOfSeq":10,"values":{
      "agentTeam":{"members":[
        {"id":"lead","name":"Coordinator","role":"lead","phase":"active"},
        {"id":"worker","name":"Researcher","role":"teammate","phase":"active"},
        {"id":"pending","name":"Starting","role":"teammate","phase":"provisioning"}
      ],"tasks":[{"id":"T1","revision":2,"subject":"Inspect source","description":"Read evidence",
        "status":"pending","blockedBy":["T0"],"writeScopes":["app/"],"ownerName":"Researcher",
        "ready":false,"writeScopeWarnings":["Overlapping scope"]}],"failure":"Recorded failure"},
      "subagentCatalog":[{"id":"worker","createdAt":1,"label":"Delegated research","mode":"continuable"},
        {"id":"external","createdAt":2,"mode":"external","label":"External task"}]
    }}""")

    @Test fun membersAndTasksComeOnlyFromTeamProjectionNotEveryChildSummary() {
        val store = AgentTeamStore()
        val summaries = listOf(SessionSummary("unrelated-fork", "Fork", null, false, false, 0, "lead", origin = "fork"))
        assertNull(store.team(root, summaries))
        store.baseline("lead", baseline)
        val team = store.team(root, summaries)!!
        assertEquals(listOf("Coordinator", "Researcher", "Starting"), team.members.map { it.name })
        assertTrue(team.members.first().current)
        assertFalse(team.members.first().canOpen)
        assertEquals(child, team.members[1].address)
        assertTrue(team.members[1].canOpen)
        assertFalse(team.members[2].canOpen)
        assertEquals("Recorded failure", team.failure)
        val task = team.tasks.single()
        assertEquals("Researcher", task.ownerName)
        assertEquals(listOf("T0"), task.blockedBy)
        assertEquals(listOf("app/"), task.writeScopes)
        assertEquals(listOf("Overlapping scope"), task.writeScopeWarnings)
        assertFalse(task.ready)
    }

    @Test fun childViewKeepsLeadIdentityAndExternalEntriesCannotOpenAsRoot() {
        val store = AgentTeamStore()
        store.baseline("lead", baseline)
        val team = store.team(child, emptyList())!!
        assertEquals("lead", team.leadSessionId)
        assertEquals(root, team.members.first().address)
        assertTrue(team.members.first().canOpen)
        assertTrue(team.members[1].current)
        assertFalse(team.members[1].canOpen)
        val directory = store.subagents(child, emptyList())!!
        assertEquals(listOf("worker", "external"), directory.map { it.id })
        assertNull(directory.last().address)
        assertFalse(directory.last().canOpen)
    }

    @Test fun staleProjectionCannotRestoreMembershipAndReconnectResetsSequenceDomain() {
        val store = AgentTeamStore()
        store.baseline("lead", baseline)
        store.put("lead", "subagentCatalog", 11, json("[]"))
        store.baseline("lead", baseline)
        assertNull(store.address("worker"))
        store.resetSequences()
        store.baseline("lead", json("""{"asOfSeq":0,"values":{"subagentCatalog":[
          {"id":"worker","createdAt":1,"mode":"one-shot"}]}}"""))
        assertEquals(SessionAddress("worker", "lead", "one-shot"), store.address("worker"))
        assertNull(store.team(root, emptyList()))
        store.clear()
        assertNull(store.subagents(root, emptyList()))
    }
}
