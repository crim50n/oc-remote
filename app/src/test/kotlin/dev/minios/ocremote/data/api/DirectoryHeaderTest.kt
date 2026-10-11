package dev.minios.ocremote.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryHeaderTest {
    @Test
    fun asciiDirectoryRemainsUnchanged() {
        val directory = "D:\\AI play\\OpenCode\\project"

        assertEquals(directory, directoryHeaderValue(directory))
    }

    @Test
    fun unicodeWindowsDirectoryIsPercentEncoded() {
        val encoded = directoryHeaderValue("D:\\AI play\\OpenCode\\爬信息")

        assertEquals(
            "D%3A%5CAI%20play%5COpenCode%5C%E7%88%AC%E4%BF%A1%E6%81%AF",
            encoded,
        )
        assertFalse(encoded.any { it.code > 0x7f })
        assertFalse('+' in encoded)
    }

    @Test
    fun unicodeEncodingProtectsPercentAndSupplementaryCharacters() {
        val encoded = directoryHeaderValue("/tmp/100%/проект/🚀")

        assertTrue("%25" in encoded)
        assertTrue("%D0%BF%D1%80%D0%BE%D0%B5%D0%BA%D1%82" in encoded)
        assertTrue("%F0%9F%9A%80" in encoded)
        assertFalse(encoded.any { it.code > 0x7f })
    }
}
