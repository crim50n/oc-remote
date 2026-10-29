package dev.minios.ocremote.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class WorkspaceDiffMode { WORKING, BRANCH, COMMITTED }

@Serializable
data class WorkspaceVcsChange(
    @SerialName("file") val path: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String = "modified",
    val patch: String? = null,
)

internal fun workspaceDiffMode(mode: WorkspaceDiffMode, v2: Boolean): String = when (mode) {
    WorkspaceDiffMode.WORKING -> if (v2) "working" else "git"
    WorkspaceDiffMode.BRANCH -> "branch"
    WorkspaceDiffMode.COMMITTED -> {
        require(v2) { "Committed-only comparison requires the V2 VCS API" }
        "committed"
    }
}
