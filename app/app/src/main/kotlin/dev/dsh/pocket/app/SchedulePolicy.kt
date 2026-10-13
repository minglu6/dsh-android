package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The CAS value is a persisted record, never catalog metadata or a reconstructed rule. */
internal fun scheduleSnapshot(row: JsonObject): JsonObject {
    val fields = listOf("id", "kind", "title", "prompt", "scheduledAt") + when (row["kind"].str()) {
        "at" -> emptyList()
        "after" -> listOf("afterSeconds")
        "every" -> listOf("everySeconds")
        "daily" -> listOf("time", "timeZone")
        "weekly" -> listOf("time", "timeZone", "weekdays")
        "cron" -> listOf("expression", "timeZone")
        else -> error("Unsupported schedule kind")
    }
    return JsonObject(fields.associateWith { row[it] ?: error("Missing schedule field: $it") })
}

internal data class ScheduleTimingDraft(
    val kind: String,
    val date: String,
    val time: String,
    val zone: String,
    val quantity: String,
    val unit: String,
    val weekdays: Set<Int>,
    val expression: String,
)

internal fun scheduleTimingDraft(row: JsonObject, deviceZone: String = ZoneId.systemDefault().id): ScheduleTimingDraft {
    val zone = row["timeZone"].str() ?: deviceZone
    val wall = try { Instant.parse(row["scheduledAt"].str()).atZone(ZoneId.of(zone)) } catch (_: Exception) { null }
    val seconds = row["everySeconds"].long() ?: 3600L
    val unit = when { seconds % 3600L == 0L -> "hour"; seconds % 60L == 0L -> "minute"; else -> "second" }
    val days = row["weekdays"].arr()?.mapNotNull { it.int() }?.toSet() ?: setOf(wall?.dayOfWeek?.value ?: 1)
    val kind = when (val stored = row["kind"].str()) {
        "at", "after" -> "once"
        "weekly" -> if (days == setOf(1, 2, 3, 4, 5)) "weekdays" else "weekly"
        else -> stored ?: error("Missing schedule kind")
    }
    return ScheduleTimingDraft(
        kind, wall?.toLocalDate()?.toString().orEmpty(),
        row["time"].str() ?: wall?.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) ?: "09:00:00.000",
        zone, (seconds / scheduleUnitSeconds(unit)).toString(), unit, days,
        row["expression"].str() ?: "0 9 * * *",
    )
}

/** Native pickers edit hours/minutes only; retain the original seconds and fraction verbatim. */
internal fun schedulePickedTime(previous: String, hour: Int, minute: Int): String {
    require(hour in 0..23 && minute in 0..59)
    val valid = try { LocalTime.parse(previous); true } catch (_: Exception) { false }
    val suffix = if (valid) previous.drop(5) else ":00.000"
    return hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0') + suffix
}

internal fun scheduleUnitSeconds(unit: String): Long = when (unit) {
    "hour" -> 3600L
    "minute" -> 60L
    "second" -> 1L
    else -> error("Unknown interval unit")
}

/** Exact decimal conversion; changing units never rounds a stored 61-second interval. */
internal fun scheduleConvertQuantity(value: String, from: String, to: String): String? = try {
    BigDecimal(value).multiply(BigDecimal(scheduleUnitSeconds(from)))
        .divide(BigDecimal(scheduleUnitSeconds(to))).stripTrailingZeros().toPlainString()
} catch (_: ArithmeticException) { null } catch (_: NumberFormatException) { null }

internal fun scheduleIntervalSeconds(value: String, unit: String): Long? = try {
    BigDecimal(value).multiply(BigDecimal(scheduleUnitSeconds(unit))).longValueExact()
        .takeIf { it in 60..9_007_199_254_740_991L }
} catch (_: ArithmeticException) { null } catch (_: NumberFormatException) { null }

