package dev.dsh.pocket.app

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.UserQuestion
import dev.dsh.pocket.protocol.UserQuestionItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal data class QuestionDraft(
    val selected: Set<String> = emptySet(),
    val custom: String = "",
    val skipped: Boolean = false,
) {
    val answered: Boolean get() = selected.isNotEmpty() || custom.isNotBlank()
    val completed: Boolean get() = skipped || answered
}

private val recommendationSuffix = Regex("\\s*(?:\\((?:recommended|推荐)\\)|（(?:recommended|推荐)）)\\s*$", RegexOption.IGNORE_CASE)

internal fun parseRecommendedLabel(label: String): Pair<String, Boolean> {
    val suffix = recommendationSuffix.find(label) ?: return label to false
    return label.substring(0, suffix.range.first) to true
}

// Match the official composer: only a marked first option is an implicit draft.
internal fun initialQuestionDraft(item: UserQuestionItem): QuestionDraft {
    val first = item.options.firstOrNull()?.label
    return QuestionDraft(selected = if (first != null && parseRecommendedLabel(first).second) setOf(first) else emptySet())
}

internal val questionDraftSaver = listSaver<MutableMap<String, SnapshotStateMap<String, QuestionDraft>>, ArrayList<String>>(
    save = { requests -> requests.flatMap { (requestId, answers) ->
        answers.map { (id, draft) -> ArrayList(listOf(requestId, id, draft.custom, draft.skipped.toString()) + draft.selected) }
    } },
    restore = { records ->
        mutableMapOf<String, SnapshotStateMap<String, QuestionDraft>>().apply {
            records.forEach { fields ->
                getOrPut(fields[0]) { mutableStateMapOf() }[fields[1]] =
                    QuestionDraft(fields.drop(4).toSet(), fields[2], fields[3].toBoolean())
            }
        }
    },
)

internal fun questionAnswers(q: UserQuestion, drafts: Map<String, QuestionDraft>) = q.questions.map { item ->
    val draft = drafts[item.id] ?: initialQuestionDraft(item)
    val custom = if (draft.skipped) "" else draft.custom.trim()
    buildJsonObject {
        put("id", item.id)
        putJsonArray("selected") {
            if (!draft.skipped && (custom.isEmpty() || item.multiSelect)) {
                draft.selected.forEach { add(JsonPrimitive(it)) }
            }
        }
        if (custom.isNotEmpty()) put("custom", custom)
    }
}

