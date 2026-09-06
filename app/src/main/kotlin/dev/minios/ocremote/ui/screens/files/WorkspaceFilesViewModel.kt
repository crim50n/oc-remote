package dev.minios.ocremote.ui.screens.files

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.minios.ocremote.data.api.FileContent
import dev.minios.ocremote.data.api.FileNode
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.data.repository.ServerConnectionStateRepository
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.data.repository.normalizeServerUrl
import dev.minios.ocremote.logging.AppLogger as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLConnection
import java.util.Base64
import javax.inject.Inject

data class WorkspaceFilePreview(
    val node: FileNode,
    val content: FileContent,
)

data class WorkspaceFilesUiState(
    val directory: String = "",
    val currentPath: String = "",
    val entries: List<FileNode> = emptyList(),
    val preview: WorkspaceFilePreview? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

internal enum class WorkspaceFileKind {
    Directory,
    Code,
    Config,
    Image,
    Document,
    Pdf,
    Archive,
    Audio,
    Video,
    Table,
    Generic,
}

internal fun workspaceFileKind(node: FileNode): WorkspaceFileKind {
    if (node.type == "directory") return WorkspaceFileKind.Directory
    val name = node.name.lowercase()
    val extension = name.substringAfterLast('.', "")
    return when {
        extension in CODE_EXTENSIONS -> WorkspaceFileKind.Code
        extension in CONFIG_EXTENSIONS || name in CONFIG_FILENAMES -> WorkspaceFileKind.Config
        extension in IMAGE_EXTENSIONS -> WorkspaceFileKind.Image
        extension == "pdf" -> WorkspaceFileKind.Pdf
        extension in ARCHIVE_EXTENSIONS -> WorkspaceFileKind.Archive
        extension in AUDIO_EXTENSIONS -> WorkspaceFileKind.Audio
        extension in VIDEO_EXTENSIONS -> WorkspaceFileKind.Video
        extension in TABLE_EXTENSIONS -> WorkspaceFileKind.Table
        extension in DOCUMENT_EXTENSIONS -> WorkspaceFileKind.Document
        else -> WorkspaceFileKind.Generic
    }
}

internal fun workspaceSyntaxLanguage(filename: String): String? {
    return when (filename.lowercase().substringAfterLast('.', "")) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "js", "jsx", "mjs", "cjs" -> "javascript"
        "ts", "tsx" -> "typescript"
        "py" -> "python"
        "rb" -> "ruby"
        "go" -> "go"
        "rs" -> "rust"
        "c", "h" -> "c"
        "cpp", "cc", "cxx", "hpp" -> "cpp"
        "cs" -> "csharp"
        "swift" -> "swift"
        "sh", "bash", "zsh", "fish" -> "bash"
        "json", "jsonl" -> "json"
        "xml", "svg" -> "xml"
        "html", "htm" -> "html"
        "css", "scss", "sass", "less" -> "css"
        "yaml", "yml" -> "yaml"
        "sql" -> "sql"
        "md", "markdown" -> "markdown"
        else -> null
    }
}

internal fun isWorkspaceMarkdownFile(filename: String): Boolean =
    filename.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")