/** Validate the numeric five-field Vixie dialect; occurrence/DST interpretation stays on Host. */
internal fun scheduleCronValid(expression: String): Boolean {
    val fields = expression.trim().split(Regex("\\s+"))
    val bounds = listOf(0..59, 0..23, 1..31, 1..12, 0..7)
    if (fields.size != 5) return false
    return fields.zip(bounds).all { (field, range) ->
        field.split(',').all element@{ part ->
            if (part == "*") return@element true
            val star = Regex("\\*/(\\d+)").matchEntire(part)
            if (star != null) return@element star.groupValues[1].toLongOrNull()?.let { it in 1..9_007_199_254_740_991L } == true
            val match = Regex("(\\d+)(?:-(\\d+))?(?:/(\\d+))?").matchEntire(part) ?: return@element false
            val start = match.groupValues[1].toIntOrNull() ?: return@element false
            val endText = match.groupValues[2]
            val stepText = match.groupValues[3]
            if (start !in range) return@element false
            if (endText.isEmpty()) return@element stepText.isEmpty()
            val end = endText.toIntOrNull() ?: return@element false
            end in range && end >= start && (stepText.isEmpty() || stepText.toLongOrNull()?.let { it in 1..9_007_199_254_740_991L } == true)
        }
    }
}

internal data class ScheduleCronShape(
    val kind: String,
    val step: Int = 1,
    val hour: Int = 9,
    val minute: Int = 0,
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val days: Set<Int> = setOf(1),
)

/** Recognize only the official builder's five shapes; Vixie day-field unions stay raw. */
internal fun scheduleCronShape(expression: String): ScheduleCronShape? {
    if (!scheduleCronValid(expression)) return null
    val fields = expression.trim().split(Regex("\\s+"))
    val bounds = listOf(0..59, 0..23, 1..31, 1..12, 0..7)
    val values = fields.zip(bounds).map { (field, range) ->
        field.split(',').flatMap { part ->
            val base = part.substringBefore('/')
            val step = part.substringAfter('/', "1").toLong()
            val start = if (base == "*") range.first else base.substringBefore('-').toInt()
            val end = if (base == "*") range.last else base.substringAfter('-', base).toInt()
            (start..end).filter { (it - start).toLong() % step == 0L }
        }.toSortedSet()
    }
    if (values[3] != (1..12).toSet()) return null
    fun fullStep(index: Int): Int? = (1..bounds[index].last).firstOrNull { step ->
        values[index] == bounds[index].filter { it % step == 0 }.toSet()
    }
    val minute = values[0].singleOrNull()
    val hour = values[1].singleOrNull()
    if (fields[4] == "*") {
        if (fields[2] == "*") {
            val minuteStep = fullStep(0)
            if (minuteStep != null && values[1] == (0..23).toSet()) return ScheduleCronShape("minutely", step = minuteStep)
            val hourStep = fullStep(1)
            if (minute != null && hourStep != null) return ScheduleCronShape("hourly", step = hourStep, minute = minute)
            if (minute != null && hour != null) return ScheduleCronShape("daily", hour = hour, minute = minute)
            return null
        }
        if (minute != null && hour != null) return ScheduleCronShape("monthly", hour = hour, minute = minute, days = values[2])
        return null
    }
    if (fields[2] != "*" || minute == null || hour == null) return null
    return ScheduleCronShape("weekly", hour = hour, minute = minute, weekdays = values[4].map { if (it == 0) 7 else it }.toSet())
}

internal fun scheduleCronExpression(shape: ScheduleCronShape): String {
    require(shape.hour in 0..23 && shape.minute in 0..59)
    fun field(values: Set<Int>): String {
        val sorted = values.sorted()
        val runs = mutableListOf<String>()
        var index = 0
        while (index < sorted.size) {
            val start = index
            while (index + 1 < sorted.size && sorted[index + 1] == sorted[index] + 1) index++
            runs += if (index - start >= 2) "${sorted[start]}-${sorted[index]}" else sorted.subList(start, index + 1).joinToString(",")
            index++
        }
        return runs.joinToString(",")
    }
    return when (shape.kind) {
        "minutely" -> {
            require(shape.step in 1..59)
            "${if (shape.step == 1) "*" else "*/${shape.step}"} * * * *"
        }
        "hourly" -> {
            require(shape.step in 1..23)
            "${shape.minute} ${if (shape.step == 1) "*" else "*/${shape.step}"} * * *"
        }
        "daily" -> "${shape.minute} ${shape.hour} * * *"
        "weekly" -> {
            require(shape.weekdays.isNotEmpty() && shape.weekdays.all { it in 1..7 })
            "${shape.minute} ${shape.hour} * * ${field(shape.weekdays.map { it % 7 }.toSet())}"
        }
        "monthly" -> {
            require(shape.days.isNotEmpty() && shape.days.all { it in 1..31 })
            "${shape.minute} ${shape.hour} ${field(shape.days)} * *"
        }
        else -> error("Unsupported cron shape")
    }
}

