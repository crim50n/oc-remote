package dev.minios.ocremote.ui.screens.chat

import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.junit.Assert.assertEquals
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
}
