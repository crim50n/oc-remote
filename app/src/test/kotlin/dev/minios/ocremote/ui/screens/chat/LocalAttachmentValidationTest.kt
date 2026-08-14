package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LocalAttachmentValidationTest {
    @Test
    fun acceptsSupportedDocumentTypes() {
        assertEquals(
            LocalAttachmentValidation.ACCEPTED,
            validateLocalAttachment("application/pdf", "spec.pdf", 5 * 1024 * 1024),
        )
        assertEquals(
            LocalAttachmentValidation.ACCEPTED,
            validateLocalAttachment("application/octet-stream", "Main.kt", 100_000),
        )
        assertEquals(
            LocalAttachmentValidation.ACCEPTED,
            validateLocalAttachment("application/json", "data.json", 100_000),
        )
    }

    @Test
    fun rejectsUnsupportedBinaryAndOversizedText() {
        assertEquals(
            LocalAttachmentValidation.UNSUPPORTED,
            validateLocalAttachment("application/zip", "archive.zip", 100_000),
        )
        assertEquals(
            LocalAttachmentValidation.TOO_LARGE,
            validateLocalAttachment("text/plain", "large.log", 2L * 1024 * 1024 + 1),
        )
    }

    @Test
    fun resolvesOnlyFilesInsideMessageImageCache() {
        val cacheDirectory = createTempDirectory("oc-remote-cache-").toFile()
        try {
            val imageDirectory = File(cacheDirectory, "message-images").apply { mkdirs() }
            val cachedImage = File(imageDirectory, "image.png").apply { writeBytes(byteArrayOf(1)) }
            val unrelatedFile = File(cacheDirectory, "private.txt").apply { writeText("private") }

            assertEquals(cachedImage.canonicalFile, resolveCachedMessageImage(cachedImage.toURI().toString(), cacheDirectory))
            assertNull(resolveCachedMessageImage(unrelatedFile.toURI().toString(), cacheDirectory))
            assertNull(
                resolveCachedMessageImage(
                    File(imageDirectory, "../private.txt").toURI().toString(),
                    cacheDirectory,
                ),
            )
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }
}
