package dev.minios.ocremote.data.sync

import dev.minios.ocremote.data.repository.SyncBackend
import dev.minios.ocremote.data.repository.SyncConfig
import dev.minios.ocremote.data.repository.SyncTargetConfig
import dev.minios.ocremote.data.repository.migrateSingleBackendSyncConfig
import dev.minios.ocremote.data.repository.requireSingleSyncStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoreTest {
    @Test
    fun passwordCryptoRoundTrips() {
        val plaintext = "server password".toByteArray()
        val envelope = PasswordCrypto.encrypt(plaintext, "correct horse".toCharArray())

        assertEquals("server password", PasswordCrypto.decrypt(envelope, "correct horse".toCharArray()).decodeToString())
    }

    @Test
    fun passwordCryptoRejectsWrongPassphrase() {
        val envelope = PasswordCrypto.encrypt("secret".toByteArray(), "correct".toCharArray())

        assertThrows(IllegalArgumentException::class.java) {
            PasswordCrypto.decrypt(envelope, "wrong".toCharArray())
        }
    }

    @Test
    fun payloadDoesNotContainPlaintextPasswords() {
        val password = "super-secret-password"
        val encrypted = PasswordCrypto.encrypt(password.toByteArray(), "passphrase".toCharArray())
        val serialized = Json.encodeToString(SyncPayload(
            servers = listOf(SyncServer(id = "server-1", url = "https://example.test", username = "user")),
            encryptedSecrets = encrypted,
        ))

        assertFalse(serialized.contains(password))
        assertFalse(serialized.contains("password\":"))
    }

    @Test
    fun parsesGistIdFromIdAndUrl() {
        assertEquals("a1b2c3d4e5", GistSyncTransport.parseGistId("a1b2c3d4e5"))
        assertEquals("a1b2c3d4e5", GistSyncTransport.parseGistId("https://gist.github.com/user/a1b2c3d4e5/"))
        assertEquals("a1b2c3d4e5", GistSyncTransport.parseGistId("https://api.github.com/gists/a1b2c3d4e5"))
    }

    @Test
    fun syncDecisionDetectsConflictAndSingleSidedChanges() {
        assertEquals(SyncDecision.CONFLICT, decideSync(true, "one", "two", "local-one", "local-two"))
        assertEquals(SyncDecision.PULL_REMOTE, decideSync(true, "one", "two", "local", "local"))
        assertEquals(SyncDecision.PUSH_LOCAL, decideSync(true, "one", "one", "local-one", "local-two"))
        assertEquals(SyncDecision.CONFLICT, decideSync(true, null, "remote", null, "local"))
        assertEquals(SyncDecision.MISSING_UPLOAD, decideSync(false, null, null, null, "local"))
    }

    @Test
    fun backupDecisionOnlyOverwritesKnownReplica() {
        assertEquals(
            BackupSyncDecision.CREATE,
            decideBackupSync(false, null, null, "canonical"),
        )
        assertEquals(
            BackupSyncDecision.UP_TO_DATE,
            decideBackupSync(true, "old", "canonical", "canonical"),
        )
        assertEquals(
            BackupSyncDecision.UPDATE,
            decideBackupSync(true, "old", "old", "canonical"),
        )
        assertEquals(
            BackupSyncDecision.DIVERGED,
            decideBackupSync(true, "old", "independent", "canonical"),
        )
        assertEquals(
            BackupSyncDecision.DIVERGED,
            decideBackupSync(true, null, "unknown", "canonical"),
        )
    }

    @Test
    fun payloadPreservesSessionCategoryAssignments() {
        val payload = SyncPayload(
            generation = 3,
            parentGeneration = 2,
            writerDeviceId = "device-1",
            sessionCategoryAssignments = mapOf(
                "server-1" to mapOf("session-1" to "category-1"),
            ),
        )

        val restored = Json.decodeFromString<SyncPayload>(Json.encodeToString(payload))

        assertEquals("category-1", restored.sessionCategoryAssignments["server-1"]?.get("session-1"))
        assertEquals(1, restored.version)
        assertEquals(3, restored.generation)
        assertEquals(2L, restored.parentGeneration)
        assertEquals("device-1", restored.writerDeviceId)
        assertTrue(restored.sessionCategories.isEmpty())
    }

    @Test
    fun payloadRequiresExplicitFormatVersionOne() {
        assertEquals(1, Json.decodeSyncPayload("{\"version\":1}").version)
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeSyncPayload("{}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeSyncPayload("{\"version\":2}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeSyncPayload("{\"version\":\"1\"}")
        }
    }

    @Test
    fun legacySingleBackendBecomesPrimaryWithoutEnablingOtherStorage() {
        val gist = migrateSingleBackendSyncConfig(
            backend = SyncBackend.GIST,
            endpoint = "gist-id",
            username = "ignored",
            autoSync = true,
            includeEncryptedPasswords = true,
        )
        assertEquals(SyncBackend.GIST, gist.primaryBackend)
        assertTrue(gist.gist.enabled)
        assertFalse(gist.webDav.enabled)
        assertEquals("gist-id", gist.gist.endpoint)
        assertTrue(gist.autoSync)

        val webDav = migrateSingleBackendSyncConfig(
            backend = SyncBackend.WEBDAV,
            endpoint = "https://dav.example/OCRemote.json",
            username = "user",
            autoSync = false,
            includeEncryptedPasswords = false,
        )
        assertEquals(SyncBackend.WEBDAV, webDav.primaryBackend)
        assertTrue(webDav.webDav.enabled)
        assertFalse(webDav.gist.enabled)
        assertEquals("user", webDav.webDav.username)
    }

    @Test
    fun syncConfigurationRejectsTwoEnabledStorages() {
        val both = SyncConfig(
            primaryBackend = SyncBackend.GIST,
            gist = SyncTargetConfig(enabled = true),
            webDav = SyncTargetConfig(enabled = true, endpoint = "https://dav.example/OCRemote.json"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            requireSingleSyncStorage(both)
        }
    }
}
