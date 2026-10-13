package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.SessionSummary
import org.junit.Assert.*
import org.junit.Test

class NavigationPolicyTest {
    private fun session(id: String, parent: String? = null, origin: String? = null, blank: Boolean = false, time: Long = 1) =
        SessionSummary(id, id, "/work/project", false, blank, time, parent, origin = origin)

    @Test fun forksRemainVisibleButSubagentsAndInactiveBlanksDoNot() {
        assertTrue(visibleDrawerSession(session("fork", "parent"), null, emptySet(), ArchivedFilter.Default))
        assertFalse(visibleDrawerSession(session("child", "parent", "subagent"), "child", emptySet(), ArchivedFilter.Show))
        assertTrue(visibleDrawerSession(session("blank", blank = true), "blank", emptySet(), ArchivedFilter.Default))
        assertFalse(visibleDrawerSession(session("blank", blank = true), "other", emptySet(), ArchivedFilter.Default))
    }

    @Test fun archiveFiltersRemainConsistentForAllViews() {
        val s = session("archived")
        assertFalse(visibleDrawerSession(s, null, setOf(s.sessionId), ArchivedFilter.Default))
        assertTrue(visibleDrawerSession(s, null, setOf(s.sessionId), ArchivedFilter.Show))
        assertTrue(visibleDrawerSession(s, null, setOf(s.sessionId), ArchivedFilter.Only))
        assertFalse(visibleDrawerSession(s, null, emptySet(), ArchivedFilter.Only))
    }

    @Test fun treeUsesFullPathsAndNearestRegisteredAncestor() {
        val tree = workspaceBranches(listOf("/a/project", "/b/project", "/a/project/nested", "/a/project-other", "/a/project/nested/deep"))
        assertEquals(listOf("/a/project", "/b/project", "/a/project-other"), tree.map { it.path })
        assertEquals("/a/project/nested", tree.first().children.single().path)
        assertEquals("/a/project/nested/deep", tree.first().children.single().children.single().path)
        assertTrue(tree[1].children.isEmpty())
        assertEquals("", workspacePathKey(null))
        assertEquals("", workspacePathKey(""))
        assertEquals("/", workspacePathKey("/"))
    }

    @Test fun newForksPrecedeSourceAndHiddenArchivesRetainSlots() {
        val all = listOf(session("source"), session("archived"), session("fork", "source", time = 3), session("other"))
        val order = reconcileSessionOrder(all, listOf("archived", "source", "other"))
        assertEquals(listOf("archived", "fork", "source", "other"), order)
        assertEquals(listOf("archived", "source", "fork", "other"), moveSession(order, listOf("fork", "source", "other"), "source", -1))
        assertEquals(order, moveSession(order, listOf("fork", "source"), "fork", -1))
        assertEquals(order, reconcileSessionOrder(all, order))
    }

    @Test fun cyclicLineageDoesNotRecurseForever() {
        val order = reconcileSessionOrder(listOf(session("a", "b"), session("b", "a")), emptyList())
        assertEquals(setOf("a", "b"), order.toSet())
        assertEquals(2, order.size)
    }
}
