package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.sync.SyncServer
import dev.minios.ocremote.domain.model.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRepositoryMergeTest {
    @Test
    fun `local upsert atomically merges duplicate entries`() {
        val current = listOf(
            ServerConfig(
                id = "local-1",
                url = "http://127.0.0.1:4096",
                username = "old",
                password = "old-password",
                name = "My local server",
                autoConnect = false,
                lastConnected = 10,
                isHealthy = false,
            ),
            ServerConfig(id = "remote", url = "https://example.com", name = "Remote"),
            ServerConfig(
                id = "local-2",
                url = "http://127.0.0.1:4096/",
                autoConnect = true,
                lastConnected = 20,
                isHealthy = true,
            ),
        )

        val (servers, result) = upsertLocalServerConfig(
            current = current,
            localUrl = "http://127.0.0.1:4096/",
            username = "opencode",
            password = "new-password",
            defaultName = "Local OpenCode",
        )

        assertEquals(listOf("local-1", "remote"), servers.map(ServerConfig::id))
        assertEquals("local-1", result.server.id)
        assertEquals(listOf("local-2"), result.removedServerIds)
        assertEquals("My local server", result.server.name)
        assertEquals("opencode", result.server.username)
        assertEquals("new-password", result.server.password)
        assertTrue(result.server.autoConnect)
        assertEquals(20L, result.server.lastConnected)
        assertTrue(result.server.isHealthy)
    }

    @Test
    fun `local upsert creates one normalized entry when missing`() {
        val (servers, result) = upsertLocalServerConfig(
            current = emptyList(),
            localUrl = " http://127.0.0.1:4096/ ",
            username = "opencode",
            password = null,
            defaultName = "Local OpenCode",
            idGenerator = { "generated" },
        )

        assertEquals(1, servers.size)
        assertEquals("generated", result.server.id)
        assertEquals("http://127.0.0.1:4096", result.server.url)
        assertNull(result.server.password)
        assertTrue(result.removedServerIds.isEmpty())
    }

    @Test
    fun `sync merge keeps runtime state and remaps colliding IDs`() {
        val current = listOf(
            ServerConfig(
                id = "local-id",
                url = "https://existing.example/",
                password = "local-secret",
                lastConnected = 42,
                isHealthy = true,
            ),
            ServerConfig(id = "occupied", url = "https://other.example"),
        )
        val result = mergeSyncServers(
            current = current,
            remote = listOf(
                SyncServer("remote-existing", "https://existing.example", username = "remote-user"),
                SyncServer("occupied", "https://new.example", username = "new-user"),
            ),
            passwords = emptyMap(),
            idGenerator = { "generated" },
        )

        val existing = result.servers.single { it.id == "local-id" }
        assertEquals("local-secret", existing.password)
        assertEquals(42L, existing.lastConnected)
        assertTrue(existing.isHealthy)
        assertEquals("remote-user", existing.username)
        assertEquals("local-id", result.idMapping["remote-existing"])
        assertEquals("generated", result.idMapping["occupied"])
        assertFalse(result.servers.any { it.id == "occupied" && it.url == "https://new.example" })
    }

    @Test
    fun `sync merge does not silently delete existing duplicate endpoints`() {
        val current = listOf(
            ServerConfig(id = "first", url = "https://same.example", name = "First"),
            ServerConfig(id = "second", url = "https://same.example/", name = "Second"),
        )

        val result = mergeSyncServers(
            current = current,
            remote = listOf(SyncServer("remote", "https://unrelated.example")),
            passwords = emptyMap(),
        )

        assertEquals(listOf("first", "second", "remote"), result.servers.map(ServerConfig::id))
    }

    @Test
    fun `sync snapshot excludes local runtime server`() {
        val servers = portableSyncServers(
            listOf(
                ServerConfig(
                    id = "local",
                    url = " http://127.0.0.1:4096/ ",
                    username = "device-user",
                    password = "device-secret",
                ),
                ServerConfig(id = "remote", url = "https://example.com/", username = "remote-user"),
            ),
        )

        assertEquals(listOf("remote"), servers.map(SyncServer::id))
        assertEquals("https://example.com", servers.single().url)
    }

    @Test
    fun `sync import ignores local runtime server from older payload`() {
        val currentLocal = ServerConfig(
            id = "local-device",
            url = LocalServerManager.LOCAL_SERVER_URL,
            username = "device-user",
            password = "device-secret",
            autoConnect = false,
        )

        val result = mergeSyncServers(
            current = listOf(currentLocal),
            remote = listOf(
                SyncServer(
                    id = "remote-local",
                    url = "http://127.0.0.1:4096/",
                    username = "other-device-user",
                    autoConnect = true,
                ),
            ),
            passwords = mapOf("remote-local" to "other-device-secret"),
        )

        assertEquals(listOf(currentLocal), result.servers)
        assertTrue(result.idMapping.isEmpty())
    }

    @Test
    fun `server reorder preserves local and unlisted server slots`() {
        val first = ServerConfig(id = "first", url = "https://first.example", password = "one")
        val local = ServerConfig(id = "local", url = LocalServerManager.LOCAL_SERVER_URL)
        val second = ServerConfig(id = "second", url = "https://second.example", isHealthy = true)
        val addedLater = ServerConfig(id = "added", url = "https://added.example")

        val reordered = reorderPortableServers(
            current = listOf(first, local, second, addedLater),
            orderedServerIds = listOf("second", "unknown", "first", "second"),
        )

        assertEquals(listOf("second", "local", "first", "added"), reordered.map(ServerConfig::id))
        assertEquals(second, reordered[0])
        assertEquals(local, reordered[1])
        assertEquals(first, reordered[2])
        assertEquals(addedLater, reordered[3])
    }

    @Test
    fun `sync merge applies remote order without moving local-only slots`() {
        val current = listOf(
            ServerConfig(id = "local-only", url = "https://local-only.example"),
            ServerConfig(id = "first", url = "https://first.example"),
            ServerConfig(id = "runtime", url = LocalServerManager.LOCAL_SERVER_URL),
            ServerConfig(id = "second", url = "https://second.example"),
        )

        val result = mergeSyncServers(
            current = current,
            remote = listOf(
                SyncServer(id = "remote-second", url = "https://second.example"),
                SyncServer(id = "remote-first", url = "https://first.example"),
                SyncServer(id = "remote-new", url = "https://new.example"),
            ),
            passwords = emptyMap(),
        )

        assertEquals(
            listOf("local-only", "second", "runtime", "first", "remote-new"),
            result.servers.map(ServerConfig::id),
        )
        assertEquals("second", result.idMapping["remote-second"])
        assertEquals("first", result.idMapping["remote-first"])
    }

    @Test
    fun `drag order does not overwrite concurrent sync reorder`() {
        val current = listOf(
            ServerConfig(id = "second", url = "https://second.example"),
            ServerConfig(id = "first", url = "https://first.example"),
            ServerConfig(id = "new", url = "https://new.example"),
        )

        val result = reorderPortableServersIfBaseUnchanged(
            current = current,
            expectedServerIds = listOf("first", "second"),
            orderedServerIds = listOf("second", "first"),
        )

        assertEquals(current, result)
    }

    @Test
    fun `drag order preserves server added after drag started`() {
        val current = listOf(
            ServerConfig(id = "first", url = "https://first.example"),
            ServerConfig(id = "second", url = "https://second.example"),
            ServerConfig(id = "new", url = "https://new.example"),
        )

        val result = reorderPortableServersIfBaseUnchanged(
            current = current,
            expectedServerIds = listOf("first", "second"),
            orderedServerIds = listOf("second", "first"),
        )

        assertEquals(listOf("second", "first", "new"), result.map(ServerConfig::id))
    }

    @Test
    fun `duplicate persisted IDs leave order unchanged`() {
        val current = listOf(
            ServerConfig(id = "duplicate", url = "https://first.example"),
            ServerConfig(id = "duplicate", url = "https://second.example"),
            ServerConfig(id = "other", url = "https://other.example"),
        )

        assertEquals(
            current,
            reorderPortableServers(current, listOf("other", "duplicate")),
        )
    }
}
