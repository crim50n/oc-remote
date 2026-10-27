package dev.minios.ocremote.data.update

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseNotesTest {
    private val release = AvailableUpdate("1.13.0", 30,
        "https://github.com/crim50n/oc-remote/releases/tag/v1.13.0", "dev.minios.ocremote",
        "https://github.com/crim50n/oc-remote/releases/download/v1.13.0/oc-remote-1.13.0.apk", "a".repeat(64))

    @Test fun notesKeepVerifiedInstallMetadataAndSurviveSerialization() {
        val enriched = UpdatePolicy.withReleaseNotes(release, GitHubReleaseDto("v1.13.0", release.releaseUrl,
            body = "## Highlights\n\n- New tabs", publishedAt = "2026-10-26T23:50:00Z"))
        assertEquals(release.apkUrl, enriched.apkUrl)
        assertEquals(release.sha256, enriched.sha256)
        assertEquals(enriched, Json.decodeFromString<AvailableUpdate>(Json.encodeToString(enriched)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun doesNotAttachNotesFromAnotherRelease() {
        UpdatePolicy.withReleaseNotes(release, GitHubReleaseDto("v1.14.0", release.releaseUrl, body = "Wrong version"))
    }

    @Test fun missingNotesAreCachedAsEmptyAndLargeNotesAreBounded() {
        assertEquals("", UpdatePolicy.withReleaseNotes(release, GitHubReleaseDto("v1.13.0", release.releaseUrl)).releaseNotes)
        assertEquals(32 * 1024, UpdatePolicy.withReleaseNotes(release,
            GitHubReleaseDto("v1.13.0", release.releaseUrl, body = "x".repeat(100_000))).releaseNotes?.length)
    }

    @Test fun oldCachedReleaseRemainsReadable() {
        val cached = Json.decodeFromString<AvailableUpdate>(
            """{"versionName":"1.13.0","releaseUrl":"${release.releaseUrl}"}""",
        )
        assertNull(cached.releaseNotes)
        assertEquals(release.versionName, cached.versionName)
    }
}
