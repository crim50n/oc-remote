package dev.minios.ocremote.ui.screens.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectoryPathQueryTest {
    @Test
    fun homePrefixExpandsToServerHome() {
        assertEquals(
            DirectoryPathQuery("/home/live", ""),
            parseDirectoryPathQuery("~/", "/home/live"),
        )
        assertEquals(
            DirectoryPathQuery("/home/live", "go"),
            parseDirectoryPathQuery("~/go", "/home/live"),
        )
    }

    @Test
    fun absolutePathSplitsIntoParentAndSegment() {
        assertEquals(DirectoryPathQuery("/", "usr"), parseDirectoryPathQuery("/usr", "/home/live"))
        assertEquals(DirectoryPathQuery("/usr", ""), parseDirectoryPathQuery("/usr/", "/home/live"))
        assertEquals(DirectoryPathQuery("/usr", "local"), parseDirectoryPathQuery("/usr/local", "/home/live"))
    }

    @Test
    fun plainNameRemainsFuzzySearch() {
        assertNull(parseDirectoryPathQuery("project", "/home/live"))
    }
}
