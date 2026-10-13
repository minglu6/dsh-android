package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.ModelGroup
import dev.dsh.pocket.protocol.ModelInfo
import java.util.Locale

/** Matches the official model picker provider order and name-only fuzzy ranking. */
internal fun modelPickerGroups(groups: List<ModelGroup>, query: String): List<ModelGroup> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val ordered = groups.sortedBy {
        when (it.id) {
            "deepseek-account" -> 0
            "deepseek-official" -> 1
            else -> 2
        }
    }
    return ordered.mapNotNull { group ->
        if (normalizedQuery.isEmpty()) return@mapNotNull group.takeIf { it.models.isNotEmpty() }
        val ranked = ArrayList<RankedPickerModel>()
        group.models.forEachIndexed { index, model ->
            val name = model.name.lowercase(Locale.ROOT)
            val score = modelNameAlignmentScore(name, normalizedQuery)
            if (score != NO_MODEL_MATCH) {
                ranked.add(RankedPickerModel(model, index, name.startsWith(normalizedQuery), score))
            }
        }
        if (ranked.isEmpty()) return@mapNotNull null
        ranked.sortWith(compareByDescending<RankedPickerModel> { it.prefix }
            .thenByDescending { it.score }
            .thenBy { it.index })
        group.copy(models = ranked.map { it.model })
    }
}

private data class RankedPickerModel(val model: ModelInfo, val index: Int, val prefix: Boolean, val score: Int)

private const val NO_MODEL_MATCH = Int.MIN_VALUE

private fun modelNameBoundaryBonus(name: String, index: Int): Int =
    if (index == 0 || name[index - 1] == '-' || name[index - 1] == '_') 8 else 0

/** Strongest ordered-subsequence alignment, using two reusable dynamic-programming rows. */
private fun modelNameAlignmentScore(name: String, query: String): Int {
    if (query.length > name.length) return NO_MODEL_MATCH
    var previous = IntArray(name.length) { NO_MODEL_MATCH }
    var current = IntArray(name.length)
    for (index in name.indices) {
        if (name[index] == query[0]) previous[index] = 1 + modelNameBoundaryBonus(name, index) - index
    }
    for (queryIndex in 1 until query.length) {
        current.fill(NO_MODEL_MATCH)
        var left = NO_MODEL_MATCH
        var leftLeft = NO_MODEL_MATCH
        var bestGapped = NO_MODEL_MATCH
        for (index in name.indices) {
            val prior = previous[index]
            if (leftLeft != NO_MODEL_MATCH) bestGapped = maxOf(bestGapped, leftLeft + index - 2)
            if (name[index] == query[queryIndex]) {
                val bonus = 1 + modelNameBoundaryBonus(name, index)
                var score = NO_MODEL_MATCH
                if (left != NO_MODEL_MATCH) score = left + bonus + 4
                if (bestGapped != NO_MODEL_MATCH) score = maxOf(score, bestGapped + bonus + 1 - index)
                current[index] = score
            }
            leftLeft = left
            left = prior
        }
        val reusable = previous
        previous = current
        current = reusable
    }
    var best = NO_MODEL_MATCH
    for (score in previous) best = maxOf(best, score)
    return best
}
