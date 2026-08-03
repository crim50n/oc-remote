package dev.minios.ocremote.ui.screens.chat

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.utils.buildMarkdownAnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTildeTest {
    @Test
    fun `renderer preserves standalone tilde`() {
        val rendered = render("Use ~/project or a ~ character")

        assertEquals("Use ~/project or a ~ character", rendered.text)
    }

    @Test
    fun `renderer applies line through to strikethrough`() {
        val rendered = render("Keep ~~strikethrough~~")

        assertEquals("Keep strikethrough", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun `renderer applies line through to cyrillic strikethrough`() {
        val rendered = render("Теперь ~~текст~~ готов")

        assertEquals("Теперь текст готов", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun `renderer applies line through to unicode text`() {
        val samples = listOf(
            "日本語",
            "العربية",
            "हिन्दी",
            "한국어",
            "emoji 😀",
            "e\u0301",
            "mixed 日本語 العربية हिन्दी 😀",
        )

        samples.forEach { sample ->
            val rendered = render("~~$sample~~")

            assertEquals(sample, rendered.text)
            assertTrue(
                "Missing line-through for: $sample",
                rendered.spanStyles.any {
                    it.item.textDecoration == TextDecoration.LineThrough &&
                        it.start == 0 &&
                        it.end == rendered.text.length
                },
            )
        }
    }

    @Test
    fun `renderer preserves tildes inside code`() {
        val rendered = render("Inline `~/project`")

        assertTrue(rendered.text.contains("~/project"))
    }

    @Test
    fun `renderer preserves email autolink label`() {
        assertEquals("test@example.com", render("<test@example.com>").text)
    }

    @Test
    fun `renderer preserves task markers`() {
        val content = "- [x] Выполнено\n- [ ] Ожидает"
        assertEquals("- ☑ Выполнено\n- ☐ Ожидает", normalizeTaskListMarkers(content))
    }

    @Test
    fun `ordinary list and fenced tasks remain unchanged`() {
        val content = "- Обычный пункт\n```text\n- [x] Не задача\n```"

        assertEquals(content, normalizeTaskListMarkers(content))
    }

    @Test
    fun `markdown image url is extracted from image node range`() {
        val content = "Before ![Preview](https://example.com/image.png) after"
        val start = content.indexOf("![Preview]")
        val end = content.indexOf(")", start) + 1

        assertEquals(
            "https://example.com/image.png",
            markdownImageUrl(content, start, end),
        )
    }

    @Test
    fun `markdown image url supports angle brackets`() {
        val content = "![Preview](<https://example.com/image file.png>)"

        assertEquals(
            "https://example.com/image file.png",
            markdownImageUrl(content, 0, content.length),
        )
    }

    @Test
    fun `markdown image url is extracted from parsed image node`() {
        val content = "![Preview](https://example.com/image.png)"
        val tree = MarkdownParser(ChatMarkdownFlavour).buildMarkdownTreeFromString(content)
        val imageNode = tree.findDescendant(MarkdownElementTypes.IMAGE)

        assertEquals(
            "https://example.com/image.png",
            markdownImageUrl(content, requireNotNull(imageNode)),
        )
    }

    private fun render(markdown: String) = markdown.buildMarkdownAnnotatedString(
        style = TextStyle.Default,
        linkTextSpanStyle = SpanStyle(),
        codeSpanStyle = SpanStyle(),
        flavour = ChatMarkdownFlavour,
        annotator = ChatMarkdownAnnotator,
    )

    private fun org.intellij.markdown.ast.ASTNode.findDescendant(
        type: org.intellij.markdown.IElementType,
    ): org.intellij.markdown.ast.ASTNode? {
        if (this.type == type) return this
        return children.firstNotNullOfOrNull { it.findDescendant(type) }
    }

}
