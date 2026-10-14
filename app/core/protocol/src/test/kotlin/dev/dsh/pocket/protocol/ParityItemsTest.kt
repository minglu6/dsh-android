package dev.dsh.pocket.protocol

import kotlin.test.*

/** Transcript parsing for deliverables, images, messageId (desktop parity remaining gaps). */
class ParityItemsTest {
    private fun j(s: String) = PocketJson.parseToJsonElement(s)
    private fun ev(seq: Int, type: String, data: String) =
        j("""{"type":"event","event":{"type":"$type","seq":$seq,"time":${1000 + seq},"data":$data}}""")

    @Test fun preservesForkIdentityAndSearchSnippetSeparately() {
        val fork = assertNotNull(SessionSummary.parse(j("""{"sessionId":"fork","parentSessionId":"root","snippet":"matched text"}""")))
        assertNull(fork.origin)
        assertEquals("root", fork.parentSessionId)
        assertNull(fork.title)
        assertEquals("matched text", fork.searchSnippet)
        val subagent = assertNotNull(SessionSummary.parse(j("""{"sessionId":"child","origin":"subagent"}""")))
        assertEquals("subagent", subagent.origin)
    }

    @Test fun workspaceRetainsHostIdentityAndManualSessionOrder() {
        val workspace = assertNotNull(WorkspaceView.parse(j("""{"workspaceId":"w1","path":"/project","title":"Project","sessionIds":["b","a"],"createdAt":"2026-10-13T00:00:00Z"}""")))
        assertEquals("w1", workspace.workspaceId)
        assertEquals(listOf("b", "a"), workspace.sessionIds)
        assertNull(WorkspaceView.parse(j("""{"path":"/project","title":"No identity"}""")))
    }

    @Test fun parsesUserImageRefs() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"projections":{}}"""))
        r.apply(
            ev(
                1, "user/message",
                """{"role":"user","source":{"kind":"user"},"content":[
                    {"type":"text","text":"see this"},
                    {"type":"image","attachment":{"attachmentId":"att-1","mediaType":"image/png"}},
                    {"type":"image","attachment":{"attachmentId":"att-2","mediaType":"image/jpeg"}}
                ]}""",
            ),
        )
        val user = r.items().filterIsInstance<Item.User>().single()
        assertEquals("see this", user.text)
        assertEquals(2, user.images)
        assertEquals(
            listOf(ImageRef("att-1", "image/png"), ImageRef("att-2", "image/jpeg")),
            user.imageRefs,
        )
    }

    @Test fun capturesAssistantMessageIdOnText() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"projections":{}}"""))
        r.apply(
            ev(
                1, "assistant/message",
                """{"turn":1,"step":1,"stream":[],"usage":{"inputTokens":1,"outputTokens":2},
                "message":{"id":"msg-abc","content":[{"type":"text","text":"hello"}],"source":{"provider":"p","model":"m"}}}""",
            ),
        )
        val text = r.items().filterIsInstance<Item.Text>().single()
        assertEquals("hello", text.text)
        assertEquals("msg-abc", text.messageId)
        assertFalse(text.live)
    }

    @Test fun parsesDeliverablesPresented() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"projections":{}}"""))
        r.apply(
            ev(
                1, "deliverables/presented",
                """{"turn":2,"callId":"present-1","files":[
                    {"path":"/tmp/a.txt","description":"report"},
                    {"path":"out/b.md"}
                ]}""",
            ),
        )
        val d = r.items().filterIsInstance<Item.Deliverable>().single()
        assertEquals(2, d.files.size)
        assertEquals("/tmp/a.txt", d.files[0].path)
        assertEquals("report", d.files[0].description)
        assertEquals("out/b.md", d.files[1].path)
        assertNull(d.files[1].description)
        assertEquals(2, d.turn)
    }

    @Test fun parsesWorkspaceChanges() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"projections":{}}"""))
        r.apply(ev(1, "workspace/changes", """{"turn":3}"""))
        val c = r.items().filterIsInstance<Item.Changes>().single()
        assertEquals(3, c.turn)
        assertEquals(1L, c.seq)

        r.apply(
            ev(
                2, "workspace/changes",
                """{"turn":4,"total":1,"files":[{"path":"src/a.kt","display":"src/a.kt"}]}""",
            ),
        )
        val c2 = r.items().filterIsInstance<Item.Changes>().last()
        assertEquals(4, c2.turn)
        assertEquals(2L, c2.seq)
    }

    @Test fun latestWorkspaceChangesAnnouncementReplacesOnlyItsTurn() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"projections":{}}"""))
        r.apply(ev(1, "workspace/changes", """{"turn":3}"""))
        r.apply(ev(2, "workspace/changes", """{"turn":4}"""))
        r.apply(ev(3, "workspace/changes", """{"turn":3}"""))
        val changes = r.items().filterIsInstance<Item.Changes>()
        assertEquals(listOf(3, 4), changes.map { it.turn })
        assertEquals(listOf(3L, 2L), changes.map { it.seq })
        assertEquals("ch3", changes.first().key)
        r.apply(ev(1, "workspace/changes", """{"turn":3}"""))
        assertEquals(changes, r.items().filterIsInstance<Item.Changes>())
    }

    @Test fun historyPageDoesNotRestoreSupersededWorkspaceChanges() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":4,"hasMore":true,"records":[
            {"type":"event","event":{"type":"workspace/changes","seq":4,"data":{"turn":3}}}
        ],"projections":{}}"""))
        r.prepend(j("""[
            {"type":"event","event":{"type":"workspace/changes","seq":1,"data":{"turn":2}}},
            {"type":"event","event":{"type":"workspace/changes","seq":2,"data":{"turn":3}}},
            {"type":"event","event":{"type":"workspace/changes","seq":3,"data":{"turn":3}}}
        ]"""), false)
        assertEquals(listOf(2, 3), r.items().filterIsInstance<Item.Changes>().map { it.turn })
        assertEquals(listOf(1L, 4L), r.items().filterIsInstance<Item.Changes>().map { it.seq })
    }

    @Test fun snapshotKeepsLatestWorkspaceChangesAndDoesNotGuessFromText() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":3,"records":[
            {"type":"event","event":{"type":"workspace/changes","seq":1,"data":{"turn":2}}},
            {"type":"event","event":{"type":"workspace/changes","seq":2,"data":{"turn":2}}},
            {"type":"event","event":{"type":"assistant/message","seq":3,"data":{
                "turn":3,"step":1,"stream":[],"message":{"content":[{"type":"text","text":"Edited a.kt +5 -2"}]}
            }}}
        ],"projections":{}}"""))
        assertEquals(2L, r.items().filterIsInstance<Item.Changes>().single().seq)
    }
}
