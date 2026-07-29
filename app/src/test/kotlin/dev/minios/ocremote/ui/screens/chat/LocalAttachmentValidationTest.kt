package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
