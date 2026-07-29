package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.data.repository.DirectoryScope
import dev.minios.ocremote.domain.model.Project
import dev.minios.ocremote.domain.model.Session
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionProjectGroupingTest {
    @Test
    fun groupsByProjectIdBeforeDirectoryFallback() {
        val projects = listOf(
            Project(id = "project", worktree = "/repo", name = "Repository"),
        )
        val item = item("session", "/elsewhere", projectId = "project")

        val group = buildProjectSessionGroups(listOf(item), projects, "/home/user", emptyMap(), "server").single()

        assertEquals("project", group.projectId)
        assertEquals("Repository", group.projectName)
        assertEquals("/repo", group.directory)
    }

    @Test
    fun choosesLongestMatchingWorktreeAndKeepsBranch() {
        val projects = listOf(
            Project(id = "root", worktree = "/repo", name = "Root"),
            Project(id = "nested", worktree = "/repo/apps/mobile", name = "Mobile"),
        )
        val branches = mapOf(DirectoryScope("server", "/repo/apps/mobile") to "feature/context")

        val group = buildProjectSessionGroups(
            listOf(item("session", "/repo/apps/mobile/src")),
            projects,
            null,
            branches,
            "server",
        ).single()

        assertEquals("nested", group.projectId)
        assertEquals("feature/context", group.branch)
    }

    @Test
    fun unknownDirectoriesBecomeIndependentGroupsOrderedByActivity() {
        val groups = buildProjectSessionGroups(
            listOf(
                item("older", "/one", updated = 1),
                item("newer", "/two", updated = 2),
            ),
            emptyList(),
            null,
            emptyMap(),
            "server",
        )

        assertEquals(listOf("/two", "/one"), groups.map { it.directory })
    }

    @Test
    fun favoriteSessionsLeadAndRespectExplicitOrder() {
        val sorted = sortSessionItems(
            listOf(
                item("newest", "/repo", updated = 5),
                item("second-favorite", "/repo", updated = 4, favoriteIndex = 1),
                item("first-favorite", "/repo", updated = 1, favoriteIndex = 0),
            )
        )

        assertEquals(listOf("first-favorite", "second-favorite", "newest"), sorted.map { it.session.id })
    }

    @Test
    fun projectContainingTopFavoriteLeadsNewerProjects() {
        val groups = buildProjectSessionGroups(
            listOf(
                item("recent", "/recent", updated = 10),
                item("favorite", "/older", updated = 1, favoriteIndex = 0),
            ),
            emptyList(),
            null,
            emptyMap(),
            "server",
        )

        assertEquals(listOf("/older", "/recent"), groups.map { it.directory })
    }

    @Test
    fun recentDirectoriesKeepTwentyNewestUniqueLocations() {
        val sessions = (1L..21L).map { updated ->
            item("session-$updated", "/repo-$updated", updated = updated)
        }

        val directories = recentSessionDirectories(sessions)

        assertEquals(20, directories.size)
        assertEquals("/repo-21", directories.first().directory)
        assertEquals("/repo-2", directories.last().directory)
    }

    @Test
    fun recentDirectoriesGroupTrailingSlashesAndUseLatestActivity() {
        val directories = recentSessionDirectories(
            listOf(
                item("first", "/repo", updated = 1),
                item("second", "/repo/", updated = 3),
                item("other", "/other", updated = 2),
            )
        )

        assertEquals(listOf("/repo", "/other"), directories.map { it.directory.trimEnd('/') })
        assertEquals(2, directories.first().count)
        assertEquals(3, directories.first().lastUsed)
    }

    private fun item(
        id: String,
        directory: String,
        projectId: String = "",
        updated: Long = 1,
        favoriteIndex: Int? = null,
    ) = SessionItem(
        Session(
            id = id,
            projectId = projectId,
            directory = directory,
            time = Session.Time(created = 1, updated = updated),
        ),
        favoriteIndex = favoriteIndex,
    )
}
