package dev.minios.ocremote.ui.screens.chat

import androidx.compose.ui.graphics.Color
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeMarkdownHighlightingTest {
    @Test
    fun buildSafeHighlightedAnnotatedString_handlesReversedHighlightRanges() {
        val code = "key: maven-${'$'}{{ runner.os }}-${'$'}{{ hashFiles('**/pom.xml') }}-${'$'}{{ hashFiles('**/*.java') }}"
        val builder = Highlights.Builder()
            .theme(SyntaxThemes.default(darkMode = false))

        val annotated = buildSafeHighlightedAnnotatedString(
            code = code,
            language = null,
            highlightsBuilder = builder,
        )

        assertEquals(code, annotated.text)
    }

    @Test
    fun buildSafeHighlightedAnnotatedString_keepsPunctuationInBaseColor() {
        val code = "{\"name\": [\"minios-release\"], \"enabled\": true}\nNAME=value"
        val annotated = buildSafeHighlightedAnnotatedString(
            code = code,
            language = "bash",
            highlightsBuilder = Highlights.Builder().theme(SyntaxThemes.default(darkMode = true)),
        )

        code.forEachIndexed { index, char ->
            if (!char.isLetterOrDigit() && !char.isWhitespace()) {
                assertTrue(
                    "Unexpected syntax color at index $index",
                    annotated.spanStyles.none {
                        index >= it.start && index < it.end && it.item.color != Color.Unspecified
                    },
                )
            }
        }
    }
}
