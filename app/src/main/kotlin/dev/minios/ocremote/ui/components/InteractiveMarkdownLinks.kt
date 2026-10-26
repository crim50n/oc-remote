package dev.minios.ocremote.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import com.mikepenz.markdown.model.DefaultMarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.utils.buildMarkdownAnnotatedString
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.MarkdownElementTypes
import dev.minios.ocremote.data.api.parseServerFileLink

internal fun inlineServerFileLink(text: String): String? {
    val value = text.trim()
    if (value.isEmpty() || value.any { it.isWhitespace() } || value.any { it in "|;&=<>*{}\"'" }) return null
    val file = parseServerFileLink(value) ?: return null
    val name = file.path.substringAfterLast('/').substringAfterLast('\\')
    val knownName = name in setOf("README", "LICENSE", "Dockerfile", "Makefile", "Containerfile", "CHANGELOG", ".env", ".gitignore")
    val extension = Regex("^[^.]+\\.[A-Za-z0-9]{1,12}$").matches(name)
    return value.takeIf { '/' in file.path || '\\' in file.path || knownName || extension }
}

/** Native Compose link gestures remain current while Markdown text streams and selection is enabled. */
@Composable
fun rememberInteractiveMarkdownLinks(
    fallback: MarkdownAnnotator? = null,
    linkColor: Color = MaterialTheme.colorScheme.primary,
): MarkdownAnnotator {
    val handler by rememberUpdatedState(LocalUriHandler.current)
    return remember(fallback, linkColor) {
        DefaultMarkdownAnnotator { content, node ->
            if (node.type == MarkdownElementTypes.INLINE_LINK) {
                val destination = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                    ?.getUnescapedTextInNode(content)?.removeSurrounding("<", ">")
                val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                if (destination != null && label != null) {
                    val style = SpanStyle(color = linkColor)
                    pushLink(LinkAnnotation.Clickable(destination, TextLinkStyles(style)) { handler.openUri(destination) })
                    buildMarkdownAnnotatedString(content, label.children.drop(1).dropLast(1), style,
                        SpanStyle(fontFamily = FontFamily.Monospace), fallback)
                    pop()
                    true
                } else false
            } else if (node.type == MarkdownElementTypes.CODE_SPAN) {
                val raw = content.substring(node.startOffset, node.endOffset).trim('`').trim()
                val destination = inlineServerFileLink(raw)
                if (destination != null) {
                    val style = SpanStyle(color = linkColor, fontFamily = FontFamily.Monospace)
                    pushLink(LinkAnnotation.Clickable(destination, TextLinkStyles(style)) { handler.openUri(destination) })
                    pushStyle(style)
                    append(raw)
                    pop()
                    pop()
                    true
                } else fallback?.annotate?.invoke(this, content, node) ?: false
            } else fallback?.annotate?.invoke(this, content, node) ?: false
        }
    }
}