@Composable
internal fun UserQuestionCard(
    q: UserQuestion,
    drafts: MutableMap<String, QuestionDraft>,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    onAnswer: (String, List<JsonObject>, (Boolean) -> Unit) -> Unit = { id, answers, done -> Pocket.answerUserQuestion(id, answers, done) },
) {
    if (q.questions.isEmpty()) return
    var index by rememberSaveable(q.questionId) { mutableIntStateOf(0) }
    var busy by remember(q.questionId, q.state) { mutableStateOf(false) }
    var err by remember(q.questionId, q.state) { mutableStateOf<String?>(null) }
    val currentIndex = index.coerceIn(q.questions.indices)
    val item = q.questions[currentIndex]
    val draft = drafts[item.id] ?: initialQuestionDraft(item)
    val writable = enabled && !busy
    val isPlan = q.questions.size == 1 && item.intentKind == "plan-review" &&
        !item.multiSelect && item.options.size <= 2 && item.options.any { it.label == item.approveLabel }
    val bodyScroll = rememberScrollState()
    LaunchedEffect(q.questionId, currentIndex) { bodyScroll.scrollTo(0) }

    fun submit() {
        if (!writable) return
        val missing = q.questions.indexOfFirst { !(drafts[it.id] ?: initialQuestionDraft(it)).completed }
        if (missing >= 0) {
            index = missing
            err = "请回答或明确跳过所有问题后再提交"
            return
        }
        busy = true
        err = null
        onAnswer(q.callId ?: q.questionId, questionAnswers(q, drafts)) { success ->
            if (!success) { busy = false; err = "提交失败，答案已保留，请重试" }
        }
    }

    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().heightIn(max = 420.dp)
        .clip(T.R16).border(1.dp, T.Border, T.R16).testTag("userQuestionCard")) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp).testTag("questionHeader"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (isPlan) L.planReview() else "${item.header ?: "问题"} · ${currentIndex + 1}/${q.questions.size}",
                fontSize = 14.sp, color = T.Text2, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss, enabled = !busy) { Text("收起") }
        }
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(bodyScroll)
            .padding(horizontal = 18.dp, vertical = 8.dp).testTag("questionBody")) {
            SelectableOutput {
                Column {
                    Text(item.question, fontSize = 16.sp, color = T.Ink)
                    item.detail?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        MarkdownView(it)
                    }
                }
            }
            if (!isPlan) {
                Spacer(Modifier.height(12.dp))
                item.options.forEachIndexed { optionIndex, opt ->
                    val selected = opt.label in draft.selected
                    val (label, recommended) = parseRecommendedLabel(opt.label)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(T.R8)
                        .border(1.dp, if (selected) T.Accent else T.Border, T.R8)
                        .semantics { this.selected = selected }
                        .testTag("questionOption:$optionIndex")
                        .clickable(enabled = writable, role = if (item.multiSelect) Role.Checkbox else Role.RadioButton) {
                            drafts[item.id] = if (item.multiSelect) draft.copy(
                                selected = if (selected) draft.selected - opt.label else draft.selected + opt.label,
                                skipped = false,
                            ) else QuestionDraft(selected = setOf(opt.label))
                            err = null
                            if (!item.multiSelect && currentIndex < q.questions.lastIndex) index = currentIndex + 1
                        }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label, fontSize = 15.sp, color = T.Ink)
                            if (recommended) Text("推荐", fontSize = 12.sp, color = T.Accent)
                            opt.description?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = T.Text3) }
                        }
                        if (selected) Icon(D.check, "已选择", tint = T.Accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedTextField(draft.custom, {
                    drafts[item.id] = draft.copy(custom = it,
                        selected = if (item.multiSelect) draft.selected else emptySet(), skipped = false)
                    err = null
                }, enabled = writable, placeholder = { Text(L.typeYourAnswer()) }, shape = T.R12,
                    modifier = Modifier.fillMaxWidth().testTag("questionCustom"), maxLines = 4)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp).testTag("questionFooter")) {
            err?.let { Text(it, fontSize = 12.sp, color = T.Err) }
            if (busy) Text("正在提交…", color = T.Text3, modifier = Modifier.padding(bottom = 8.dp))
            else if (q.state == "continued") Text("智能体已继续工作，仍可补充答案", fontSize = 12.sp, color = T.Text3)
            if (isPlan) {
                Button({
                    drafts[item.id] = QuestionDraft(setOf(item.approveLabel!!))
                    submit()
                }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = writable, shape = T.R12, colors = PrimaryColors()) {
                    Text(L.approvePlan())
                }
                OutlinedButton(onDismiss, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy, shape = T.R12) {
                    Text("继续讨论")
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (currentIndex > 0) TextButton({ index = currentIndex - 1; err = null }, enabled = !busy) { Text("上一题") }
                    TextButton({
                        drafts[item.id] = QuestionDraft(skipped = true)
                        err = null
                        if (currentIndex < q.questions.lastIndex) index = currentIndex + 1 else submit()
                    }, enabled = writable) { Text(L.skip()) }
                    Spacer(Modifier.weight(1f))
                    Button({
                        if (currentIndex == q.questions.lastIndex) submit()
                        else if (!draft.completed) err = L.completeQuestionFirst()
                        else { index = currentIndex + 1; err = null }
                    }, enabled = writable, shape = T.R12, colors = PrimaryColors()) {
                        Text(if (currentIndex < q.questions.lastIndex) L.nextQuestion() else "提交答案")
                    }
                }
            }
        }
    }
}
