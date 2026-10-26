package dev.minios.ocremote.data.api

import java.net.URLDecoder

data class ServerFileLink(val path: String, val line: Int? = null, val endLine: Int? = null)
internal data class ServerFileRequest(val directory: String, val path: String)

/** File links refer to the connected server, never Android's local filesystem. */
fun parseServerFileLink(href: String): ServerFileLink? {
    var value = href.trim().removeSurrounding("<", ">")
    if (value.isBlank() || value.startsWith('#') || value.startsWith('?')) return null
    val fileUrl = value.startsWith("file://", ignoreCase = true)
    val windowsDrive = Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(value)
    if (!fileUrl && !windowsDrive && Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value)) return null
    var first: Int? = null
    var last: Int? = null
    val suffix = Regex("(?:#L(\\d+)(?:-L?(\\d+))?|:(\\d+)(?::\\d+|-(\\d+))?)$").find(value)
    if (suffix != null) {
        first = (suffix.groups[1]?.value ?: suffix.groups[3]?.value)?.toIntOrNull()?.takeIf { it > 0 }
        last = (suffix.groups[2]?.value ?: suffix.groups[4]?.value)?.toIntOrNull()?.takeIf { first != null && it >= first }
        value = value.substring(0, suffix.range.first)
    }
    if (fileUrl) {
        value = value.substring(7).substringBefore('?').substringBefore('#')
        if (value.startsWith("localhost/", true)) value = value.substring(9)
        // A file URI with a remote authority is not a path on the currently connected server.
        if (!value.startsWith('/') && !Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(value)) return null
    }
    value = try { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { return null }
    if (Regex("^/[A-Za-z]:/").containsMatchIn(value)) value = value.drop(1)
    if (value.startsWith("//") || value.startsWith("\\\\") || value.startsWith('~') || value.any { it.code < 32 }) return null
    if (value.isBlank()) return null
    return ServerFileLink(value, first, last)
}

internal fun isWindowsServerPath(path: String) = Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(path)

/** Lexical server-side paths: java.nio on Android cannot resolve Windows paths correctly. */
internal fun resolveServerPath(path: String, directory: String): String {
    require(path.none { it.code < 32 } && directory.none { it.code < 32 }) { "Invalid server file path" }
    require(!path.startsWith("\\\\") && !path.startsWith("//")) { "Network-share paths are not supported" }
    val windows = isWindowsServerPath(path) || isWindowsServerPath(directory)
    val input = if (windows) path.replace('\\', '/') else path
    val base = if (windows) directory.replace('\\', '/') else directory
    val full = if (input.startsWith('/') || isWindowsServerPath(input)) input else base.trimEnd('/') + "/" + input
    val prefix = if (isWindowsServerPath(full)) full.take(3) else if (full.startsWith('/')) "/" else ""
    val segments = mutableListOf<String>()
    full.removePrefix(prefix).split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    return prefix + segments.joinToString("/")
}

internal fun serverFileRequest(path: String, directory: String): ServerFileRequest {
    val absolute = resolveServerPath(path, directory)
    val slash = absolute.lastIndexOf('/')
    require(slash >= 0 && slash < absolute.lastIndex) { "A file path is required" }
    val parent = when {
        slash == 0 -> "/"
        slash == 2 && isWindowsServerPath(absolute) -> absolute.take(3)
        else -> absolute.substring(0, slash)
    }
    return ServerFileRequest(parent, absolute.substring(slash + 1))
}
