package dev.minios.ocremote.data.api

import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in check against a local V1 server; normal test runs do not make network requests. */
class V1BinaryDownloadLiveTest {
    @Test fun downloadedApkMatchesLocalArtifact() {
        val path = System.getenv("OCREMOTE_LIVE_FILE_PATH")
        assumeTrue(path != null)
        val file = File(requireNotNull(path))
        fun query(value: String) = URLEncoder.encode(value, "UTF-8")
        val url = URI("http://127.0.0.1:4096/file/content?directory=${query(requireNotNull(file.parent))}&path=${query(file.name)}")
        val connection = url.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        System.getenv("OPENCODE_SERVER_PASSWORD")?.let { password ->
            val user = System.getenv("OPENCODE_SERVER_USERNAME") ?: "opencode"
            connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray()))
        }
        try {
            assertEquals(200, connection.responseCode)
            val actual = MessageDigest.getInstance("SHA-256")
            val mime = connection.inputStream.use { input ->
                downloadV1Binary(input, object : OutputStream() {
                    override fun write(value: Int) { actual.update(value.toByte()) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { actual.update(bytes, offset, length) }
                })
            }
            val expected = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    expected.update(buffer, 0, count)
                }
            }
            assertArrayEquals(expected.digest(), actual.digest())
            assertEquals("application/vnd.android.package-archive", mime)
        } finally {
            connection.disconnect()
        }
    }
}
