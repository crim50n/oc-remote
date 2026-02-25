package dev.minios.ocremote.ui.screens.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * A proper terminal emulator that maintains a fixed-size screen buffer.
 *
 * Modelled after Termux's TerminalEmulator.java but simplified for our use case.
 * Processes raw PTY output incrementally and maintains screen state including:
 * - Fixed-size cell grid with cursor tracking
 * - Scroll regions (DECSTBM)
 * - Alternate screen buffer with save/restore
 * - Insert/delete line (IL/DL)
 * - All erase modes (ED 0/1/2/3, EL 0/1/2)
 * - SGR attributes (colors, bold, reverse, underline, italic)
 * - 256-color and 24-bit true-color support
 */
class TerminalEmulator(initialCols: Int = 80, initialRows: Int = 24) {

    // ── Cell & Screen ────────────────────────────────────────────────

    data class Cell(
        var ch: Char = ' ',
        var fg: Color? = null,
        var bg: Color? = null,
        var bold: Boolean = false,
        var reverse: Boolean = false,
        var underline: Boolean = false,
        var italic: Boolean = false,
    ) {
        fun reset() {
            ch = ' '; fg = null; bg = null; bold = false; reverse = false
            underline = false; italic = false
        }

        fun copyFrom(other: Cell) {
            ch = other.ch; fg = other.fg; bg = other.bg; bold = other.bold
            reverse = other.reverse; underline = other.underline; italic = other.italic
        }
    }

    // Current screen dimensions
    var cols: Int = initialCols
        private set
    var rows: Int = initialRows
        private set

    // Main and alternate screen buffers
    private var mainScreen: Array<Array<Cell>> = makeScreen(rows, cols)
    private var altScreen: Array<Array<Cell>> = makeScreen(rows, cols)
    private var screen: Array<Array<Cell>> = mainScreen
    private var onAltScreen = false

    // Cursor
    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set

    // Scroll region (top inclusive, bottom exclusive, like Termux)
    private var topMargin: Int = 0
    private var bottomMargin: Int = rows

    // Saved cursor state (main & alt have independent saves)
    private data class SavedState(
        var row: Int = 0,
        var col: Int = 0,
        var fg: Color? = null,
        var bg: Color? = null,
        var bold: Boolean = false,
        var reverse: Boolean = false,
        var underline: Boolean = false,
        var italic: Boolean = false,
        var useLineDrawingG0: Boolean = false,
        var useLineDrawingG1: Boolean = false,
        var useG0: Boolean = true,
    )

    private var savedMain = SavedState()
    private var savedAlt = SavedState()

    // Current text attributes
    private var attrFg: Color? = null
    private var attrBg: Color? = null
    private var attrBold: Boolean = false
    private var attrReverse: Boolean = false
    private var attrUnderline: Boolean = false
    private var attrItalic: Boolean = false

    // Auto-wrap pending flag (like Termux's mAboutToAutoWrap)
    private var aboutToAutoWrap = false

    // G0/G1 charset state (VT100 line-drawing support)
    private var useLineDrawingG0 = false   // true = G0 is DEC Special Graphics
    private var useLineDrawingG1 = false   // true = G1 is DEC Special Graphics
    private var useG0 = true               // true = GL invokes G0, false = GL invokes G1

    // DEC private modes exposed to UI
    var cursorKeysApplicationMode = false   // DECCKM: true = application cursor keys (ESC O), false = normal (ESC [)
        private set

    // ESC sequence parser state
    private var escState = EscState.NORMAL
    private val escParams = StringBuilder()

    // Scrollback buffer for main screen (limited size)
    private val scrollback = mutableListOf<Array<Cell>>()
    private val maxScrollback = 500

    // Version counter — incremented on any screen change for compose recomposition
    var version: Long = 0L
        private set

    private enum class EscState {
        NORMAL,
        ESC,        // saw ESC
        CSI,        // saw ESC[
        OSC,        // saw ESC]
        ESC_SELECT_G0,  // saw ESC( — next char designates G0 charset
        ESC_SELECT_G1,  // saw ESC) — next char designates G1 charset
        ESC_SKIP1,      // saw ESC# — skip next char
    }

    // ── Public API ───────────────────────────────────────────────────

    /** Process a chunk of raw PTY output. */
    @Synchronized
    fun process(data: String) {
        for (ch in data) {
            processChar(ch)
        }
        version++
    }

    /** Resize the terminal. Clamps cursor and resets margins. */
    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        if (newCols == cols && newRows == rows) return

        mainScreen = resizeScreen(mainScreen, rows, cols, newRows, newCols)
        altScreen = resizeScreen(altScreen, rows, cols, newRows, newCols)
        screen = if (onAltScreen) altScreen else mainScreen

