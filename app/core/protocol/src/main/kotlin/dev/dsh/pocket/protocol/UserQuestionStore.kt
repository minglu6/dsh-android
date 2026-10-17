package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Merge live offers with the durable question/inbox projections, including reconnects. */
class UserQuestionStore {
    private data class SessionQuestions(
        var questionSeq: Long = -1,
        var inboxSeq: Long = -1,
        var active: List<UserQuestion> = emptyList(),
        var queued: Set<String> = emptySet(),
        val submitted: MutableMap<String, String?> = mutableMapOf(),
    )

    private val sessions = mutableMapOf<String, SessionQuestions>()
    private val offers = linkedMapOf<Pair<String?, String>, UserQuestion>()

    fun clear() {
        sessions.clear()
        offers.clear()
    }

    fun offer(value: JsonObject) {
        val question = UserQuestion.parse(value) ?: return
        offers[question.sessionId to question.questionId] = question
    }

    fun close(questionId: String, sessionId: String? = null) {
        offers.entries.removeAll { (_, question) ->
            question.questionId == questionId && (sessionId == null || question.sessionId == sessionId)
        }
        // A closed foreground request may already have become a continued question.
        sessions.forEach { (id, session) ->
            if (sessionId == null || id == sessionId) {
                session.active = session.active.filter { it.questionId != questionId || it.state == "continued" }
            }
        }
    }

    fun submitted(sessionId: String, callId: String) {
        val session = sessions.getOrPut(sessionId) { SessionQuestions() }
        val question = session.active.find { it.callId == callId } ?: offers[sessionId to callId]
        session.submitted[callId] = question?.state
    }

    fun apply(sessionId: String, frame: JsonElement?) {
        val frameSession = frame["sessionId"].str()
        if (frameSession != null && frameSession != sessionId) return
        when (frame["type"].str()) {
            "snapshot", "resume" -> {
                val projections = frame["projections"]
                val values = projections["values"] as? JsonObject ?: return
                val seq = projections["asOfSeq"].long() ?: frame["cursor"].long() ?: return
                applyQuestions(sessionId, seq, values["userQuestions"])
                applyInbox(sessionId, seq, values["inbox"])
            }
            "projection" -> {
                val seq = frame["seq"].long() ?: return
                when (frame["key"].str()) {
                    "userQuestions" -> applyQuestions(sessionId, seq, frame["value"])
                    "inbox" -> applyInbox(sessionId, seq, frame["value"])
                }
            }
        }
    }

    private fun applyQuestions(sessionId: String, seq: Long, value: JsonElement?) {
        val session = sessions.getOrPut(sessionId) { SessionQuestions() }
        if (seq < session.questionSeq) return
        session.questionSeq = seq
        session.active = value["active"].arr().orEmpty().mapNotNull { entry ->
            val fields = entry as? JsonObject ?: return@mapNotNull null
            UserQuestion.parse(JsonObject(fields + ("sessionId" to JsonPrimitive(sessionId))))
        }
        val activeIds = session.active.mapTo(mutableSetOf()) { it.questionId }
        offers.entries.removeAll { (_, question) ->
            question.sessionId == sessionId && question.state != null && question.questionId !in activeIds
        }
        session.submitted.entries.removeAll { (id, state) ->
            val current = session.active.find { it.callId == id }
            current == null || current.state != state
        }
    }

    private fun applyInbox(sessionId: String, seq: Long, value: JsonElement?) {
        val session = sessions.getOrPut(sessionId) { SessionQuestions() }
        if (seq < session.inboxSeq) return
        session.inboxSeq = seq
        val queued = listOf("next-turn", "next-step").flatMap { target ->
            value[target].arr().orEmpty().mapNotNull { message ->
                message["source"].takeIf { it["kind"].str() == "user-question-reply" }?.get("callId").str()
            }
        }.toSet()
        // Discarding a queued reply makes its still-active question answerable again.
        (session.queued - queued).forEach { session.submitted.remove(it) }
        session.queued = queued
    }

    fun questions(): List<UserQuestion> {
        val result = LinkedHashMap(offers)
        sessions.forEach { (_, session) -> session.active.forEach { result[it.sessionId to it.questionId] = it } }
        return result.values.filter { question ->
            val session = sessions[question.sessionId]
            question.callId !in session?.queued.orEmpty() &&
                !session?.submitted.orEmpty().containsKey(question.callId)
        }
    }
}
