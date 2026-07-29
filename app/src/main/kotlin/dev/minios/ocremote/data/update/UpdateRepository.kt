package dev.minios.ocremote.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.logging.AppLogger as Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val RICH_UPDATE_MANIFEST_URL = "https://github.com/crim50n/oc-remote/releases/latest/download/update.json"
private const val UPDATE_MANIFEST_URL = "https://raw.githubusercontent.com/crim50n/oc-remote/master/update.json"
private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/crim50n/oc-remote/releases/latest"
private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
private const val MAX_RESPONSE_BYTES = 64 * 1024L
private const val MAX_MANIFEST_CHARS = 8 * 1024
private const val MAX_GITHUB_CHARS = 64 * 1024
private const val UPDATE_TIMEOUT_MS = 15_000L
private const val APK_TIMEOUT_MS = 10 * 60 * 1000L
private const val MAX_APK_BYTES = 250L * 1024 * 1024
private const val TAG = "UpdateRepository"

@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: HttpClient,
    private val json: Json,
    private val dataStore: DataStore<Preferences>,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val operationMutex = Mutex()

    suspend fun restore() = operationMutex.withLock {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.ReadyToInstall) return@withLock
        val preferences = dataStore.data.first()
        decodeCachedRelease(preferences[CACHED_RELEASE_KEY])
            ?.takeIf { UpdatePolicy.isNewer(it, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME) }
            ?.let { _state.value = UpdateState.Available(it) }
    }

    suspend fun check(manual: Boolean) = operationMutex.withLock {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.ReadyToInstall) return@withLock
        val preferences = dataStore.data.first()
        val now = System.currentTimeMillis()
        if (!manual && now - (preferences[LAST_ATTEMPT_KEY] ?: 0L) < CHECK_INTERVAL_MS) return
        val cachedAvailable = _state.value as? UpdateState.Available
        if (manual || cachedAvailable == null) _state.value = UpdateState.Checking
        dataStore.edit { it[LAST_ATTEMPT_KEY] = now }

        val release = runCatching { fetchManifest(RICH_UPDATE_MANIFEST_URL) }.getOrNull()
            ?: runCatching { fetchManifest(UPDATE_MANIFEST_URL) }.getOrNull()
            ?: runCatching { fetchLatestGitHubRelease() }.getOrElse {
                _state.value = cachedAvailable ?: if (manual) {
                    UpdateState.Error("Unable to check for updates")
                } else {
                    UpdateState.Idle
                }
                return
            }

        if (UpdatePolicy.isNewer(release, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)) {
            dataStore.edit { it[CACHED_RELEASE_KEY] = json.encodeToString(AvailableUpdate.serializer(), release) }
            _state.value = UpdateState.Available(release)
        } else {
            dataStore.edit { it.remove(CACHED_RELEASE_KEY) }
            _state.value = UpdateState.UpToDate
        }
    }

    suspend fun prepareInstall(release: AvailableUpdate) = operationMutex.withLock {
        if (!UpdatePolicy.isInstallable(release)) {
            _state.value = UpdateState.Error("This update cannot be installed automatically", release)
            return@withLock
        }

        try {
            _state.value = UpdateState.Downloading(release, null)
            val apk = withContext(Dispatchers.IO) { downloadAndValidateApk(release) }
            _state.value = UpdateState.ReadyToInstall(release, apk.absolutePath)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Unable to prepare update installation", error)
            _state.value = UpdateState.Error("Unable to prepare update for installation", release)
        }
    }

    fun markInstallerLaunched() {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        _state.value = UpdateState.Available(ready.release)
    }

    private suspend fun fetchManifest(url: String): AvailableUpdate {
        val response = client.get(url) {
            timeout { requestTimeoutMillis = UPDATE_TIMEOUT_MS }
        }
        response.requireBoundedSuccess()
        val dto = json.decodeFromString<UpdateManifestDto>(response.readBounded(MAX_MANIFEST_CHARS))
        return requireNotNull(UpdatePolicy.manifestToRelease(dto)) { "Invalid update manifest" }
    }

    private suspend fun fetchLatestGitHubRelease(): AvailableUpdate {
        val response = client.get(GITHUB_LATEST_RELEASE_URL) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "OC-Remote/${BuildConfig.VERSION_NAME}")
            timeout { requestTimeoutMillis = UPDATE_TIMEOUT_MS }
        }
        response.requireBoundedSuccess()
        val dto = json.decodeFromString<GitHubReleaseDto>(response.readBounded(MAX_GITHUB_CHARS))
        return requireNotNull(UpdatePolicy.githubToRelease(dto)) { "Invalid GitHub release" }
    }

    private fun decodeCachedRelease(value: String?): AvailableUpdate? {
        if (value.isNullOrBlank() || value.length > 512) return null
        return runCatching { json.decodeFromString(AvailableUpdate.serializer(), value) }
            .getOrNull()
            ?.takeIf(UpdatePolicy::isInstallableOrDiscoveryOnly)
    }

    private suspend fun downloadAndValidateApk(release: AvailableUpdate): File {
        val updatesDir = File(context.cacheDir, "updates")
        require(updatesDir.exists() || updatesDir.mkdirs()) { "Unable to create update directory" }
        val tempFile = File.createTempFile("update-", ".apk.part", updatesDir)
        val targetFile = File(updatesDir, "oc-remote-${release.versionName}.apk")
        try {
            val response = client.get(requireNotNull(release.apkUrl)) {
                timeout {
                    requestTimeoutMillis = APK_TIMEOUT_MS
                    socketTimeoutMillis = APK_TIMEOUT_MS
                }
            }
            response.requireApkSuccess()
            val expectedSize = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            require(expectedSize == null || expectedSize in 1..MAX_APK_BYTES) { "Update APK is too large" }

            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var lastProgress: Int? = null
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            FileOutputStream(tempFile).use { output ->
                val channel = response.bodyAsChannel()
                while (true) {
                    val count = channel.readAvailable(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= MAX_APK_BYTES) { "Update APK is too large" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    val progress = expectedSize?.let { ((total * 100) / it).toInt().coerceIn(0, 100) }
                    if (progress != lastProgress) {
                        lastProgress = progress
                        _state.value = UpdateState.Downloading(release, progress)
                    }
                }
            }
            require(total > 0) { "Downloaded APK is empty" }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualSha256 == release.sha256) { "Downloaded APK checksum does not match" }
            validateApk(tempFile, release)
            if (targetFile.exists()) targetFile.delete()
            require(tempFile.renameTo(targetFile)) { "Unable to finalize downloaded APK" }
            return targetFile
        } catch (error: CancellationException) {
            tempFile.delete()
            throw error
        } catch (error: Exception) {
            tempFile.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(apk: File, release: AvailableUpdate) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(apk.path, flags)) {
            "Downloaded file is not a valid APK"
        }
        require(archive.packageName == release.packageName && archive.packageName == context.packageName) {
            "APK package does not match this app"
        }
        require(archive.versionName == release.versionName && archive.versionCodeCompat() == release.versionCode?.toLong()) {
            "APK version does not match update metadata"
        }
        require(archive.versionCodeCompat() > BuildConfig.VERSION_CODE.toLong()) { "APK is not newer than the installed app" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(signingCertificates(archive).intersect(signingCertificates(installed)).isNotEmpty()) {
            "APK signing certificate does not match the installed app"
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun signingCertificates(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.let { signingInfo ->
                if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
                else signingInfo.signingCertificateHistory
            }
        } else {
            packageInfo.signatures
        }.orEmpty()
        return signatures.map { it.toByteArray().joinToString("") { byte -> "%02x".format(byte) } }.toSet()
    }

    private fun HttpResponse.requireBoundedSuccess() {
        require(status.isSuccess()) { "Update server returned $status" }
        val contentLength = headers[HttpHeaders.ContentLength]?.toLongOrNull()
        require(contentLength == null || contentLength in 1..MAX_RESPONSE_BYTES) { "Update response is too large" }
    }

    private fun HttpResponse.requireApkSuccess() {
        require(status.isSuccess()) { "Update download returned $status" }
    }

    private suspend fun HttpResponse.readBounded(maxChars: Int): String {
        val channel = bodyAsChannel()
        val output = ByteArrayOutputStream(minOf(maxChars, 8 * 1024))
        val buffer = ByteArray(1024)
        var total = 0
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maxChars) { "Update response is too large" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        val LAST_ATTEMPT_KEY = longPreferencesKey("update_last_attempt")
        val CACHED_RELEASE_KEY = stringPreferencesKey("update_cached_release")
    }
}
