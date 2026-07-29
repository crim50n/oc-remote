package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedPatchTest {

    @Test
    fun countsChangesWithoutTreatingFileHeadersAsChanges() {
        val patch = """
            --- a/file.kt
            +++ b/file.kt
            @@ -1,2 +1,3 @@
            -old
            +new
            +extra
        """.trimIndent()

        assertEquals(2 to 1, countUnifiedPatchChanges(patch))
    }
}
