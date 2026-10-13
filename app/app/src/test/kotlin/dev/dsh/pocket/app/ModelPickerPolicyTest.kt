package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.ModelGroup
import dev.dsh.pocket.protocol.ModelInfo
import org.junit.Assert.*
import org.junit.Test

class ModelPickerPolicyTest {
    @Test fun providerPriorityPreservesOtherProvidersAndEachGroupsSourceOrder() {
        val groups = listOf(
            group("third-z", "Z model", "A model"),
            group("deepseek-official", "Official second", "Official first"),
            group("third-a", "Model A"),
            group("deepseek-account", "Account second", "Account first"),
            group("third-m", "Model M"),
        )

        val result = modelPickerGroups(groups, "")

        assertEquals(listOf("deepseek-account", "deepseek-official", "third-z", "third-a", "third-m"), result.map { it.id })
        result.forEach { filtered -> assertSame(groups.first { it.id == filtered.id }.models, filtered.models) }
    }

    @Test fun queryMatchesCaseInsensitiveOrderedSubsequencesAndIsTrimmed() {
        val groups = listOf(group("provider", "DeepSeek V4 Flash", "DeepSeek V4 Pro", "Flash V4 DeepSeek"))

        val result = modelPickerGroups(groups, "  dV4f\t")

        assertEquals(listOf("DeepSeek V4 Flash"), result.single().models.map { it.name })
    }

    @Test fun prefixRanksBeforeAStrongerNonPrefixAlignment() {
        val result = modelPickerGroups(listOf(group("provider", "x-a-b", "ab")), "ab")

        assertEquals(listOf("ab", "x-a-b"), result.single().models.map { it.name })
    }

    @Test fun alignmentRewardsBoundariesAndAdjacencyThenPreservesTies() {
        val groups = listOf(group("provider", "xayb", "yyab", "x-ab", "xxab", "x_a_b"))

        val result = modelPickerGroups(groups, "ab")

        assertEquals(listOf("x_a_b", "x-ab", "yyab", "xxab", "xayb"), result.single().models.map { it.name })
    }

    @Test fun rankingUsesTheStrongestAlignmentRatherThanTheFirstMatch() {
        val result = modelPickerGroups(listOf(group("provider", "xab", "a------ab")), "ab")

        assertEquals(listOf("a------ab", "xab"), result.single().models.map { it.name })
    }

    @Test fun searchRanksOnlyWithinGroupsAndKeepsProviderPriority() {
        val groups = listOf(
            group("third-z", "ab"),
            group("deepseek-official", "x-ab"),
            group("third-a", "ab"),
            group("deepseek-account", "xayb"),
        )

        val result = modelPickerGroups(groups, "ab")

        assertEquals(listOf("deepseek-account", "deepseek-official", "third-z", "third-a"), result.map { it.id })
    }

    @Test fun unmatchedGroupsAreRemovedAndIdsAndDescriptionsAreNotSearchKeys() {
        val hiddenMatch = ModelInfo("provider", "needle", "Display model", "needle", emptyList(), null)
        val groups = listOf(
            ModelGroup("needle", "needle", listOf(hiddenMatch)),
            group("matching", "Needle model"),
            group("empty"),
        )

        assertEquals(listOf("matching"), modelPickerGroups(groups, "needle").map { it.id })
        assertTrue(modelPickerGroups(groups, "missing").isEmpty())
        assertTrue(modelPickerGroups(groups, "this query is longer than every model name").isEmpty())
        assertTrue(modelPickerGroups(emptyList(), "needle").isEmpty())
    }

    @Test fun whitespaceQueryKeepsSourceModelOrderButRemovesEmptyGroups() {
        val populated = group("provider", "Z model", "A model")
        val groups = listOf(group("deepseek-account"), populated)

        val result = modelPickerGroups(groups, " \n\t ")

        assertEquals(listOf(populated), result)
        assertSame(populated.models, result.single().models)
    }

    @Test fun rankingAndFilteringDoNotRewriteTheCatalog() {
        val originalModels = mutableListOf(
            ModelInfo("provider", "first", "x-a-b", null, emptyList(), null),
            ModelInfo("provider", "second", "ab", null, emptyList(), null),
            ModelInfo("provider", "third", "Other", null, emptyList(), null),
        )
        val originalGroup = ModelGroup("provider", "Provider", originalModels)
        val groups = mutableListOf(originalGroup, group("deepseek-account", "Account"))
        val originalGroupOrder = groups.toList()
        val originalModelOrder = originalModels.toList()

        val filtered = modelPickerGroups(groups, "ab")
        val unfiltered = modelPickerGroups(groups, "")

        assertEquals(listOf("second", "first"), filtered.single().models.map { it.id })
        assertSame(originalModels[1], filtered.single().models[0])
        assertSame(originalModels, unfiltered.first { it.id == "provider" }.models)
        assertEquals(originalGroupOrder, groups)
        assertEquals(originalModelOrder, originalModels)
        assertSame(originalGroup, groups[0])
    }

    private fun group(id: String, vararg names: String) = ModelGroup(
        id, id, names.mapIndexed { index, name -> ModelInfo(id, "model-$index", name, null, emptyList(), null) },
    )
}
