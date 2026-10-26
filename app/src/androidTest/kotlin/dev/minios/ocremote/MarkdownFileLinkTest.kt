package dev.minios.ocremote

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mikepenz.markdown.m3.Markdown
import dev.minios.ocremote.ui.components.rememberInteractiveMarkdownLinks
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MarkdownFileLinkTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inlineApkPathOpensAsServerFile() {
        val path = "app/build/outputs/apk/debug/app-debug.apk"
        var opened: String? = null
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened = uri }
                }) {
                    SelectionContainer {
                        Markdown("APK: `$path`", annotator = rememberInteractiveMarkdownLinks())
                    }
                }
            }
        }
        compose.onNodeWithText(path, substring = true).performClick()
        compose.runOnIdle { assertEquals(path, opened) }
    }

    @Test fun fileLinksUseCurrentDestinationInsideSelectableMarkdown() {
        val markdown = mutableStateOf("[Report](file:///tmp/first.pdf)")
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened += uri }
                }) {
                    SelectionContainer {
                        Markdown(markdown.value, annotator = rememberInteractiveMarkdownLinks())
                    }
                }
            }
        }
        compose.onNodeWithText("Report").performClick()
        compose.runOnIdle { markdown.value = "[Report](file:///tmp/second.pdf#L12)" }
        compose.onNodeWithText("Report").performClick()
        compose.runOnIdle {
            assertEquals(listOf("file:///tmp/first.pdf", "file:///tmp/second.pdf#L12"), opened)
        }
    }
}
