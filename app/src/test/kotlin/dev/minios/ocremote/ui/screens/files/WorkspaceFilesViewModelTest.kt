package dev.minios.ocremote.ui.screens.files

import dev.minios.ocremote.data.api.FileContent
import dev.minios.ocremote.data.api.FileNode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceFilesViewModelTest {
    @Test
    fun resolvesParentPaths() {
        assertEquals("src/main", workspaceParentPath("src/main/kotlin"))
        assertEquals("", workspaceParentPath("src"))
        assertEquals("", workspaceParentPath(""))
    }

    @Test
    fun decodesTextAndBase64FileContent() {
        assertArrayEquals(
            "hello".toByteArray(),
            workspaceFileBytes(FileContent(type = "text", content = "hello")),
        )
        assertArrayEquals(
            "image".toByteArray(),
            workspaceFileBytes(
                FileContent(type = "text", content = "aW1hZ2U=", encoding = "base64", mimeType = "image/png"),
            ),
        )
        assertNull(workspaceFileBytes(FileContent(type = "binary", content = "")))
    }

    @Test
    fun determinesMimeTypeFromResponseOrFilename() {
        val node = FileNode(name = "config.json", path = "config.json", type = "file")

        assertEquals("application/json", workspaceFileMimeType(node, FileContent("text", "{}")))
        assertEquals(
            "application/custom",
            workspaceFileMimeType(node, FileContent("text", "{}", mimeType = "application/custom")),
        )
    }

    @Test
    fun classifiesFileTypesAndSyntaxLanguages() {
        fun node(name: String, type: String = "file") = FileNode(name = name, path = name, type = type)

        assertEquals(WorkspaceFileKind.Directory, workspaceFileKind(node("src", "directory")))
        assertEquals(WorkspaceFileKind.Code, workspaceFileKind(node("MainActivity.kt")))
        assertEquals(WorkspaceFileKind.Config, workspaceFileKind(node("build.gradle")))
        assertEquals(WorkspaceFileKind.Config, workspaceFileKind(node(".gitignore")))
        assertEquals(WorkspaceFileKind.Image, workspaceFileKind(node("preview.webp")))
        assertEquals(WorkspaceFileKind.Archive, workspaceFileKind(node("sources.zip")))
        assertEquals("kotlin", workspaceSyntaxLanguage("MainActivity.kt"))
        assertEquals("typescript", workspaceSyntaxLanguage("component.tsx"))
        assertEquals(null, workspaceSyntaxLanguage("LICENSE"))
        assertEquals(true, isWorkspaceMarkdownFile("README.md"))
        assertEquals(true, isWorkspaceMarkdownFile("guide.MARKDOWN"))
        assertEquals(false, isWorkspaceMarkdownFile("notes.txt"))
    }
}
