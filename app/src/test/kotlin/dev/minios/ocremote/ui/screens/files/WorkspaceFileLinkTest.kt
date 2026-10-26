package dev.minios.ocremote.ui.screens.files

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFileLinkTest {
    @Test fun browsingCannotLeaveProjectRoot() {
        assertEquals("", workspaceRelativeDirectory("", "/work/project"))
        assertEquals("", workspaceRelativeDirectory("/work/project/", "/work/project"))
        assertEquals("src", workspaceRelativeDirectory("src/utils/..", "/work/project"))
        assertEquals(null, workspaceRelativeDirectory("..", "/work/project"))
        assertEquals(null, workspaceRelativeDirectory("src/../../other", "/work/project"))
        assertEquals(null, workspaceRelativeDirectory("/work/project-other", "/work/project"))
        assertEquals("src", workspaceRelativeDirectory("C:\\Project\\src", "C:\\Project"))
        assertEquals(null, workspaceRelativeDirectory("D:/Project", "C:/Project"))
        assertEquals(null, workspaceRelativeDirectory("../other", "C:/Project"))
    }
    @Test fun lineOffsetsHandleWindowsNewlinesAndMissingLines() {
        assertEquals(0, workspaceLineOffset("one\r\ntwo\r\nthree", 1))
        assertEquals(5, workspaceLineOffset("one\r\ntwo\r\nthree", 2))
        assertEquals(10, workspaceLineOffset("one\r\ntwo\r\nthree", 3))
        assertEquals(3, workspaceLineOffset("one", 99))
    }
    @Test fun parentNavigationPreservesFilesystemRoots() {
        assertEquals("/", workspaceParentPath("/tmp"))
        assertEquals("/", workspaceParentPath("/"))
        assertEquals("C:/", workspaceParentPath("C:\\Project"))
        assertEquals("C:/", workspaceParentPath("C:/"))
        assertEquals("src", workspaceParentPath("src/main.kt"))
    }
}
