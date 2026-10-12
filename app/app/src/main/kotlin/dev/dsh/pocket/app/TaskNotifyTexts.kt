package dev.dsh.pocket.app

/**
 * Adapted from saya-ch/dsh-mobile (Apache-2.0) native-mobile task labels.
 * Builds notification title/body locally from session title + kind.
 * Uses desktop-aligned locale strings via [L].
 */
object TaskNotifyTexts {
    fun title(kind: TaskEvent.Kind): String = when (kind) {
        TaskEvent.Kind.Done -> L.taskFinished()
        TaskEvent.Kind.Question -> L.inputNeeded()
    }

    fun body(kind: TaskEvent.Kind, sessionTitle: String?): String {
        val label = sessionTitle?.trim().orEmpty()
        return when (kind) {
            TaskEvent.Kind.Done -> if (label.isEmpty()) L.taskFinishedBody() else L.taskFinishedBodyNamed(label)
            TaskEvent.Kind.Question -> if (label.isEmpty()) L.waitingForAnswer() else L.waitingForAnswerNamed(label)
        }
    }

    /** Stable notification tag so concurrent sessions keep separate entries. */
    fun tag(event: TaskEvent): String = when (event.kind) {
        TaskEvent.Kind.Done -> "dsh-task-done-${event.sessionId}-${event.turn}"
        TaskEvent.Kind.Question -> "dsh-task-question-${event.questionId ?: event.sessionId}"
    }
}
