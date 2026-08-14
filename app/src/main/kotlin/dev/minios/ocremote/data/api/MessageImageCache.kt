package dev.minios.ocremote.data.api

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageImageCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val directory = File(context.cacheDir, "message-images")

    @Synchronized
    fun cacheDataUrl(value: String): String? {
        if (!value.startsWith("data:image/", ignoreCase = true)) return null
        val separator = value.indexOf(',')
        if (separator <= 0 || !value.substring(0, separator).contains(";base64", ignoreCase = true)) return null

        directory.mkdirs()
        pruneCache()
        val mime = value.substringAfter("data:").substringBefore(';').lowercase()
        val extension = when (mime) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "img"
        }
        val temporary = File.createTempFile("image-", ".tmp", directory)
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestOutputStream(FileOutputStream(temporary), digest).use { output ->
                decodeBase64InChunks(value, separator + 1, output)
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val cached = File(directory, "$hash.$extension")
            if (cached.exists()) {
                temporary.delete()
            } else if (!temporary.renameTo(cached)) {
                temporary.copyTo(cached, overwrite = true)
                temporary.delete()
            }
            cached.setLastModified(System.currentTimeMillis())
            cached.toURI().toString()
        } catch (_: Exception) {
            temporary.delete()
            null
        }
    }

    private fun decodeBase64InChunks(value: String, start: Int, output: DigestOutputStream) {
        val decoder = Base64.getMimeDecoder()
        var offset = start
        while (offset < value.length) {
            var end = (offset + BASE64_CHUNK_CHARS).coerceAtMost(value.length)
            if (end < value.length) end -= (end - offset) % 4
            val encoded = value.substring(offset, end).toByteArray(Charsets.US_ASCII)
            output.write(decoder.decode(encoded))
            offset = end
        }
    }

    private fun pruneCache() {
        val files = directory.listFiles()?.sortedBy(File::lastModified).orEmpty()
        val staleBefore = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        files.filter { it.lastModified() < staleBefore }.forEach(File::delete)
        var totalBytes = directory.listFiles()?.sumOf(File::length) ?: 0L
        if (totalBytes <= CACHE_MAX_BYTES) return
        directory.listFiles()?.sortedBy(File::lastModified)?.forEach { file ->
            val size = file.length()
            if (totalBytes > CACHE_MAX_BYTES && file.delete()) totalBytes -= size
        }
    }

    private companion object {
        const val BASE64_CHUNK_CHARS = 8 * 1024
        const val CACHE_MAX_BYTES = 256L * 1024L * 1024L
        const val CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
