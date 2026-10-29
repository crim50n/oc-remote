package dev.minios.ocremote.data.repository

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface WorkspaceTab {
    val key: String
    val title: String
    val serverId: String?
    val sessionId: String?
    val pinned: Boolean
    val lastActive: Long
    val hasDraft: Boolean
}

enum class WorkspacePage(val serverScoped: Boolean = false) {
    HOME, FAVORITES, SETTINGS, SYNC, DIAGNOSTICS, ABOUT, UPDATES,
    SESSIONS(true), SERVER_SETTINGS(true), PROVIDERS(true), MODELS(true), MCP(true), FILES(true),
}

@Serializable
@SerialName("page")
data class PageTab(
    val page: WorkspacePage,
    override val title: String,
    override val serverId: String? = null,
    val directory: String = "",
    val filePath: String? = null,
    override val pinned: Boolean = false,
    override val lastActive: Long = 0,
) : WorkspaceTab {
    init {
        require(if (page.serverScoped) !serverId.isNullOrBlank() else serverId == null)
        require(page == WorkspacePage.FILES || (directory.isEmpty() && filePath.isNullOrBlank()))
    }
    override val sessionId: String? get() = null
    override val hasDraft: Boolean get() = false
    override val key: String get() = "page|" + listOf(page.name, serverId.orEmpty(), directory, filePath.orEmpty())
        .joinToString("|") { "${it.length}:$it" }
}

internal fun WorkspaceTab.copyMetadata(
    title: String = this.title, pinned: Boolean = this.pinned,
    lastActive: Long = this.lastActive, hasDraft: Boolean = this.hasDraft,
): WorkspaceTab = when (this) {
    is SessionTab -> copy(title = title, pinned = pinned, lastActive = lastActive, hasDraft = hasDraft)
    is PageTab -> copy(title = title, pinned = pinned, lastActive = lastActive)
}
