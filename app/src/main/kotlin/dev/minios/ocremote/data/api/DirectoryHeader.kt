package dev.minios.ocremote.data.api

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.encodeURLParameter

private const val DIRECTORY_HEADER = "x-opencode-directory"

internal fun directoryHeaderValue(directory: String): String =
    // Match the OpenCode SDK v2 while preserving compatibility for existing ASCII paths.
    if (directory.any { it.code > 0x7f }) {
        directory.encodeURLParameter(spaceToPlus = false)
    } else {
        directory
    }

internal fun HttpRequestBuilder.directoryHeader(directory: String?) {
    directory?.let { header(DIRECTORY_HEADER, directoryHeaderValue(it)) }
}
