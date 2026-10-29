package dev.minios.ocremote.ui.navigation

internal data class DesktopTabWidths(val pinned: Float, val normal: Float)

/** Widths in dp. Preserve a usable minimum; only then allow the strip to scroll. */
internal fun desktopTabWidths(available: Float, pinnedCount: Int, normalCount: Int): DesktopTabWidths {
    val count = pinnedCount + normalCount
    val spacing = (count - 1).coerceAtLeast(0) * 4f
    val usable = (available - 48f - 16f - spacing).coerceAtLeast(0f)
    val minimum = pinnedCount * 48f + normalCount * 96f
    val maximum = pinnedCount * 160f + normalCount * 220f
    val fraction = if (maximum > minimum) ((usable - minimum) / (maximum - minimum)).coerceIn(0f, 1f) else 1f
    return DesktopTabWidths(48f + 112f * fraction, 96f + 124f * fraction)
}
