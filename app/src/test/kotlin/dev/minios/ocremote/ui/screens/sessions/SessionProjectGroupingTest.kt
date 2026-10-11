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
    fun globalSessionWithKnownProjectDirectoryGroupsByProject() {
        val projects = listOf(
            Project(id = "global", worktree = "/", name = "Global"),
            Project(id = "project-a", worktree = "/home/user/projects/foo", name = "Foo"),
        )

        val group = buildProjectSessionGroups(
            listOf(item("session", "/home/user/projects/foo", projectId = "global")),
            projects,
            "/home/user",
            emptyMap(),
            "server",
        ).single()

        assertEquals("project-a", group.projectId)
        assertEquals("Foo", group.projectName)
    }

    @Test
    fun globalSessionsInUnknownDirectoriesBecomeSeparateGroups() {
        val projects = listOf(
            Project(id = "global", worktree = "/", name = "Global"),
        )

        val groups = buildProjectSessionGroups(
            listOf(
                item("one", "/srv/alpha", projectId = "global", updated = 2),
                item("two", "/srv/beta", projectId = "global", updated = 1),
            ),
            projects,
            null,
            emptyMap(),
            "server",
        )

        assertEquals(listOf("/srv/alpha", "/srv/beta"), groups.map { it.directory })
        assertEquals(listOf("directory:/srv/alpha", "directory:/srv/beta"), groups.map { it.projectId })
    }

    @Test
    fun globalSessionsPreferMostSpecificNonGlobalProjectOverGlobalRoot() {
        val projects = listOf(
            Project(id = "global", worktree = "/", name = "Global"),
            Project(id = "repo", worktree = "/repo", name = "Root"),
            Project(id = "nested", worktree = "/repo/apps/mobile", name = "Mobile"),
        )

        val group = buildProjectSessionGroups(
            listOf(item("session", "/repo/apps/mobile/src", projectId = "global")),
            projects,
            null,
            emptyMap(),
            "server",
        ).single()

        assertEquals("nested", group.projectId)
        assertEquals("Mobile", group.projectName)
    }

    @Test
    fun nonGlobalProjectIdStillTakesPrecedenceOverDirectory() {
        val projects = listOf(
            Project(id = "project", worktree = "/repo", name = "Repository"),
            Project(id = "other", worktree = "/elsewhere", name = "Elsewhere"),
        )

        val group = buildProjectSessionGroups(
            listOf(item("session", "/elsewhere/nested", projectId = "project")),
            projects,
            null,
            emptyMap(),
            "server",
        ).single()

        assertEquals("project", group.projectId)
        assertEquals("Repository", group.projectName)
        assertEquals("/repo", group.directory)
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
    fun recentDirectoriesRespectConfiguredLimit() {
        val sessions = (1L..10L).map { updated ->
            item("session-$updated", "/repo-$updated", updated = updated)
        }

        val directories = recentSessionDirectories(sessions, limit = 5)

        assertEquals(listOf(10L, 9L, 8L, 7L, 6L), directories.map { it.lastUsed })
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
