package dev.minios.ocremote.data.sync

import dev.minios.ocremote.domain.model.SessionCategory
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncMergeTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    @Test
    fun canonicalDataIgnoresMetadataServerIdsAndLegacyDefaults() {
        val first = canonicalizeSyncData(
            payload = SyncPayload(
                generation = 10,
                updatedAt = 100,
                writerDeviceId = "one",
                settings = SyncSettings(hideToolDetails = false),
                servers = listOf(SyncServer("local-id", "https://example.test/")),
                favoriteSessionIds = mapOf("local-id" to listOf("session-1")),
                hiddenModels = mapOf("local-id" to setOf("provider:model")),
            ),
            passwords = mapOf("local-id" to "secret"),
        )
        val second = canonicalizeSyncData(
            payload = SyncPayload(
                generation = 11,
                parentGeneration = 10,
                updatedAt = 200,
                writerDeviceId = "two",
                settings = SyncSettings(hideToolDetails = null),
                servers = listOf(SyncServer("remote-id", "https://example.test")),
                favoriteSessionIds = mapOf("remote-id" to listOf("session-1")),
                hiddenModels = mapOf("remote-id" to linkedSetOf("provider:model")),
            ),
            passwords = mapOf("remote-id" to "secret"),
            fallback = first,
        )

        assertEquals(first, second)
    }

    @Test
    fun canonicalDataRejectsAmbiguousServerIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            canonicalizeSyncData(
                SyncPayload(
                    servers = listOf(
                        SyncServer("one", "https://example.test"),
                        SyncServer("two", "https://example.test/"),
                    ),
                ),
                emptyMap(),
            )
        }
    }

    @Test
    fun canonicalDataTreatsMissingAndEmptyCollectionsEqually() {
        val server = SyncServer("server", "https://example.test")
        val missing = canonicalizeSyncData(SyncPayload(servers = listOf(server)), emptyMap())
        val empty = canonicalizeSyncData(
            SyncPayload(
                servers = listOf(server),
                sessionCategoryAssignments = mapOf("server" to emptyMap()),
                favoriteSessionIds = mapOf("server" to emptyList()),
                hiddenModels = mapOf("server" to emptySet()),
            ),
            emptyMap(),
        )

        assertEquals(missing, empty)
    }

    @Test
    fun disjointSettingChangesMergeAutomatically() {
        val base = data(settings = SyncSettings())
        val local = data(settings = SyncSettings(appTheme = "dark"))
        val remote = data(settings = SyncSettings(chatFontSize = "large"))

        val result = mergeSyncData(base, local, remote, json)

        assertFalse(result.hasConflicts)
        assertEquals("dark", result.data.payload.settings.appTheme)
        assertEquals("large", result.data.payload.settings.chatFontSize)
    }

    @Test
    fun sameSettingChangedDifferentlyIsAConflict() {
        val base = data(settings = SyncSettings(appTheme = "system"))
        val local = data(settings = SyncSettings(appTheme = "dark"))
        val remote = data(settings = SyncSettings(appTheme = "light"))

        val result = mergeSyncData(base, local, remote, json)

        assertTrue(result.hasConflicts)
        assertEquals(1, result.conflictCounts[SyncConflictArea.SETTINGS])
        assertEquals("dark", result.data.payload.settings.appTheme)
    }

    @Test
    fun conflictChoiceKeepsDisjointChangesFromBothSides() {
        val base = data(settings = SyncSettings(appTheme = "system", chatFontSize = "medium"))
        val local = data(settings = SyncSettings(appTheme = "dark", chatFontSize = "medium"))
        val remote = data(settings = SyncSettings(appTheme = "light", chatFontSize = "large"))

        val keepLocal = mergeSyncData(
            base,
            local,
            remote,
            json,
            SyncConflictPreference.LOCAL,
        )
        val keepRemote = mergeSyncData(
            base,
            local,
            remote,
            json,
            SyncConflictPreference.REMOTE,
        )

        assertFalse(keepLocal.hasConflicts)
        assertEquals("dark", keepLocal.data.payload.settings.appTheme)
        assertEquals("large", keepLocal.data.payload.settings.chatFontSize)
        assertFalse(keepRemote.hasConflicts)
        assertEquals("light", keepRemote.data.payload.settings.appTheme)
        assertEquals("large", keepRemote.data.payload.settings.chatFontSize)
    }

    @Test
    fun independentServerAndHiddenModelChangesMerge() {
        val base = data(
            servers = listOf(SyncServer("base", "https://base.test")),
            hiddenModels = mapOf("base" to emptySet()),
        )
        val local = data(
            servers = listOf(
                SyncServer("base-local", "https://base.test"),
                SyncServer("local", "https://local.test"),
            ),
            hiddenModels = mapOf("base-local" to setOf("provider:local")),
        )
        val remote = data(
            servers = listOf(
                SyncServer("base-remote", "https://base.test"),
                SyncServer("remote", "https://remote.test"),
            ),
            hiddenModels = mapOf("base-remote" to setOf("provider:remote")),
        )

        val result = mergeSyncData(base, local, remote, json)

        assertFalse(result.hasConflicts)
        assertEquals(3, result.data.payload.servers.size)
        val baseId = result.data.payload.servers.single { it.url == "https://base.test" }.id
        assertEquals(setOf("provider:local", "provider:remote"), result.data.payload.hiddenModels?.get(baseId))
    }

    @Test
    fun independentFavoriteAdditionsMergeAutomatically() {
        val server = SyncServer("server", "https://base.test")
        val base = data(servers = listOf(server), favorites = mapOf("server" to emptyList()))
        val local = data(servers = listOf(server), favorites = mapOf("server" to listOf("local")))
        val remote = data(servers = listOf(server), favorites = mapOf("server" to listOf("remote")))

        val result = mergeSyncData(base, local, remote, json)

        assertFalse(result.hasConflicts)
        assertEquals(setOf("local", "remote"), result.data.payload.favoriteSessionIds?.values?.single()?.toSet())
    }

    @Test
    fun serverDeletionMergesAgainstUnchangedCopyButConflictsWithEdit() {
        val original = SyncServer("base", "https://base.test", name = "Base")
        val base = data(servers = listOf(original))
        val deleted = data(servers = emptyList())
        val unchanged = data(servers = listOf(original.copy(id = "remote")))
        val edited = data(servers = listOf(original.copy(id = "remote", name = "Changed")))

        val deletion = mergeSyncData(base, deleted, unchanged, json)
        val conflict = mergeSyncData(base, deleted, edited, json)

        assertFalse(deletion.hasConflicts)
        assertTrue(deletion.data.payload.servers.isEmpty())
        assertEquals(1, conflict.conflictCounts[SyncConflictArea.SERVERS])
        assertNotEquals("Changed", conflict.data.payload.servers.firstOrNull()?.name)
    }

    @Test
    fun independentFieldsOfSameServerAndCategoryMergeAutomatically() {
        val server = SyncServer("server", "https://base.test", name = "Base", autoConnect = false)
        val category = SessionCategory("category", "Base", "red", "folder")
        val base = data(servers = listOf(server), categories = listOf(category))
        val local = data(
            servers = listOf(server.copy(name = "Local")),
            categories = listOf(category.copy(name = "Local")),
        )
        val remote = data(
            servers = listOf(server.copy(autoConnect = true)),
            categories = listOf(category.copy(color = "blue")),
        )

        val result = mergeSyncData(base, local, remote, json)

        assertFalse(result.hasConflicts)
        assertEquals("Local", result.data.payload.servers.single().name)
        assertTrue(result.data.payload.servers.single().autoConnect)
        assertEquals("Local", result.data.payload.sessionCategories.single().name)
        assertEquals("blue", result.data.payload.sessionCategories.single().color)
    }

    @Test
    fun remoteConflictChoiceUsesRemoteOrder() {
        val servers = listOf(
            SyncServer("a", "https://a.test"),
            SyncServer("b", "https://b.test"),
            SyncServer("c", "https://c.test"),
        )
        val base = data(servers = servers)
        val local = data(servers = listOf(servers[1], servers[0], servers[2]))
        val remote = data(servers = listOf(servers[0], servers[2], servers[1]))

        val result = mergeSyncData(base, local, remote, json, SyncConflictPreference.REMOTE)

        assertFalse(result.hasConflicts)
        assertEquals(
            listOf("https://a.test", "https://c.test", "https://b.test"),
            result.data.payload.servers.map(SyncServer::url),
        )
    }

    @Test
    fun passwordConflictHasItsOwnArea() {
        val server = SyncServer("server", "https://base.test")
        val base = data(servers = listOf(server), passwords = mapOf("server" to "base"))
        val local = data(servers = listOf(server), passwords = mapOf("server" to "local"))
        val remote = data(servers = listOf(server), passwords = mapOf("server" to "remote"))

        val result = mergeSyncData(base, local, remote, json)

        assertEquals(1, result.conflictCounts[SyncConflictArea.PASSWORDS])
        assertFalse(result.conflictCounts.containsKey(SyncConflictArea.SERVERS))
    }

    @Test
    fun omittedPasswordsUseFallbackButExplicitEmptyPasswordsDeleteThem() {
        val server = SyncServer("server", "https://base.test")
        val base = data(servers = listOf(server), passwords = mapOf("server" to "secret"))
        val omitted = canonicalizeSyncData(
            SyncPayload(servers = listOf(server), passwordsIncluded = false),
            emptyMap(),
            base,
        )
        val explicitEmpty = canonicalizeSyncData(
            SyncPayload(servers = listOf(server), passwordsIncluded = true),
            emptyMap(),
            base,
        )

        assertEquals(mapOf(base.payload.servers.single().id to "secret"), omitted.passwords)
        assertTrue(explicitEmpty.passwords.isEmpty())
    }

    @Test
    fun duplicateCategoryIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            canonicalizeSyncData(
                SyncPayload(
                    sessionCategories = listOf(
                        SessionCategory("duplicate", "One", "red", "folder"),
                        SessionCategory("duplicate", "Two", "blue", "star"),
                    ),
                ),
                emptyMap(),
            )
        }
    }

    @Test
    fun serverDeletionConflictsWithDependentEditAndFollowsPreference() {
        val server = SyncServer("server", "https://base.test")
        val base = data(servers = listOf(server))
        val deleted = data(servers = emptyList())
        val edited = data(servers = listOf(server), hiddenModels = mapOf("server" to setOf("provider:model")))

        val conflict = mergeSyncData(base, deleted, edited, json)
        val keepRemote = mergeSyncData(base, deleted, edited, json, SyncConflictPreference.REMOTE)

        assertTrue(conflict.hasConflicts)
        assertTrue(conflict.data.payload.servers.isEmpty())
        assertEquals(listOf("https://base.test"), keepRemote.data.payload.servers.map(SyncServer::url))
        assertEquals(setOf("provider:model"), keepRemote.data.payload.hiddenModels?.values?.single())
    }

    @Test
    fun categoryDeletionConflictsWithNewAssignmentAndFollowsPreference() {
        val server = SyncServer("server", "https://base.test")
        val category = SessionCategory("category", "Base", "red", "folder")
        val base = data(servers = listOf(server), categories = listOf(category))
        val deleted = data(servers = listOf(server), categories = emptyList())
        val assigned = data(
            servers = listOf(server),
            categories = listOf(category),
            assignments = mapOf("server" to mapOf("session" to "category")),
        )

        val conflict = mergeSyncData(base, deleted, assigned, json)
        val keepRemote = mergeSyncData(base, deleted, assigned, json, SyncConflictPreference.REMOTE)

        assertTrue(conflict.hasConflicts)
        assertTrue(conflict.data.payload.sessionCategories.isEmpty())
        assertEquals(listOf("category"), keepRemote.data.payload.sessionCategories.map(SessionCategory::id))
        assertEquals("category", keepRemote.data.payload.sessionCategoryAssignments.values.single()["session"])
    }

    private fun data(
        settings: SyncSettings = SyncSettings(),
        servers: List<SyncServer> = emptyList(),
        hiddenModels: Map<String, Set<String>> = emptyMap(),
        favorites: Map<String, List<String>> = emptyMap(),
        categories: List<SessionCategory> = emptyList(),
        assignments: Map<String, Map<String, String>> = emptyMap(),
        passwords: Map<String, String> = emptyMap(),
    ): SemanticSyncData = canonicalizeSyncData(
        SyncPayload(
            settings = settings,
            sessionCategories = categories,
            sessionCategoryAssignments = assignments,
            servers = servers,
            favoriteSessionIds = favorites,
            crossServerFavoriteOrder = emptyList(),
            favoriteSessionSnapshots = emptyMap(),
            hiddenModels = hiddenModels,
        ),
        passwords,
    )
}
