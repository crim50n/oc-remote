package dev.minios.ocremote.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerFileLinkTest {
    @Test fun relativeMarkdownLinkPreservesLineRange() {
        assertEquals(ServerFileLink("src/main.kt", 12, 18), parseServerFileLink("src/main.kt#L12-L18"))
        assertEquals(ServerFileLink("src/main.kt", 12), parseServerFileLink("src/main.kt:12:4"))
    }

    @Test fun fileUriDecodesUnicodeSpacesAndLiteralPlus() {
        assertEquals(ServerFileLink("/tmp/Отчёт +.pdf"),
            parseServerFileLink("file:///tmp/%D0%9E%D1%82%D1%87%D1%91%D1%82%20+.pdf"))
        assertEquals(ServerFileLink("/tmp/a#b.txt"), parseServerFileLink("file://localhost/tmp/a%23b.txt"))
    }

    @Test fun windowsUriAndLineSuffixResolveOnServer() {
        val link = parseServerFileLink("file:///C:/Project/src/main.kt#L3")!!
        assertEquals(ServerFileLink("C:/Project/src/main.kt", 3), link)
        assertEquals(ServerFileRequest("C:/Project/src", "main.kt"), serverFileRequest(link.path, "C:/Project"))
        assertEquals(ServerFileRequest("D:/Работа", "report.pdf"), serverFileRequest("D:\\Работа\\report.pdf", "C:/Project"))
    }

    @Test fun outsideWorkspaceUsesFilesOwnDirectory() {
        assertEquals(ServerFileRequest("/tmp", "report.pdf"), serverFileRequest("/tmp/report.pdf", "/work/repo"))
        assertEquals(ServerFileRequest("/work", "report.pdf"), serverFileRequest("../report.pdf", "/work/repo"))
        assertEquals(ServerFileRequest("/work/repo/docs", "guide.md"), serverFileRequest("docs/guide.md", "/work/repo"))
    }

    @Test fun rootsStayValid() {
        assertEquals(ServerFileRequest("/", "report.txt"), serverFileRequest("/report.txt", "/repo"))
        assertEquals(ServerFileRequest("C:/", "report.txt"), serverFileRequest("C:/report.txt", "C:/repo"))
    }

    @Test fun ordinaryUrlsAndDeviceSchemesAreNotServerFiles() {
        listOf("https://example.com/report.pdf", "http://localhost:3000", "mailto:a@b.test",
            "content://media/1", "javascript:alert(1)", "#section", "file://otherhost/share/report.txt",
            "//otherhost/share/report.txt", "file:///tmp/a%00b", "~/file.txt").forEach {
            assertNull(it, parseServerFileLink(it))
        }
    }
}
