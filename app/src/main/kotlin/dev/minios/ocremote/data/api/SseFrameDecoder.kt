package dev.minios.ocremote.data.api

internal const val DEFAULT_MAX_SSE_FRAME_SIZE = 1_048_576

internal class SseFrameDecoder(
    private val maxFrameSize: Int = DEFAULT_MAX_SSE_FRAME_SIZE,
) {
    private val dataLines = mutableListOf<String>()
    private var size = 0

    fun accept(line: String): String? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(':')) return null

        val separator = line.indexOf(':')
        val field = if (separator >= 0) line.substring(0, separator) else line
        var value = if (separator >= 0) line.substring(separator + 1) else ""
        if (value.startsWith(' ')) value = value.substring(1)

        if (field == "data") {
            val addedSize = value.length + if (dataLines.isEmpty()) 0 else 1
            if (size + addedSize > maxFrameSize) {
                clear()
                throw SseFrameTooLargeException(maxFrameSize)
            }
            dataLines += value
            size += addedSize
        }
        return null
    }

    fun finish(): String? = dispatch()

    private fun dispatch(): String? {
        if (dataLines.isEmpty()) return null
        val data = dataLines.joinToString("\n")
        clear()
        return data
    }

    private fun clear() {
        dataLines.clear()
        size = 0
    }
}

class SseFrameTooLargeException(maxFrameSize: Int) :
    Exception("SSE frame exceeds $maxFrameSize characters")
