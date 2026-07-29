package dev.minios.ocremote.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val REPOSITORY_HOST = "github.com"
private const val REPOSITORY_OWNER = "crim50n"
private const val REPOSITORY_NAME = "oc-remote"
private const val APPLICATION_ID = "dev.minios.ocremote"

@Serializable
data class UpdateManifestDto(
    val versionName: String,
    val versionCode: Int,
    val releaseUrl: String,
    val packageName: String? = null,
    val apkUrl: String? = null,
    val sha256: String? = null,
)

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

@Serializable
data class AvailableUpdate(
    val versionName: String,
    val versionCode: Int? = null,
    val releaseUrl: String,
    val packageName: String? = null,
    val apkUrl: String? = null,
    val sha256: String? = null,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    open class Available(open val release: AvailableUpdate) : UpdateState
    class Downloading(release: AvailableUpdate, val progressPercent: Int?) : Available(release)
    class ReadyToInstall(release: AvailableUpdate, val apkPath: String) : Available(release)
    data class Error(val message: String, val release: AvailableUpdate? = null) : UpdateState
}

object UpdatePolicy {
    const val RELEASE_URL_PREFIX = "https://$REPOSITORY_HOST/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases/tag/"

    fun manifestToRelease(dto: UpdateManifestDto): AvailableUpdate? {
        val versionName = dto.versionName.takeIf(::isValidVersionName) ?: return null
        if (dto.versionCode <= 0) return null
        val release = AvailableUpdate(
            versionName = versionName,
            versionCode = dto.versionCode,
            releaseUrl = dto.releaseUrl,
            packageName = dto.packageName,
            apkUrl = dto.apkUrl,
            sha256 = dto.sha256?.lowercase(),
        )
        return release.takeIf(::hasValidReleaseUrl)?.takeIf(::hasValidRichMetadata)
    }

    fun githubToRelease(dto: GitHubReleaseDto): AvailableUpdate? {
        if (dto.draft || dto.prerelease) return null
        val versionName = dto.tagName.removePrefix("v").takeIf(::isValidVersionName) ?: return null
        return AvailableUpdate(versionName, releaseUrl = dto.htmlUrl).takeIf {
            hasValidReleaseUrl(it) && dto.tagName == "v$versionName"
        }
    }

    fun hasValidReleaseUrl(release: AvailableUpdate): Boolean {
        return release.releaseUrl == "$RELEASE_URL_PREFIX${releaseTag(release.versionName)}"
    }

    fun isInstallable(release: AvailableUpdate): Boolean {
        return hasValidReleaseUrl(release) && hasValidRichMetadata(release) && release.apkUrl != null
    }

    fun isInstallableOrDiscoveryOnly(release: AvailableUpdate): Boolean {
        return hasValidReleaseUrl(release) && hasValidRichMetadata(release)
    }

    fun isNewer(release: AvailableUpdate, installedVersionCode: Int, installedVersionName: String): Boolean {
        val versionCode = release.versionCode
        return if (versionCode != null) {
            versionCode > installedVersionCode
        } else {
            compareSemVer(release.versionName, installedVersionName) > 0
        }
    }

    fun compareSemVer(left: String, right: String): Int {
        val leftParts = parseSemVer(left) ?: return 0
        val rightParts = parseSemVer(right) ?: return 0
        return leftParts.zip(rightParts).firstOrNull { (a, b) -> a != b }
            ?.let { (a, b) -> a.compareTo(b) }
            ?: 0
    }

    private fun releaseTag(versionName: String) = "v$versionName"

    private fun hasValidRichMetadata(release: AvailableUpdate): Boolean {
        val supplied = listOf(release.packageName, release.apkUrl, release.sha256)
        if (supplied.all { it == null }) return true
        if (supplied.any { it == null }) return false
        return release.packageName == APPLICATION_ID &&
            release.apkUrl == expectedApkUrl(release.versionName) &&
            release.sha256?.matches(Regex("^[0-9a-fA-F]{64}$")) == true
    }

    private fun expectedApkUrl(versionName: String): String {
        return "https://$REPOSITORY_HOST/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases/download/" +
            "${releaseTag(versionName)}/oc-remote-$versionName.apk"
    }

    private fun isValidVersionName(version: String): Boolean {
        return version.length <= 32 && parseSemVer(version) != null
    }

    private fun parseSemVer(version: String): List<Int>? {
        if (!Regex("^\\d+\\.\\d+\\.\\d+([-.][0-9A-Za-z.-]+)?$").matches(version)) return null
        return version.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
    }
}
