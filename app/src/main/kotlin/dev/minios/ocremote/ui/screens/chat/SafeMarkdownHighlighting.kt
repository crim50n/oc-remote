package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import org.intellij.markdown.ast.ASTNode

val safeHighlightedCodeFence: MarkdownComponent = {
    SafeMarkdownHighlightedCodeFence(it.content, it.node)
}

val safeHighlightedCodeBlock: MarkdownComponent = {
    SafeMarkdownHighlightedCodeBlock(it.content, it.node)
}

@Composable
fun SafeMarkdownHighlightedCodeFence(
    content: String,
    node: ASTNode,
    highlights: Highlights.Builder = Highlights.Builder(),
) {
    MarkdownCodeFence(content, node) { code, language ->
        SafeMarkdownHighlightedCode(code, language, highlights)
    }
}

@Composable
fun SafeMarkdownHighlightedCodeBlock(
    content: String,
    node: ASTNode,
    highlights: Highlights.Builder = Highlights.Builder(),
) {
    MarkdownCodeBlock(content, node) { code, language ->
        SafeMarkdownHighlightedCode(code, language, highlights)
    }
}

@Composable
fun SafeMarkdownHighlightedCode(
    code: String,
    language: String?,
    highlights: Highlights.Builder = Highlights.Builder(),
    style: TextStyle = LocalMarkdownTypography.current.code,
) {
    val backgroundCodeColor = LocalMarkdownColors.current.codeBackground
    val codeBackgroundCornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val codeBlockPadding = LocalMarkdownPadding.current.codeBlock
    val codeScrollModifier = if (LocalCodeWordWrap.current) {
        Modifier
    } else {
        Modifier.horizontalScroll(rememberScrollState())
    }
    val annotatedCode = remember(code, language, highlights) {
        buildSafeHighlightedAnnotatedString(code, language, highlights)
    }

    MarkdownCodeBackground(
        color = backgroundCodeColor,
        shape = RoundedCornerShape(codeBackgroundCornerSize),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        MarkdownBasicText(
            annotatedCode,
            color = LocalMarkdownColors.current.codeText,
            modifier = codeScrollModifier
                .padding(codeBlockPadding),
            style = style,
        )
    }
}

internal fun buildSafeHighlightedAnnotatedString(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder,
): AnnotatedString {
    return runCatching {
        val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
        val codeHighlights = highlightsBuilder
            .code(code)
            .let { builder -> if (syntaxLanguage != null) builder.language(syntaxLanguage) else builder }
            .build()

        buildAnnotatedString {
            text(codeHighlights.getCode())

            codeHighlights.getHighlights()
                .filterIsInstance<ColorHighlight>()
                .forEach { highlight ->
                    addSafeStyle(
                        style = SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f)),
                        start = highlight.location.start,
                        end = highlight.location.end,
                        textLength = code.length,
                    )
                }

            codeHighlights.getHighlights()
                .filterIsInstance<BoldHighlight>()
                .forEach { highlight ->
                    addSafeStyle(
                        style = SpanStyle(fontWeight = FontWeight.Bold),
                        start = highlight.location.start,
                        end = highlight.location.end,
                        textLength = code.length,
                    )
                }
        }
    }.getOrDefault(AnnotatedString(code))
}

private fun AnnotatedString.Builder.addSafeStyle(
    style: SpanStyle,
    start: Int,
    end: Int,
    textLength: Int,
) {
    if (start < 0 || end > textLength || start >= end) return
    addStyle(style = style, start = start, end = end)
}

private fun AnnotatedString.Builder.text(
    text: String,
    style: SpanStyle = SpanStyle(),
) = withStyle(style = style) {
    append(text)
}
