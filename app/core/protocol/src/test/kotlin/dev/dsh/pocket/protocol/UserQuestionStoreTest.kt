package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserQuestionStoreTest {
    private val store = UserQuestionStore()
    private fun json(text: String) = PocketJson.parseToJsonElement(text) as JsonObject
    private fun question(id: String = "call", state: String = "open") =
        """{"callId":"$id","state":"$state","questions":[{"id":"q","question":"Choose an update","options":[{"label":"Rebase (推荐)","description":"Keep the local commit"},{"label":"Merge"}]}]}"""
    private fun baseline(seq: Long, active: String, inbox: String = "{}", session: String = "s1") = store.apply(session,
        json("""{"type":"snapshot","cursor":$seq,"projections":{"asOfSeq":$seq,"values":{"userQuestions":{"active":[$active],"settled":[]},"inbox":$inbox}}}"""))
    private fun projection(seq: Long, active: String, session: String = "s1") = store.apply(session,
        json("""{"type":"projection","sessionId":"$session","key":"userQuestions","seq":$seq,"value":{"active":[$active],"settled":[]}}"""))
    private fun inbox(seq: Long, value: String) = store.apply("s1",
        json("""{"type":"projection","sessionId":"s1","key":"inbox","seq":$seq,"value":$value}"""))

    @Test fun openingExistingSessionRecoversQuestionsWithoutAnOfferPush() {
        baseline(20, question())
        val recovered = store.questions().single()
        assertEquals("s1", recovered.sessionId)
        assertEquals("call", recovered.callId)
        assertEquals("Keep the local commit", recovered.questions.single().options.first().description)
        assertEquals("open", recovered.state)
    }

    @Test fun continuedProjectionSurvivesForegroundCloseAndClosesWhenAnsweredElsewhere() {
        store.offer(json("""{"questionId":"call","sessionId":"s1","callId":"call","wait":{"callId":"call"},"questions":[{"id":"q","question":"Choose"}]}"""))
        baseline(20, question())
        projection(22, question(state = "continued"))
        store.close("call")
        assertEquals("continued", store.questions().single().state)
        projection(21, question())
        assertEquals("continued", store.questions().single().state)
        projection(23, "")
        assertTrue(store.questions().isEmpty())
    }

    @Test fun queuedReplySuppressesDuplicateAnswerAndDiscardReopensQuestion() {
        val reply = """{"next-step":[{"id":"reply","source":{"kind":"user-question-reply","callId":"call"}}]}"""
        baseline(20, question(state = "continued"), reply)
        assertTrue(store.questions().isEmpty())
        inbox(21, "{}")
        assertEquals("call", store.questions().single().callId)
        store.submitted("s1", "call")
        assertTrue(store.questions().isEmpty())
        projection(22, question(state = "continued"))
        assertTrue(store.questions().isEmpty())
        inbox(23, reply)
        inbox(24, "{}")
        assertEquals("call", store.questions().single().callId)
        inbox(22, reply)
        assertEquals("call", store.questions().single().callId)
    }

    @Test fun continuedTransitionReleasesUnconfirmedForegroundSubmission() {
        baseline(20, question())
        store.submitted("s1", "call")
        projection(21, question())
        assertTrue(store.questions().isEmpty())
        projection(22, question(state = "continued"))
        assertEquals("continued", store.questions().single().state)
    }

    @Test fun sessionsRemainIsolatedAndReconnectAllowsLowerHostWatermark() {
        baseline(50, question("first"))
        baseline(80, question("second"), session = "s2")
        projection(51, "")
        assertEquals("s2", store.questions().single().sessionId)
        store.clear()
        baseline(2, question("restored", "continued"))
        assertEquals("restored", store.questions().single().callId)
        store.close("restored", "s2")
        assertEquals("restored", store.questions().single().callId)
        store.apply("s1", json("""{"type":"projection","sessionId":"s2","key":"userQuestions","seq":99,"value":{"active":[]}}"""))
        assertEquals("restored", store.questions().single().callId)
    }

    @Test fun copiedCallIdsRemainScopedToTheirSession() {
        baseline(10, question(), session = "s1")
        baseline(10, question(), session = "s2")
        assertEquals(setOf("s1", "s2"), store.questions().map { it.sessionId }.toSet())
        store.submitted("s1", "call")
        assertEquals("s2", store.questions().single().sessionId)
        store.close("call", "s1")
        assertEquals("s2", store.questions().single().sessionId)
    }

    @Test fun legacyUnkeyedOfferSurvivesSnapshotUntilExplicitClose() {
        store.offer(json("""{"questionId":"legacy","sessionId":"s1","questions":[{"id":"q","question":"Choose"}]}"""))
        baseline(10, "")
        assertEquals("legacy", store.questions().single().questionId)
        store.close("legacy")
        assertTrue(store.questions().isEmpty())
    }
}