internal fun workspaceParentPath(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")

internal fun workspaceFileBytes(content: FileContent): ByteArray? {
    if (content.type == "binary" && content.content.isEmpty()) return null
    return runCatching {
        if (content.encoding == "base64") {
            Base64.getMimeDecoder().decode(content.content)
        } else {
            content.content.toByteArray(Charsets.UTF_8)
        }
    }.getOrNull()
}

internal fun workspaceFileMimeType(node: FileNode, content: FileContent): String {
    return content.mimeType
        ?: URLConnection.guessContentTypeFromName(node.name)
        ?: if (content.type == "text") "text/plain" else "application/octet-stream"
}

@HiltViewModel
class WorkspaceFilesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: OpenCodeApi,
    private val settingsRepository: SettingsRepository,
    private val connectionStateRepository: ServerConnectionStateRepository,
    private val serverRepository: ServerRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val connection = ServerConnection.from(
        url = savedStateHandle.get<String>("serverUrl").orEmpty(),
        username = savedStateHandle.get<String>("username").orEmpty().ifBlank { "opencode" },
        password = savedStateHandle.get<String>("password").orEmpty().ifEmpty { null },
    )
    private val directory = savedStateHandle.get<String>("directory").orEmpty()
    private val routeServerId = savedStateHandle.get<String>("serverId").orEmpty()
    private val serverUrl = savedStateHandle.get<String>("serverUrl").orEmpty()
    @Volatile private var effectiveServerId = routeServerId
    private val _uiState = MutableStateFlow(WorkspaceFilesUiState(directory = directory))
    val uiState = _uiState.asStateFlow()
    private val _saveResults = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val saveResults = _saveResults.asSharedFlow()
    val wordWrap = settingsRepository.codeWordWrap.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false,
    )
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            combine(serverRepository.servers, connectionStateRepository.connectedServerIds) { servers, connectedIds ->
                effectiveServerId = servers.firstOrNull {
                    it.id == routeServerId || normalizeServerUrl(it.url) == normalizeServerUrl(serverUrl)
                }?.id ?: routeServerId
                effectiveServerId in connectedIds
            }
                .distinctUntilChanged()
                .collect { connected ->
                    if (connected) {
                        loadDirectory(_uiState.value.currentPath)
                    } else {
                        loadJob?.cancel()
                        _uiState.update { it.copy(isLoading = false, error = null) }
                    }
                }
        }
    }

    fun loadDirectory(path: String) {
        if (effectiveServerId !in connectionStateRepository.connectedServerIds.value) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(currentPath = path, preview = null, isLoading = true, error = null)
            }
            try {
                val entries = api.listDirectory(connection, path = path, directory = directory)
                _uiState.update { it.copy(entries = entries, isLoading = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to list workspace directory", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun open(node: FileNode) {
        if (effectiveServerId !in connectionStateRepository.connectedServerIds.value) return
        if (node.type == "directory") {
            loadDirectory(node.path)
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val content = api.readFile(connection, node.path, directory)
                _uiState.update {
                    it.copy(preview = WorkspaceFilePreview(node, content), isLoading = false)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to read workspace file", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun navigateUp(): Boolean {
        val state = _uiState.value
        if (state.preview != null) {
            _uiState.update { it.copy(preview = null, error = null) }
            return true
        }
        if (state.currentPath.isNotEmpty()) {
            loadDirectory(workspaceParentPath(state.currentPath))
            return true
        }
        return false
    }

    fun retry() {
        if (effectiveServerId !in connectionStateRepository.connectedServerIds.value) return
        val preview = _uiState.value.preview
        if (preview != null) open(preview.node) else loadDirectory(_uiState.value.currentPath)
    }

    fun setWordWrap(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCodeWordWrap(enabled) }
    }

    fun savePreview(uri: Uri) {
        val preview = _uiState.value.preview ?: return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val bytes = workspaceFileBytes(preview.content) ?: return@withContext false
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("Unable to open destination")
                }.onFailure { Log.e(TAG, "Failed to save workspace file", it) }.isSuccess
            }
            _saveResults.emit(saved)
        }
    }

    private companion object {
        const val TAG = "WorkspaceFilesVM"
    }
}

private val CODE_EXTENSIONS = setOf(
    "kt", "kts", "java", "js", "jsx", "mjs", "cjs", "ts", "tsx", "py", "rb", "go", "rs",
    "c", "h", "cpp", "cc", "cxx", "hpp", "cs", "swift", "sh", "bash", "zsh", "fish", "sql",
    "html", "htm", "css", "scss", "sass", "less", "vue", "svelte", "dart", "lua", "php",
)
private val CONFIG_EXTENSIONS = setOf(
    "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "conf", "config", "properties", "gradle",
)
private val CONFIG_FILENAMES = setOf(
    ".env", ".gitignore", ".gitattributes", ".editorconfig", "dockerfile", "makefile",
)
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "avif")
private val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "apk")
private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac")
private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "avi", "m4v")
private val TABLE_EXTENSIONS = setOf("csv", "tsv", "xls", "xlsx")
private val DOCUMENT_EXTENSIONS = setOf("txt", "md", "markdown", "rtf", "doc", "docx", "odt", "log")
