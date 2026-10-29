package dev.minios.ocremote.ui.screens.chat

import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow

internal class TerminalDesktopInputState {
    var focused by mutableStateOf(false)
    var localMouse by mutableStateOf(true)
    var menu by mutableStateOf(false)
    var menuPosition by mutableStateOf(Offset.Zero)
    var start by mutableStateOf<TerminalCellPoint?>(null)
    var end by mutableStateOf<TerminalCellPoint?>(null)
    var lines: List<String> = emptyList()
    var pageHeight = 0f
    private var deadAccent = 0

    fun selectedText(): String = terminalSelectedText(lines, start, end)
    fun selectAll(value: List<String>) {
        lines = value
        start = TerminalCellPoint(0, 0)
        end = TerminalCellPoint(value.lastIndex.coerceAtLeast(0), value.lastOrNull()?.length ?: 0)
    }

    fun key(
        event: KeyEvent, connected: Boolean, applicationCursor: Boolean,
        onSend: (String) -> Unit, onCopy: () -> Unit, onPaste: () -> Unit,
        onZoom: (Float) -> Unit, onResetZoom: () -> Unit, onScroll: (Float) -> Unit,
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.isMetaPressed) return false
        if (event.isCtrlPressed && event.isShiftPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_C -> { onCopy(); return true }
                KeyEvent.KEYCODE_V -> { if (connected) onPaste(); return true }
            }
        }
        if (event.isCtrlPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> { onZoom(1.1f); return true }
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { onZoom(1f / 1.1f); return true }
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> { onResetZoom(); return true }
            }
        }
        if (event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_PAGE_UP -> { onScroll(-pageHeight); return true }
                KeyEvent.KEYCODE_PAGE_DOWN -> { onScroll(pageHeight); return true }
            }
        }
        if (!connected) return false
        var unicode = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
        if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
            deadAccent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        if (deadAccent != 0 && unicode > 0 && !event.isCtrlPressed) {
            val combined = KeyCharacterMap.getDeadChar(deadAccent, unicode)
            if (combined > 0) unicode = combined else onSend(String(Character.toChars(deadAccent)))
        }
        deadAccent = 0
        val altGr = event.isCtrlPressed && event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0
        val sequence = terminalHardwareSequence(event.keyCode, unicode, event.isCtrlPressed && !altGr,
            event.isAltPressed && !altGr, event.isShiftPressed, applicationCursor) ?: return false
        start = null; end = null
        onSend(sequence)
        return true
    }
}

internal data class TerminalMouseGrid(val charWidth: Float, val rowHeight: Int, val columns: Int,
    val rows: Int, val firstVisibleRow: Int, val totalRows: Int)

@Composable
internal fun terminalMouseInput(
    input: TerminalDesktopInputState, emulator: TerminalEmulator, grid: TerminalMouseGrid, connected: Boolean,
    onFocus: () -> Unit, onSend: (String) -> Unit, onScroll: (Float) -> Unit, onZoom: (Float) -> Unit,
): Modifier {
    val currentGrid by rememberUpdatedState(grid)
    val currentConnected by rememberUpdatedState(connected)
    val focus by rememberUpdatedState(onFocus)
    val send by rememberUpdatedState(onSend)
    val scroll by rememberUpdatedState(onScroll)
    val zoom by rememberUpdatedState(onZoom)
    return Modifier.pointerHoverIcon(if (input.localMouse) PointerIcon.Text else PointerIcon.Default,
        overrideDescendants = true).pointerInput(input, emulator) {
        var button: Int? = null
        var reporting = false
        var lastColumn = 1
        var lastRow = 1
        var lastModifiers = 0
        var lastEncoding = TerminalMouseEncoding.LEGACY
        try {
        awaitPointerEventScope {
            var lastReported: TerminalCellPoint? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
                val g = currentGrid
                val column = (change.position.x / g.charWidth.coerceAtLeast(1f)).toInt().coerceIn(0, g.columns - 1)
                val row = (change.position.y / g.rowHeight.coerceAtLeast(1)).toInt().coerceIn(0, g.rows - 1)
                val cell = TerminalCellPoint(row + g.firstVisibleRow, column)
                val modifiers = (if (event.keyboardModifiers.isAltPressed) 8 else 0) + (if (event.keyboardModifiers.isCtrlPressed) 16 else 0)
                val appMouse = currentConnected && emulator.mouseTracking != TerminalMouseTracking.NONE && !event.keyboardModifiers.isShiftPressed
                input.localMouse = !appMouse
                lastColumn = column + 1; lastRow = row + 1; lastModifiers = modifiers; lastEncoding = emulator.mouseEncoding
                fun report(code: Int, release: Boolean = false) {
                    terminalMouseSequence(emulator.mouseEncoding, code + modifiers, column + 1, row + 1, release)?.let(send)
                }
                when (event.type) {
                    PointerEventType.Press -> {
                        focus()
                        input.menu = false
                        button = when {
                            event.buttons.isPrimaryPressed -> 0
                            event.buttons.isTertiaryPressed -> 1
                            event.buttons.isSecondaryPressed -> 2
                            else -> null
                        }
                        reporting = appMouse
                        if (reporting) { input.start = null; input.end = null; button?.let { report(it) } }
                        else if (button == 0) {
                            input.lines = emulator.renderSelectionText(0, g.totalRows).lines()
                            input.start = cell
                            input.end = cell
                        } else if (button == 2) {
                            input.menuPosition = change.position
                            input.menu = true
                        }
                        event.changes.forEach { it.consume() }
                    }
                    PointerEventType.Move -> {
                        val motion = (reporting && emulator.mouseTracking == TerminalMouseTracking.DRAG && button != null) ||
                            (appMouse && emulator.mouseTracking == TerminalMouseTracking.ANY)
                        if (motion && cell != lastReported) { report(32 + (button ?: 3)); lastReported = cell }
                        else if (!reporting && button == 0) input.end = cell
                        if (button != null || motion) event.changes.forEach { it.consume() }
                    }
                    PointerEventType.Release -> {
                        if (reporting && currentConnected) button?.let { report(it, release = true) }
                        button = null
                        reporting = false
                        lastReported = null
                        event.changes.forEach { it.consume() }
                    }
                    PointerEventType.Scroll -> {
                        val delta = change.scrollDelta.y
                        if (event.keyboardModifiers.isCtrlPressed) zoom(1.1f.pow(-delta.coerceIn(-5f, 5f)))
                        else if (appMouse) repeat(ceil(abs(delta)).toInt().coerceIn(0, 8)) { report(if (delta < 0) 64 else 65) }
                        else scroll(delta * g.rowHeight * 3f)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
        } finally {
            // Release a captured button if the view is hidden/resized away mid-gesture.
            if (reporting && currentConnected) button?.let {
                terminalMouseSequence(lastEncoding, it + lastModifiers, lastColumn, lastRow, release = true)?.let(send)
            }
        }
    }
}
