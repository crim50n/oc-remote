package dev.minios.ocremote.ui.screens.chat

private val HEADING_PREFIX = Regex("^#{1,6}\\s+")
private val QUOTE_PREFIX = Regex("^>+\\s*")
private val LIST_PREFIX = Regex("^(?:[-+*]|\\d+[.)])\\s+")
private val MARKDOWN_LINK = Regex("!?\\[([^]]+)]\\([^)]+\\)")
private val WHITESPACE = Regex("\\s+")
private val THEMATIC_RULE = Regex("^(?:[-*_]\\s*){3,}$")
private val TABLE_SEPARATOR = Regex("^\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?$")
private val LEADING_BOLD_TITLE = Regex("^\\*\\*(.+?)\\*\\*")

internal fun extractReasoningTitle(markdown: String, maxLength: Int = 100): String? {
    val line = markdown.lineSequence().map(String::trim).firstOrNull(::isReasoningTitleLine)
        ?: return null

    val normalized = line
        .replace(HEADING_PREFIX, "")
        .replace(QUOTE_PREFIX, "")
        .replace(LIST_PREFIX, "")
    val title = (LEADING_BOLD_TITLE.find(normalized)?.groupValues?.get(1) ?: normalized)
        .replace(MARKDOWN_LINK, "$1")
        .replace("`", "")
        .replace("**", "")
        .replace("__", "")
        .replace("~~", "")
        .trim('*', '_', '~', ' ')
        .replace(WHITESPACE, " ")
        .trim()

    if (title.isEmpty()) return null
    return if (title.length <= maxLength) title else title.take(maxLength - 1).trimEnd() + "…"
}

internal fun removeReasoningTitleLine(markdown: String): String {
    val lines = markdown.lines().toMutableList()
    val titleIndex = lines.indexOfFirst { isReasoningTitleLine(it.trim()) }
    if (titleIndex < 0) return markdown
    lines.removeAt(titleIndex)
    if (titleIndex < lines.size && lines[titleIndex].isBlank()) lines.removeAt(titleIndex)
    return lines.joinToString("\n").trim()
}

private fun isReasoningTitleLine(candidate: String): Boolean =
    candidate.isNotEmpty() &&
        !candidate.startsWith("```") &&
        !candidate.startsWith("~~~") &&
        !THEMATIC_RULE.matches(candidate) &&
        !TABLE_SEPARATOR.matches(candidate)