internal data class ScheduleRelativeTime(val direction: Int, val value: Long, val unit: String)

internal fun scheduleRelativeTime(scheduledAt: String?, now: Instant): ScheduleRelativeTime? {
    val target = try { Instant.parse(scheduledAt) } catch (_: Exception) { return null }
    val millis = java.time.Duration.between(now, target).toMillis()
    val seconds = kotlin.math.abs(millis.toDouble()) / 1000
    val unit = listOf("day" to 86400, "hour" to 3600, "minute" to 60, "second" to 1).first { seconds >= it.second || it.first == "second" }
    val count = if (millis > 0) kotlin.math.ceil(seconds / unit.second) else kotlin.math.floor(seconds / unit.second)
    return ScheduleRelativeTime(millis.compareTo(0L), count.toLong().coerceAtLeast(1), unit.first)
}

internal fun scheduleTimingChange(draft: ScheduleTimingDraft): JsonObject {
    if (draft.kind == "every") {
        val seconds = scheduleIntervalSeconds(draft.quantity, draft.unit) ?: throw IllegalArgumentException("frequency_too_high")
        return jsonOf("kind" to "every", "every_seconds" to seconds)
    }
    val zone = draft.zone.trim()
    require(zone == "UTC" || (zone.contains('/') && !zone.any { it.isWhitespace() })) { "invalid_time_zone" }
    if (draft.kind == "cron") {
        require(scheduleCronValid(draft.expression)) { "invalid_cron" }
        return jsonOf("kind" to "cron", "cron" to jsonOf("expression" to draft.expression, "time_zone" to zone))
    }
    val time = draft.time.trim()
    require(Regex("\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,3})?)?").matches(time)) { "invalid_rule" }
    try { LocalTime.parse(time) } catch (_: Exception) { throw IllegalArgumentException("invalid_rule") }
    val normalizedTime = if (time.length == 5) "$time:00" else time
    return when (draft.kind) {
        "once" -> {
            require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(draft.date)) { "invalid_rule" }
            try { LocalDate.parse(draft.date) } catch (_: Exception) { throw IllegalArgumentException("invalid_rule") }
            jsonOf("kind" to "at", "at" to jsonOf("date" to draft.date, "time" to normalizedTime, "time_zone" to zone))
        }
        "daily" -> jsonOf("kind" to "daily", "daily" to jsonOf("time" to normalizedTime, "time_zone" to zone))
        "weekly", "weekdays" -> {
            val days = if (draft.kind == "weekdays") setOf(1, 2, 3, 4, 5) else draft.weekdays
            require(days.isNotEmpty() && days.all { it in 1..7 }) { "invalid_rule" }
            jsonOf("kind" to "weekly", "weekly" to jsonOf("time" to normalizedTime, "time_zone" to zone,
                "weekdays" to JsonArray(days.sorted().map { JsonPrimitive(it) })))
        }
        else -> error("Unsupported schedule kind")
    }
}

internal fun scheduleUpdateRequest(row: JsonObject, title: String, prompt: String, initial: ScheduleTimingDraft, draft: ScheduleTimingDraft): JsonObject {
    require(title.trim().isNotEmpty() && title.trim().length <= 120) { "invalid_title" }
    require(prompt.trim().isNotEmpty()) { "invalid_prompt" }
    return buildJsonObject {
        put("id", row.getValue("id"))
        put("sessionId", row.getValue("sessionId"))
        put("expected", scheduleSnapshot(row))
        if (title.trim() != row["title"].str()) put("title", title.trim())
        if (prompt.trim() != row["prompt"].str()) put("prompt", prompt.trim())
        if (initial != draft) put("change", scheduleTimingChange(draft))
    }
}

/** updated:false plus record is a confirmed no-op, not a conflict. */
internal fun scheduleUpdatedRecord(result: JsonElement): JsonObject {
    result["code"].str()?.let { throw IllegalStateException(listOfNotNull(it, result["message"].str()).joinToString(": ")) }
    check(result["updated"].bool() != null) { "Invalid schedule update response" }
    return result["record"] as? JsonObject ?: error("Invalid schedule update response: missing record")
}

internal fun scheduleConfirmDeleted(result: JsonElement) {
    check(result["deleted"].bool() == true) {
        listOfNotNull(result["code"].str(), result["message"].str()).joinToString(": ").ifBlank { "Could not confirm task deletion" }
    }
}
