package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningTitleTest {

    @Test
    fun extractsBoldHeadingText() {
        assertEquals(
            "Designing grouped message rendering approach",
            extractReasoningTitle("**Designing grouped message rendering approach**\n\nMore details"),
        )
    }

    @Test
    fun stripsCommonMarkdownPrefixesAndLinks() {
        assertEquals(
            "Review the rendering flow",
            extractReasoningTitle("## [Review](https://example.com) the `rendering` flow"),
        )
    }

    @Test
    fun ignoresFenceBeforeFirstContentLine() {
        assertEquals("Inspect parser", extractReasoningTitle("```\n**Inspect parser**"))
    }

    @Test
    fun returnsNullForFormattingOnlyText() {
        assertNull(extractReasoningTitle("\n---\n```"))
    }

    @Test
    fun removesExtractedTitleFromExpandedBody() {
        assertEquals(
            "Details with **formatting**.",
            removeReasoningTitleLine("**Inspect parser**\n\nDetails with **formatting**."),
        )
    }

    @Test
    fun leadingBoldTitleDoesNotIncludeRepeatedTrailingText() {
        assertEquals(
            "Evaluating messageID generation strategy",
            extractReasoningTitle(
                "**Evaluating messageID generation strategy**Evaluating messageID generation strategy",
            ),
        )
    }
}
