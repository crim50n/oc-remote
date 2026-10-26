package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.ui.components.inlineServerFileLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineFileLinkTest {
    @Test fun apkPathFromChatIsClickable() {
        val path = "app/build/outputs/apk/debug/app-debug.apk"
        assertEquals(path, inlineServerFileLink(path))
    }
    @Test fun recognizesAbsoluteWindowsAndLineReferences() {
        listOf("/tmp/report.pdf", "C:\\project\\file.kt:12", "src/main.kt#L4-L8", "README", ".gitignore").forEach {
            assertEquals(it, inlineServerFileLink(it))
        }
    }
    @Test fun ordinaryCodeCommandsAndUrlsAreNotLocalFiles() {
        listOf("true", "hello", "./gradlew assembleDebug", "foo()", "x=1", "src/*.kt",
            "https://example.test/file", "content://media/1", "//host/share/file").forEach {
            assertNull(it, inlineServerFileLink(it))
        }
    }
}
