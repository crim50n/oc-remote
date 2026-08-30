package dev.minios.ocremote.ui.screens.chat

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.logging.AppLogger as Log
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import org.intellij.markdown.ast.ASTNode

private const val TAG = "SafeMarkdownHighlight"

internal val safeHighlightedCodeFence: MarkdownComponent = {
    SafeMarkdownHighlightedCodeFence(it.content, it.node)
}

internal val safeHighlightedCodeBlock: MarkdownComponent = {
    SafeMarkdownHighlightedCodeBlock(it.content, it.node)
}

internal val horizontallyScrollableMarkdownTable: MarkdownComponent = {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        MarkdownTable(it.content, it.node, style = it.typography.table)
    }
}


@Composable
private fun SafeMarkdownHighlightedCodeFence(
    content: String,
    node: ASTNode,
    highlights: Highlights.Builder = Highlights.Builder(),
) {
    MarkdownCodeFence(content, node) { code, language, style ->
        SafeMarkdownHighlightedCode(code, language, highlights, style)
    }
}

@Composable
private fun SafeMarkdownHighlightedCodeBlock(
    content: String,
    node: ASTNode,
    highlights: Highlights.Builder = Highlights.Builder(),
) {
    MarkdownCodeBlock(content, node) { code, language, style ->
        SafeMarkdownHighlightedCode(code, language, highlights, style)
    }
}

@Composable
private fun SafeMarkdownHighlightedCode(
    code: String,
    language: String?,
    highlights: Highlights.Builder = Highlights.Builder(),
    style: TextStyle = LocalMarkdownTypography.current.code,
) {
    val backgroundCodeColor = LocalMarkdownColors.current.codeBackground
    val codeBackgroundCornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val codeBlockPadding = LocalMarkdownPadding.current.codeBlock
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
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
        Column(modifier = Modifier.fillMaxWidth()) {
            MarkdownBasicText(
                annotatedCode,
                color = style.color,
                modifier = codeScrollModifier
                    .padding(codeBlockPadding),
                style = style,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(context, R.string.chat_copied_clipboard, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .padding(end = 4.dp, bottom = 4.dp)
                        .size(22.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy),
                        modifier = Modifier.size(14.dp),
                        tint = style.color.copy(alpha = 0.42f),
                    )
                }
            }
        }
    }
}

internal fun buildSafeHighlightedAnnotatedString(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder,
): AnnotatedString {
    return try {
        val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
        val codeHighlights = highlightsBuilder
            .code(code)
            .let { builder -> if (syntaxLanguage != null) builder.language(syntaxLanguage) else builder }
            .build()

        val highlightedCode = codeHighlights.getCode()
        buildAnnotatedString {
            text(highlightedCode)

            codeHighlights.getHighlights()
                .filterIsInstance<ColorHighlight>()
                .forEach { highlight ->
                    addSafeColorStyle(
                        style = SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f)),
                        start = highlight.location.start,
                        end = highlight.location.end,
                        text = highlightedCode,
                    )
                }

            codeHighlights.getHighlights()
                .filterIsInstance<BoldHighlight>()
                .forEach { highlight ->
                    addSafeStyle(
                        style = SpanStyle(fontWeight = FontWeight.Bold),
                        start = highlight.location.start,
                        end = highlight.location.end,
                        textLength = highlightedCode.length,
                    )
                }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Syntax highlighting failed; rendering plain code", e)
        AnnotatedString(code)
    }
}

private fun AnnotatedString.Builder.addSafeColorStyle(
    style: SpanStyle,
    start: Int,
    end: Int,
    text: String,
) {
    if (start < 0 || end > text.length || start >= end) return
    var segmentStart = start
    for (index in start until end) {
        if (text[index].shouldInheritCodeColor()) {
            addSafeStyle(style, segmentStart, index, text.length)
            segmentStart = index + 1
        }
    }
    addSafeStyle(style, segmentStart, end, text.length)
}

private fun Char.shouldInheritCodeColor(): Boolean = when (Character.getType(this).toInt()) {
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
    Character.MATH_SYMBOL.toInt(),
    Character.CURRENCY_SYMBOL.toInt(),
    Character.MODIFIER_SYMBOL.toInt(),
    -> true
    else -> false
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
