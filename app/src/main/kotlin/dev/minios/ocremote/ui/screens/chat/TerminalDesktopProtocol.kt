package dev.minios.ocremote.ui.screens.chat

import android.view.KeyEvent

enum class TerminalMouseTracking { NONE, PRESS, DRAG, ANY }
enum class TerminalMouseEncoding { LEGACY, UTF8, SGR, URXVT }
internal data class TerminalCellPoint(val row: Int, val column: Int) : Comparable<TerminalCellPoint> {
    override fun compareTo(other: TerminalCellPoint): Int = compareValuesBy(this, other, { it.row }, { it.column })
}

internal fun terminalHardwareSequence(
    key: Int, unicode: Int = 0, ctrl: Boolean = false, alt: Boolean = false,
    shift: Boolean = false, applicationCursor: Boolean = false,
): String? {
    val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
    val cursor = when (key) {
        KeyEvent.KEYCODE_DPAD_UP -> "A"
        KeyEvent.KEYCODE_DPAD_DOWN -> "B"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "C"
        KeyEvent.KEYCODE_DPAD_LEFT -> "D"
        KeyEvent.KEYCODE_MOVE_HOME -> "H"
        KeyEvent.KEYCODE_MOVE_END -> "F"
        else -> null
    }
    if (cursor != null) return if (modifier > 1) "\u001b[1;$modifier$cursor"
        else "\u001b${if (applicationCursor) "O" else "["}$cursor"
    if (key in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F4) {
        val suffix = ('P'.code + key - KeyEvent.KEYCODE_F1).toChar()
        return if (modifier == 1) "\u001bO$suffix" else "\u001b[1;$modifier$suffix"
    }
    val numbered = when (key) {
        KeyEvent.KEYCODE_INSERT -> 2
        KeyEvent.KEYCODE_FORWARD_DEL -> 3
        KeyEvent.KEYCODE_PAGE_UP -> 5
        KeyEvent.KEYCODE_PAGE_DOWN -> 6
        in KeyEvent.KEYCODE_F5..KeyEvent.KEYCODE_F12 -> listOf(15, 17, 18, 19, 20, 21, 23, 24)[key - KeyEvent.KEYCODE_F5]
        else -> null
    }
    if (numbered != null) return "\u001b[$numbered${if (modifier > 1) ";$modifier" else ""}~"
    val value = when (key) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
        KeyEvent.KEYCODE_TAB -> if (shift) "\u001b[Z" else "\t"
        KeyEvent.KEYCODE_ESCAPE -> "\u001b"
        KeyEvent.KEYCODE_DEL -> if (ctrl) "\b" else "\u007f"
        else -> {
            if (ctrl && key in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                (key - KeyEvent.KEYCODE_A + 1).toChar().toString()
            } else if (ctrl) {
                when (unicode.toChar()) {
                    ' ', '@', '2' -> "\u0000"
                    '[', '3' -> "\u001b"
                    '\\', '4' -> "\u001c"
                    ']', '5' -> "\u001d"
                    '^', '6' -> "\u001e"
                    '_', '/', '7' -> "\u001f"
                    '?', '8' -> "\u007f"
                    else -> return null
                }
            } else if (unicode > 0 && Character.isValidCodePoint(unicode)) String(Character.toChars(unicode)) else return null
        }
    }
    return if (alt && key != KeyEvent.KEYCODE_TAB) "\u001b$value" else value
}

internal fun terminalPaste(text: String, bracketed: Boolean): String {
    val clean = text.filter { it in "\t\r\n" || (it.code >= 32 && it.code !in 127..159) }
        .replace("\r\n", "\n").replace('\r', '\n')
    if (clean.isEmpty()) return ""
    return if (bracketed) "\u001b[200~$clean\u001b[201~" else clean.replace('\n', '\r')
}

internal fun terminalMouseSequence(encoding: TerminalMouseEncoding, button: Int, column: Int, row: Int, release: Boolean = false): String? {
    if (column < 1 || row < 1) return null
    if (encoding == TerminalMouseEncoding.SGR) return "\u001b[<$button;$column;$row${if (release) "m" else "M"}"
    val code = if (release) (button and 28) or 3 else button
    if (encoding == TerminalMouseEncoding.URXVT) return "\u001b[${code + 32};$column;${row}M"
    // Text WebSocket frames are UTF-8; unnegotiated legacy coordinates must stay ASCII.
    val limit = if (encoding == TerminalMouseEncoding.UTF8) 2015 else 95
    if (column > limit || row > limit) return null
    return "\u001b[M${(code + 32).toChar()}${(column + 32).toChar()}${(row + 32).toChar()}"
}

internal fun terminalSelectedText(lines: List<String>, first: TerminalCellPoint?, last: TerminalCellPoint?): String {
    if (first == null || last == null || first == last || lines.isEmpty()) return ""
    val start = minOf(first, last)
    val end = maxOf(first, last)
    return (start.row.coerceAtLeast(0)..end.row.coerceAtMost(lines.lastIndex)).joinToString("\n") { row ->
        val line = lines[row]
        val from = if (row == start.row) start.column.coerceIn(0, line.length) else 0
        val to = if (row == end.row) end.column.coerceIn(from, line.length) else line.length
        line.substring(from, to)
    }
}