        cols = newCols
        rows = newRows
        topMargin = 0
        bottomMargin = rows
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        aboutToAutoWrap = false
        version++
    }

    /** Reset to initial state. */
    @Synchronized
    fun reset() {
        screen = mainScreen
        onAltScreen = false
        clearScreenCells(mainScreen)
        clearScreenCells(altScreen)
        cursorRow = 0
        cursorCol = 0
        topMargin = 0
        bottomMargin = rows
        attrFg = null; attrBg = null; attrBold = false; attrReverse = false
        attrUnderline = false; attrItalic = false
        aboutToAutoWrap = false
        useLineDrawingG0 = false; useLineDrawingG1 = false; useG0 = true
        cursorKeysApplicationMode = false
        escState = EscState.NORMAL
        escParams.clear()
        oscEscSeen = false
        savedMain = SavedState()
        savedAlt = SavedState()
        scrollback.clear()
        version++
    }

    /** Total visible rows including scrollback history. */
    @Synchronized
    fun totalRowsWithScrollback(): Int = scrollback.size + rows

    /** Maximum scrollback offset for a given viewport height in rows. */
    @Synchronized
    fun maxScrollbackOffset(windowRows: Int): Int {
        if (windowRows <= 0) return 0
        return (totalRowsWithScrollback() - windowRows).coerceAtLeast(0)
    }

    /** Render visible window (screen + optional scrollback) to AnnotatedString. */
    @Synchronized
    fun render(scrollbackOffsetRows: Int = 0, windowRows: Int = rows): AnnotatedString {
        val defaultFg = Color(0xFFD3D7CF)
        val defaultBg = Color.Black
        val visibleRows = resolveVisibleRows(scrollbackOffsetRows, windowRows)

        return buildAnnotatedString {
            for (r in visibleRows.indices) {
                val row = visibleRows[r]
                var runStart = 0
                while (runStart < cols) {
                    val refCell = cellAt(row, runStart)
                    val sb = StringBuilder()
                    sb.append(refCell.ch)
                    var runEnd = runStart + 1
                    while (runEnd < cols) {
                        val nextCell = cellAt(row, runEnd)
                        if (!sameStyle(nextCell, refCell)) break
                        sb.append(nextCell.ch)
                        runEnd++
                    }
                    // Resolve colors
                    val effFg: Color
                    val effBg: Color
                    if (refCell.reverse) {
                        effFg = refCell.bg ?: defaultBg
                        effBg = refCell.fg ?: defaultFg
                    } else {
                        effFg = refCell.fg ?: Color.Unspecified
                        effBg = refCell.bg ?: Color.Unspecified
                    }
                    withStyle(SpanStyle(
                        color = effFg,
                        background = if (effBg != Color.Unspecified) effBg else Color.Unspecified,
                        fontWeight = if (refCell.bold) FontWeight.SemiBold else FontWeight.Normal,
                    )) {
                        append(sb.toString())
                    }
                    runStart = runEnd
                }

                if (r < visibleRows.lastIndex) append('\n')
            }
        }
    }

    /**
     * Render visible window as plain text for copy/selection.
     * Trailing spaces are trimmed per line to avoid copying terminal padding.
     */
    @Synchronized
    fun renderSelectionText(scrollbackOffsetRows: Int = 0, windowRows: Int = rows): String {
        val visibleRows = resolveVisibleRows(scrollbackOffsetRows, windowRows)
        if (visibleRows.isEmpty()) return ""

        val out = StringBuilder()
        for (r in visibleRows.indices) {
            val row = visibleRows[r]
            val lineChars = CharArray(cols)
            for (c in 0 until cols) {
                lineChars[c] = cellAt(row, c).ch
            }
            out.append(String(lineChars).trimEnd(' '))
            if (r < visibleRows.lastIndex) out.append('\n')
        }
        return out.toString()
    }

    /**
     * A horizontal span of identically-styled characters in a terminal row.
     * [col] is the 0-based starting column.
     */
    data class TerminalRun(
        val col: Int,
        val text: String,
        val fg: Color,
        val bg: Color,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
    )

    /**
     * Render the screen buffer as a list of rows, each containing a list of styled runs.
     * Each run is placed at an exact column position so the caller can draw it at
     * `col * charWidth` — this avoids glyph-width misalignment for box-drawing characters.
     */
    @Synchronized
    fun renderRuns(scrollbackOffsetRows: Int = 0, windowRows: Int = rows): List<List<TerminalRun>> {
        val defaultFg = Color(0xFFD3D7CF)
        val defaultBg = Color.Black
        val visibleRows = resolveVisibleRows(scrollbackOffsetRows, windowRows)

        val result = ArrayList<List<TerminalRun>>(visibleRows.size)
        for (r in visibleRows.indices) {
            val row = visibleRows[r]
            val runs = mutableListOf<TerminalRun>()
            var runStart = 0
            while (runStart < cols) {
                val refCell = cellAt(row, runStart)
                val sb = StringBuilder()
                sb.append(refCell.ch)
                var runEnd = runStart + 1
                while (runEnd < cols) {
                    val nextCell = cellAt(row, runEnd)
                    if (!sameStyle(nextCell, refCell)) break
                    sb.append(nextCell.ch)
                    runEnd++
                }
                val effFg: Color
                val effBg: Color
                if (refCell.reverse) {
                    effFg = refCell.bg ?: defaultBg
                    effBg = refCell.fg ?: defaultFg
                } else {
                    effFg = refCell.fg ?: defaultFg
                    effBg = refCell.bg ?: Color.Unspecified
                }
                runs.add(TerminalRun(
                    col = runStart,
                    text = sb.toString(),
                    fg = effFg,
                    bg = effBg,
                    bold = refCell.bold,
                    italic = refCell.italic,
                    underline = refCell.underline,
                ))
                runStart = runEnd
            }
            result.add(runs)
        }
        return result
    }

    /** Get cursor position for rendering. */
    @Synchronized
    fun getCursorPosition(): Pair<Int, Int> = cursorRow to cursorCol

    /**
     * Cursor position relative to the currently visible window.
     * Returns null when cursor is outside the visible viewport (e.g. user scrolled into history).
     */
    @Synchronized
    fun getCursorPositionInWindow(scrollbackOffsetRows: Int = 0, windowRows: Int = rows): Pair<Int, Int>? {
        val rowCount = windowRows.coerceAtLeast(1)
        val totalRows = scrollback.size + rows
        val maxOffset = (totalRows - rowCount).coerceAtLeast(0)
        val offset = scrollbackOffsetRows.coerceIn(0, maxOffset)
        val startRow = (totalRows - rowCount - offset).coerceAtLeast(0)

        val absoluteCursorRow = scrollback.size + cursorRow
        val visibleCursorRow = absoluteCursorRow - startRow
        if (visibleCursorRow !in 0 until rowCount) return null
        return visibleCursorRow to cursorCol
    }

    // ── Character Processing ─────────────────────────────────────────

    private fun processChar(ch: Char) {
        when (escState) {
            EscState.NORMAL -> processNormal(ch)
            EscState.ESC -> processEsc(ch)
            EscState.CSI -> processCsi(ch)
            EscState.OSC -> processOsc(ch)
            EscState.ESC_SELECT_G0 -> {
                // '0' = DEC Special Graphics (line drawing), anything else = ASCII
                useLineDrawingG0 = (ch == '0')
                escState = EscState.NORMAL
            }
            EscState.ESC_SELECT_G1 -> {
                useLineDrawingG1 = (ch == '0')
                escState = EscState.NORMAL
            }
            EscState.ESC_SKIP1 -> {
                // Consume one char (e.g., DEC line attrs after ESC#)
                escState = EscState.NORMAL
            }
        }
    }

    private fun processNormal(ch: Char) {
        when (ch) {
            '\u001B' -> {
                escState = EscState.ESC
            }
            '\r' -> {
                cursorCol = 0
                aboutToAutoWrap = false
            }
            '\n', '\u000B', '\u000C' -> {
                doLinefeed()
                // Most terminals do LF = newline (move to col 0) only if ONLCR is set.
                // PTY layer typically handles this, but server-side shells usually have it set.
                // We don't move col to 0 here — LF just moves down.
            }
            '\t' -> {
                // Move to next tab stop (every 8 columns)
                aboutToAutoWrap = false
                val nextTab = ((cursorCol / 8) + 1) * 8
                cursorCol = nextTab.coerceAtMost(cols - 1)
            }
            '\b' -> {
                aboutToAutoWrap = false
                if (cursorCol > 0) cursorCol--
            }
            '\u0007' -> {
                // Bell — ignore
            }
            '\u000E', '\u000F' -> {
                // Shift Out (0x0E) = invoke G1, Shift In (0x0F) = invoke G0
                useG0 = (ch == '\u000F')
            }
            else -> {
                if (ch.code < 32) return // ignore other control chars
                emitChar(ch)
            }
        }
    }

    private fun processEsc(ch: Char) {
        escState = EscState.NORMAL
        when (ch) {
            '[' -> {
                escState = EscState.CSI
                escParams.clear()
            }
            ']' -> {
                escState = EscState.OSC
                escParams.clear()
            }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> doLinefeed()  // Index (IND)
            'E' -> {            // Next Line (NEL)
                cursorCol = 0
                doLinefeed()
            }
            'M' -> doReverseIndex()
            'c' -> reset()      // Full reset (RIS)
            '(' -> {
                // Designate G0 charset — next char selects (0=line drawing, B=ASCII)
                escState = EscState.ESC_SELECT_G0
            }
            ')' -> {
                // Designate G1 charset — next char selects
                escState = EscState.ESC_SELECT_G1
            }
            '*', '+' -> {
                // Designate G2/G3 charset — currently unsupported, but consume designator char.
                escState = EscState.ESC_SKIP1
            }
            '#' -> {
                // DEC line attrs — skip next char
                escState = EscState.ESC_SKIP1
            }
            '>' -> { /* DECKPNM — ignore */ }
            '=' -> { /* DECKPAM — ignore */ }
            else -> { /* Unknown ESC sequence — ignore */ }
        }
    }

    private fun processCsi(ch: Char) {
        // Collect parameter characters
        if (ch in '0'..'9' || ch == ';' || ch == '?' || ch == '>' || ch == '!' || ch == ' ' || ch == '"' || ch == '\'') {
            escParams.append(ch)
            return
        }
        // Final character — execute
        escState = EscState.NORMAL
        val paramsStr = escParams.toString()
        val privateMode = paramsStr.startsWith("?")
        val numStr = if (privateMode) paramsStr.drop(1) else paramsStr
        val params = if (numStr.isBlank()) emptyList() else numStr.split(';').map { it.toIntOrNull() ?: 0 }

        when (ch) {
            'm' -> applySgr(paramsStr)
            'H', 'f' -> {
                // Cursor Position
                val targetRow = (params.getOrNull(0) ?: 1).coerceAtLeast(1) - 1
                val targetCol = (params.getOrNull(1) ?: 1).coerceAtLeast(1) - 1
                cursorRow = targetRow.coerceIn(0, rows - 1)
                cursorCol = targetCol.coerceIn(0, cols - 1)
                aboutToAutoWrap = false
            }
            'A' -> {
                // Cursor Up
                val n = (params.getOrNull(0) ?: 1).coerceAtLeast(1)
                cursorRow = (cursorRow - n).coerceAtLeast(0)
                aboutToAutoWrap = false
            }
            'B' -> {
                // Cursor Down
                val n = (params.getOrNull(0) ?: 1).coerceAtLeast(1)
                cursorRow = (cursorRow + n).coerceIn(0, rows - 1)
                aboutToAutoWrap = false
            }
            'C' -> {
                // Cursor Forward
                val n = (params.getOrNull(0) ?: 1).coerceAtLeast(1)
                cursorCol = (cursorCol + n).coerceIn(0, cols - 1)
                aboutToAutoWrap = false
            }
            'D' -> {
                // Cursor Back
                val n = (params.getOrNull(0) ?: 1).coerceAtLeast(1)
                cursorCol = (cursorCol - n).coerceAtLeast(0)
                aboutToAutoWrap = false
            }
            'E' -> {
                // Cursor Next Line
                val n = (params.getOrNull(0) ?: 1).coerceAtLeast(1)
                cursorRow = (cursorRow + n).coerceIn(0, rows - 1)
                cursorCol = 0
                aboutToAutoWrap = false
            }
            'F' -> {
                // Cursor Previous Line
                val n = (params.getOrNull(0) ?: 1).coerceAtLeast(1)
                cursorRow = (cursorRow - n).coerceAtLeast(0)
                cursorCol = 0
                aboutToAutoWrap = false
            }
            'G' -> {
                // Cursor Horizontal Absolute
                val n = (params.getOrNull(0) ?: 1).coerceAtLeast(1) - 1
                cursorCol = n.coerceIn(0, cols - 1)
                aboutToAutoWrap = false
            }
            'd' -> {
                // Vertical Position Absolute (VPA)
                val n = (params.getOrNull(0) ?: 1).coerceAtLeast(1) - 1
                cursorRow = n.coerceIn(0, rows - 1)
                aboutToAutoWrap = false
            }
            'J' -> doEraseInDisplay(params.getOrNull(0) ?: 0)
            'K' -> doEraseInLine(params.getOrNull(0) ?: 0)
            'L' -> doInsertLines((params.getOrNull(0) ?: 1).coerceAtLeast(1))
            'M' -> doDeleteLines((params.getOrNull(0) ?: 1).coerceAtLeast(1))
            'S' -> {
                // Scroll Up
                val n = (params.getOrNull(0) ?: 1).coerceAtLeast(1)
                repeat(n) { scrollUp() }
            }
            'T' -> {
                // Scroll Down
                if (params.size <= 1) { // CSI T with >1 params is highlight mouse tracking, ignore
                    val n = (params.getOrNull(0) ?: 1).coerceAtLeast(1)
                    repeat(n) { scrollDown() }
                }
            }
            'P' -> {
                // Delete Characters (DCH)
                val n = (params.getOrNull(0) ?: 1).coerceAtLeast(1)
                doDeleteChars(n)
            }
            '@' -> {
                // Insert Characters (ICH)
                val n = (params.getOrNull(0) ?: 1).coerceAtLeast(1)
                doInsertChars(n)
            }
            'X' -> {
                // Erase Characters (ECH)
                val n = (params.getOrNull(0) ?: 1).coerceAtLeast(1)
                doEraseChars(n)
            }
            'r' -> {
                // Set Scrolling Region (DECSTBM)
                val top = (params.getOrNull(0) ?: 1).coerceAtLeast(1) - 1
                val bot = (params.getOrNull(1) ?: rows)
                topMargin = top.coerceIn(0, rows - 2)
                bottomMargin = bot.coerceIn(topMargin + 2, rows)
                // Move cursor to home
                cursorRow = 0
                cursorCol = 0
                aboutToAutoWrap = false
            }
            's' -> {
                if (!privateMode) {
                    saveCursor()
                }
            }
            'u' -> {
                restoreCursor()
            }
            'h' -> {
                if (privateMode) doDecSet(params, true)
            }
            'l' -> {
                if (privateMode) doDecSet(params, false)
            }
            'n' -> {
                // Device Status Report — we can't respond, ignore
            }
            'c' -> {
                // Device Attributes — ignore
            }
            't' -> {
                // Window manipulation — ignore
            }
            'p' -> {
                // Various — DECSTR (soft reset) if params contain "!"
                if (paramsStr.contains("!")) {
                    // Soft reset
                    topMargin = 0
                    bottomMargin = rows
                    attrFg = null; attrBg = null; attrBold = false; attrReverse = false
                    attrUnderline = false; attrItalic = false
                    aboutToAutoWrap = false
                }
            }
            'q' -> { /* DECLL, cursor style — ignore */ }
        }
    }

    // Flag to consume the '\' after ESC in an OSC ST terminator
    private var oscEscSeen = false

    private fun processOsc(ch: Char) {
        // Handle the '\' after ESC (completing the ST = ESC\ terminator)
        if (oscEscSeen) {
            oscEscSeen = false
            if (ch == '\\') {
                // ST complete — terminate OSC
                escState = EscState.NORMAL
                escParams.clear()
                return
            }
            // Not a ST — the ESC starts a new sequence
            escState = EscState.ESC
            escParams.clear()
            processEsc(ch)
            return
        }

        // OSC sequences terminated by BEL or ST (ESC\)
        if (ch == '\u0007') {
            escState = EscState.NORMAL
            escParams.clear()
            return
        }
        if (ch == '\u001B') {
            // Might be ST (ESC\) — need to check next char
            oscEscSeen = true
            return
        }
        // Accumulate (but don't do anything with OSC content)
        if (escParams.length < 256) {
            escParams.append(ch)
        }
    }

    // ── Character Emission ───────────────────────────────────────────

    private fun emitChar(ch: Char) {
        if (aboutToAutoWrap) {
            // We need to wrap: move to next line
            aboutToAutoWrap = false
            cursorCol = 0
            if (cursorRow + 1 >= bottomMargin) {
                scrollUp()
            } else {
                cursorRow++
            }
        }

        // Apply VT100 line-drawing charset translation if active
        val mapped = if (if (useG0) useLineDrawingG0 else useLineDrawingG1) {
            mapLineDrawing(ch)
        } else {
            ch
        }

        val cell = screen[cursorRow][cursorCol]
        cell.ch = mapped
        cell.fg = attrFg
        cell.bg = attrBg
        cell.bold = attrBold
        cell.reverse = attrReverse
        cell.underline = attrUnderline
        cell.italic = attrItalic

        if (cursorCol + 1 >= cols) {
            // At right margin — set auto-wrap flag (don't move yet)
            aboutToAutoWrap = true
        } else {
            cursorCol++
        }
    }

    // ── Line Feed & Scrolling ────────────────────────────────────────

    private fun doLinefeed() {
        aboutToAutoWrap = false
        val belowScrollRegion = cursorRow >= bottomMargin

        if (belowScrollRegion) {
            // Cursor is below scroll region — move down freely, stop at bottom of screen
            if (cursorRow < rows - 1) {
                cursorRow++
            }
        } else {
            val newRow = cursorRow + 1
            if (newRow >= bottomMargin) {
                // At bottom of scroll region — scroll up
                scrollUp()
                // cursor stays at bottomMargin - 1
            } else {
                cursorRow = newRow
            }
        }
    }

    private fun doReverseIndex() {
        aboutToAutoWrap = false
        if (cursorRow <= topMargin) {
            // At top of scroll region — scroll content down
            scrollDown()
        } else {
            cursorRow--
        }
    }

    /** Scroll the scroll region up by one line (content moves up, new blank line at bottom). */
    private fun scrollUp() {
        // Save top line to scrollback (only if main screen and full-width scroll region)
        if (!onAltScreen && topMargin == 0) {
            val saved = Array(cols) { Cell() }
            for (c in 0 until cols) saved[c].copyFrom(screen[topMargin][c])
            scrollback.add(saved)
            if (scrollback.size > maxScrollback) scrollback.removeAt(0)
        }

        // Shift lines up within scroll region
        for (r in topMargin until bottomMargin - 1) {
            for (c in 0 until cols) {
                screen[r][c].copyFrom(screen[r + 1][c])
            }
        }
        // Blank the last line of the scroll region
        for (c in 0 until cols) {
            blankCell(screen[bottomMargin - 1][c])
        }
    }

    /** Scroll the scroll region down by one line (content moves down, new blank line at top). */
    private fun scrollDown() {
        for (r in bottomMargin - 1 downTo topMargin + 1) {
            for (c in 0 until cols) {
                screen[r][c].copyFrom(screen[r - 1][c])
            }
        }
        for (c in 0 until cols) {
            blankCell(screen[topMargin][c])
        }
    }

    // ── Insert / Delete Lines ────────────────────────────────────────

    private fun doInsertLines(count: Int) {
        aboutToAutoWrap = false
        if (cursorRow < topMargin || cursorRow >= bottomMargin) return
        val linesAfter = bottomMargin - cursorRow
        val toInsert = count.coerceAtMost(linesAfter)
        val toMove = linesAfter - toInsert

        // Move lines down
        for (r in bottomMargin - 1 downTo cursorRow + toInsert) {
            val srcRow = r - toInsert
            if (srcRow >= cursorRow) {
                for (c in 0 until cols) {
                    screen[r][c].copyFrom(screen[srcRow][c])
                }
            }
        }
        // Blank inserted lines
        for (r in cursorRow until cursorRow + toInsert) {
            for (c in 0 until cols) {
                blankCell(screen[r][c])
            }
        }
    }

    private fun doDeleteLines(count: Int) {
        aboutToAutoWrap = false
        if (cursorRow < topMargin || cursorRow >= bottomMargin) return
        val linesAfter = bottomMargin - cursorRow
        val toDelete = count.coerceAtMost(linesAfter)
        val toMove = linesAfter - toDelete

        // Move lines up
        for (r in cursorRow until cursorRow + toMove) {
            val srcRow = r + toDelete
            for (c in 0 until cols) {
                screen[r][c].copyFrom(screen[srcRow][c])
            }
        }
        // Blank vacated lines at bottom
        for (r in cursorRow + toMove until bottomMargin) {
            for (c in 0 until cols) {
                blankCell(screen[r][c])
            }
        }
    }

    // ── Insert / Delete / Erase Characters ───────────────────────────

    private fun doInsertChars(count: Int) {
        aboutToAutoWrap = false
        val n = count.coerceAtMost(cols - cursorCol)
        // Shift chars right
        for (c in cols - 1 downTo cursorCol + n) {
            screen[cursorRow][c].copyFrom(screen[cursorRow][c - n])
        }
        // Blank inserted area
        for (c in cursorCol until (cursorCol + n).coerceAtMost(cols)) {
            blankCell(screen[cursorRow][c])
        }
    }

    private fun doDeleteChars(count: Int) {
        aboutToAutoWrap = false
        val n = count.coerceAtMost(cols - cursorCol)
        // Shift chars left
        for (c in cursorCol until cols - n) {
            screen[cursorRow][c].copyFrom(screen[cursorRow][c + n])
        }
        // Blank vacated area at end
        for (c in cols - n until cols) {
            blankCell(screen[cursorRow][c])
        }
    }

    private fun doEraseChars(count: Int) {
        aboutToAutoWrap = false
        val n = count.coerceAtMost(cols - cursorCol)
        for (c in cursorCol until cursorCol + n) {
            blankCell(screen[cursorRow][c])
        }
    }

    // ── Erase In Display / Line ──────────────────────────────────────

    private fun doEraseInDisplay(mode: Int) {
        aboutToAutoWrap = false
        when (mode) {
            0 -> {
                // Erase from cursor to end of screen
                // Rest of current line
                for (c in cursorCol until cols) {
                    blankCell(screen[cursorRow][c])
                }
                // All lines below
                for (r in cursorRow + 1 until rows) {
                    for (c in 0 until cols) {
                        blankCell(screen[r][c])
                    }
                }
            }
            1 -> {
                // Erase from start of screen to cursor
                // All lines above
                for (r in 0 until cursorRow) {
                    for (c in 0 until cols) {
                        blankCell(screen[r][c])
                    }
                }
                // Start of cursor line through cursor
                for (c in 0..cursorCol.coerceAtMost(cols - 1)) {
                    blankCell(screen[cursorRow][c])
                }
            }
            2 -> {
                // Erase entire display
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        blankCell(screen[r][c])
                    }
                }
            }
            3 -> {
                // Clear scrollback
                scrollback.clear()
            }
        }
    }

    private fun doEraseInLine(mode: Int) {
        aboutToAutoWrap = false
        when (mode) {
            0 -> {
                // Erase from cursor to end of line
                for (c in cursorCol until cols) {
                    blankCell(screen[cursorRow][c])
                }
            }
            1 -> {
                // Erase from start of line to cursor
                for (c in 0..cursorCol.coerceAtMost(cols - 1)) {
                    blankCell(screen[cursorRow][c])
                }
            }
            2 -> {
                // Erase entire line
                for (c in 0 until cols) {
                    blankCell(screen[cursorRow][c])
                }
            }
        }
    }

    // ── DEC Private Modes ────────────────────────────────────────────

    private fun doDecSet(params: List<Int>, enable: Boolean) {
        for (p in params) {
            when (p) {
                25 -> {
                    // DECTCEM — cursor visibility (we always show cursor, ignore)
                }
                47, 1047, 1049 -> {
                    // Alternate screen buffer
                    if (enable) {
                        // Switch to alt screen
                        if (!onAltScreen) {
                            saveCursor()
                            onAltScreen = true
                            screen = altScreen
                            clearScreenCells(altScreen)
                            topMargin = 0
                            bottomMargin = rows
                        }
                    } else {
                        // Switch back to main screen
                        if (onAltScreen) {
                            onAltScreen = false
                            screen = mainScreen
                            restoreCursor()
                            topMargin = 0
                            bottomMargin = rows
                        }
                    }
                }
                2004 -> {
                    // Bracketed paste mode — ignore
                }
                7 -> {
                    // Auto-wrap — we always auto-wrap, ignore
                }
                1 -> {
                    // DECCKM — cursor key mode
                    cursorKeysApplicationMode = enable
                }
                12 -> {
                    // Start Blinking Cursor — ignore
                }
                1000, 1002, 1003, 1006, 1015 -> {
                    // Mouse tracking modes — ignore
                }
            }
        }
    }

    // ── Cursor Save/Restore ──────────────────────────────────────────

    private fun saveCursor() {
        val state = if (onAltScreen) savedAlt else savedMain
        state.row = cursorRow
        state.col = cursorCol
        state.fg = attrFg
        state.bg = attrBg
        state.bold = attrBold
        state.reverse = attrReverse
        state.underline = attrUnderline
        state.italic = attrItalic
        state.useLineDrawingG0 = useLineDrawingG0
        state.useLineDrawingG1 = useLineDrawingG1
        state.useG0 = useG0
    }

    private fun restoreCursor() {
        val state = if (onAltScreen) savedAlt else savedMain
        cursorRow = state.row.coerceIn(0, rows - 1)
        cursorCol = state.col.coerceIn(0, cols - 1)
        attrFg = state.fg
        attrBg = state.bg
        attrBold = state.bold
        attrReverse = state.reverse
        attrUnderline = state.underline
        attrItalic = state.italic
        useLineDrawingG0 = state.useLineDrawingG0
        useLineDrawingG1 = state.useLineDrawingG1
        useG0 = state.useG0
        aboutToAutoWrap = false
    }

    // ── SGR (Select Graphic Rendition) ───────────────────────────────

    private fun applySgr(paramsStr: String) {
        val raw = if (paramsStr.isBlank()) "0" else paramsStr
        val parts = raw.split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < parts.size) {
            when (parts[i]) {
                0 -> {
                    attrFg = null; attrBg = null; attrBold = false; attrReverse = false
                    attrUnderline = false; attrItalic = false
                }
                1 -> attrBold = true
                2 -> { /* Dim/faint — ignore */ }
                3 -> attrItalic = true
                4 -> attrUnderline = true
                7 -> attrReverse = true
                22 -> { attrBold = false }
                23 -> attrItalic = false
                24 -> attrUnderline = false
                27 -> attrReverse = false
                in 30..37 -> attrFg = ansiColor(parts[i] - 30)
                38 -> {
                    // Extended foreground color
                    val result = parseExtendedColor(parts, i + 1)
                    if (result != null) {
                        attrFg = result.first
                        i = result.second
                        i++; continue
                    }
                }
                39 -> attrFg = null
                in 40..47 -> attrBg = ansiColor(parts[i] - 40)
                48 -> {
                    // Extended background color
                    val result = parseExtendedColor(parts, i + 1)
                    if (result != null) {
                        attrBg = result.first
                        i = result.second
                        i++; continue
                    }
                }
                49 -> attrBg = null
                in 90..97 -> attrFg = ansiColor((parts[i] - 90) + 8)
                in 100..107 -> attrBg = ansiColor((parts[i] - 100) + 8)
            }
            i++
        }
    }

    /** Parse 256-color (5;n) or true-color (2;r;g;b) from SGR params starting at index.
     *  Returns (color, lastConsumedIndex) or null if invalid. */
    private fun parseExtendedColor(parts: List<Int>, startIndex: Int): Pair<Color, Int>? {
        if (startIndex >= parts.size) return null
        return when (parts[startIndex]) {
            5 -> {
                // 256-color: 5;n
                if (startIndex + 1 >= parts.size) return null
                val n = parts[startIndex + 1]
                val color = color256(n)
                Pair(color, startIndex + 1)
            }
            2 -> {
                // True color: 2;r;g;b
                if (startIndex + 3 >= parts.size) return null
                val r = parts[startIndex + 1].coerceIn(0, 255)
                val g = parts[startIndex + 2].coerceIn(0, 255)
                val b = parts[startIndex + 3].coerceIn(0, 255)
                Pair(Color(r, g, b), startIndex + 3)
            }
            else -> null
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** VT100 DEC Special Graphics charset mapping (line-drawing characters). */
    private fun mapLineDrawing(ch: Char): Char {
        return when (ch) {
            '_' -> ' '      // Blank
            '`' -> '\u25C6' // ◆ Diamond
            '0' -> '\u2588' // █ Solid block
            'a' -> '\u2592' // ▒ Checker board
            'b' -> '\u2409' // ␉ HT symbol
            'c' -> '\u240C' // ␌ FF symbol
            'd' -> '\u240D' // ␍ CR symbol
            'e' -> '\u240A' // ␊ LF symbol
            'f' -> '\u00B0' // ° Degree
            'g' -> '\u00B1' // ± Plus-minus
            'h' -> '\u2424' // ␤ NL symbol
            'i' -> '\u240B' // ␋ VT symbol
            'j' -> '\u2518' // ┘ Lower-right corner
            'k' -> '\u2510' // ┐ Upper-right corner
            'l' -> '\u250C' // ┌ Upper-left corner
            'm' -> '\u2514' // └ Lower-left corner
            'n' -> '\u253C' // ┼ Crossing lines
            'o' -> '\u23BA' // ⎺ Scan line 1
            'p' -> '\u23BB' // ⎻ Scan line 3
            'q' -> '\u2500' // ─ Horizontal line
            'r' -> '\u23BC' // ⎼ Scan line 7
            's' -> '\u23BD' // ⎽ Scan line 9
            't' -> '\u251C' // ├ Left tee
            'u' -> '\u2524' // ┤ Right tee
            'v' -> '\u2534' // ┴ Bottom tee
            'w' -> '\u252C' // ┬ Top tee
            'x' -> '\u2502' // │ Vertical line
            'y' -> '\u2264' // ≤ Less-equal
            'z' -> '\u2265' // ≥ Greater-equal
            '{' -> '\u03C0' // π Pi
            '|' -> '\u2260' // ≠ Not equal
            '}' -> '\u00A3' // £ Pound
            '~' -> '\u00B7' // · Middle dot
            else -> ch
        }
    }

    /** Blank a cell using current rendition attributes (terminal behavior for erase operations). */
    private fun blankCell(cell: Cell) {
        cell.ch = ' '
        cell.fg = attrFg
        cell.bg = attrBg
        cell.bold = attrBold
        cell.reverse = attrReverse
        cell.underline = attrUnderline
        cell.italic = attrItalic
    }

    private fun sameStyle(a: Cell, b: Cell): Boolean {
        return a.fg == b.fg && a.bg == b.bg && a.bold == b.bold &&
                a.reverse == b.reverse && a.underline == b.underline && a.italic == b.italic
    }

    private fun resolveVisibleRows(scrollbackOffsetRows: Int, windowRows: Int): List<Array<Cell>> {
        val allRows = ArrayList<Array<Cell>>(scrollback.size + rows)
        allRows.addAll(scrollback)
        for (r in 0 until rows) {
            allRows.add(screen[r])
        }
        if (allRows.isEmpty()) return emptyList()

        val rowCount = windowRows.coerceAtLeast(1)
        val maxOffset = (allRows.size - rowCount).coerceAtLeast(0)
        val offset = scrollbackOffsetRows.coerceIn(0, maxOffset)
        val start = (allRows.size - rowCount - offset).coerceAtLeast(0)
        val end = (start + rowCount).coerceAtMost(allRows.size)
        return allRows.subList(start, end)
    }

    private fun cellAt(row: Array<Cell>, col: Int): Cell {
        if (col in row.indices) return row[col]
        return EMPTY_CELL
    }

    companion object {
        private val EMPTY_CELL = Cell()

        fun makeScreen(rows: Int, cols: Int): Array<Array<Cell>> {
            return Array(rows) { Array(cols) { Cell() } }
        }

        fun clearScreenCells(screen: Array<Array<Cell>>) {
            for (row in screen) {
                for (cell in row) {
                    cell.reset()
                }
            }
        }

        fun resizeScreen(
            old: Array<Array<Cell>>,
            oldRows: Int,
            oldCols: Int,
            newRows: Int,
            newCols: Int
        ): Array<Array<Cell>> {
            val newScreen = makeScreen(newRows, newCols)
            val copyRows = minOf(oldRows, newRows)
            val copyCols = minOf(oldCols, newCols)
            for (r in 0 until copyRows) {
                for (c in 0 until copyCols) {
                    newScreen[r][c].copyFrom(old[r][c])
                }
            }
            return newScreen
        }

        /** Standard 16-color ANSI palette. */
        fun ansiColor(index: Int): Color {
            return when (index) {
                0 -> Color(0xFF2E3436)   // Black
                1 -> Color(0xFFCC0000)   // Red
                2 -> Color(0xFF4E9A06)   // Green
                3 -> Color(0xFFC4A000)   // Yellow
                4 -> Color(0xFF3465A4)   // Blue
                5 -> Color(0xFF75507B)   // Magenta
                6 -> Color(0xFF06989A)   // Cyan
                7 -> Color(0xFFD3D7CF)   // White
                8 -> Color(0xFF555753)   // Bright Black
                9 -> Color(0xFFEF2929)   // Bright Red
                10 -> Color(0xFF8AE234)  // Bright Green
                11 -> Color(0xFFFCE94F)  // Bright Yellow
                12 -> Color(0xFF729FCF)  // Bright Blue
                13 -> Color(0xFFAD7FA8)  // Bright Magenta
                14 -> Color(0xFF34E2E2)  // Bright Cyan
                15 -> Color(0xFFEEEEEC)  // Bright White
                else -> Color.Unspecified
            }
        }

        /** 256-color palette: 0-15 = standard, 16-231 = 6x6x6 cube, 232-255 = grayscale. */
        fun color256(n: Int): Color {
            return when {
                n < 16 -> ansiColor(n)
                n < 232 -> {
                    // 6x6x6 color cube
                    val idx = n - 16
                    val b = idx % 6
                    val g = (idx / 6) % 6
                    val r = idx / 36
                    val ri = if (r == 0) 0 else 55 + r * 40
                    val gi = if (g == 0) 0 else 55 + g * 40
                    val bi = if (b == 0) 0 else 55 + b * 40
                    Color(ri, gi, bi)
                }
                n < 256 -> {
                    // Grayscale ramp
                    val v = 8 + (n - 232) * 10
                    Color(v, v, v)
                }
                else -> Color.Unspecified
            }
        }
    }
}
