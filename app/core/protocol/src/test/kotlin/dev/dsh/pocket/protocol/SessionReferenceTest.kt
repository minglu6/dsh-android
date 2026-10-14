package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionReferenceTest {
    @Test fun preservesCanonicalMentionAndCandidatePresentationMetadata() {
        val candidate = SessionReference.parse(PocketJson.parseToJsonElement("""
            {"sessionId":"child","mention":"@[Review](dsh-session:child)",
             "displayTitle":"Review title","title":"Fallback","label":"Review","kind":"subagent",
             "createdAt":123,"updatedAt":456,"cwd":"/project","sameWorkspace":true}
        """))!!
        assertEquals("@[Review](dsh-session:child)", candidate.mention)
        assertEquals("Review title", candidate.title)
        assertEquals("child", candidate.sessionId)
        assertEquals("subagent", candidate.kind)
        assertEquals(123L, candidate.createdAt)
        assertEquals(456L, candidate.updatedAt)
        assertEquals("/project", candidate.cwd)
        assertEquals(true, candidate.sameWorkspace)
    }

    @Test fun doesNotInventNoncanonicalMentionFromSessionId() {
        assertNull(SessionReference.parse(PocketJson.parseToJsonElement("""{"sessionId":"other","label":"Other"}""")))
    }

    @Test fun labelProvidesTitleWhenDisplayTitleIsUnavailable() {
        val candidate = SessionReference.parse(PocketJson.parseToJsonElement("""
            {"mention":"@[Other](dsh-session:other)","label":"Other"}
        """))!!
        assertEquals("Other", candidate.title)
    }
}
