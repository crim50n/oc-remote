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
import dev.minios.ocremote.data.api.parseServerFileLink
import dev.minios.ocremote.data.api.resolveServerPath
import dev.minios.ocremote.data.api.serverFileRequest
import dev.minios.ocremote.data.api.WorkspaceFileCapabilities
import dev.minios.ocremote.data.api.WorkspaceVcsChange
import dev.minios.ocremote.data.api.WorkspaceDiffMode
import dev.minios.ocremote.data.api.SearchMatch
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
    val line: Int? = null,
    val endLine: Int? = null,
    val requiresDownload: Boolean = false,
)

data class WorkspaceFilesUiState(
    val directory: String = "",
    val currentPath: String = "",
    val entries: List<FileNode> = emptyList(),
    val preview: WorkspaceFilePreview? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val searchQuery: String = "",
    val capabilities: WorkspaceFileCapabilities = WorkspaceFileCapabilities(),
    val transfer: WorkspaceTransfer? = null,
    val downloaded: DownloadedWorkspaceFile? = null,
    val searchContents: Boolean = false,
    val matches: List<SearchMatch> = emptyList(),
    val gitStatuses: List<WorkspaceVcsChange>? = null,
    val gitMode: WorkspaceDiffMode = WorkspaceDiffMode.WORKING,
    val gitDiffPath: String? = null,
    val gitDiff: WorkspaceVcsChange? = null,
    val gitDiffLoading: Boolean = false,
    val gitDiffError: String? = null,
    val isSaving: Boolean = false,
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

internal fun workspaceLineOffset(text: String, line: Int): Int {
    var remaining = (line - 1).coerceAtLeast(0)
    if (remaining == 0) return 0
    text.forEachIndexed { index, char ->
        if (char == '\n' && --remaining == 0) return index + 1
    }
    return text.length
}

internal fun workspaceParentPath(path: String): String {
    val normalized = if (dev.minios.ocremote.data.api.isWindowsServerPath(path)) path.replace('\\', '/') else path
    val trimmed = normalized.trimEnd('/')
    val slash = trimmed.lastIndexOf('/')
    return when {
        normalized == "/" -> "/"
        Regex("^[A-Za-z]:/?$").matches(trimmed) -> trimmed.take(2) + "/"
        slash == 0 -> "/"
        slash == 2 && trimmed.getOrNull(1) == ':' -> trimmed.take(3)
        else -> trimmed.substringBeforeLast('/', "")
    }
}

/** Return a project-relative directory, or null when navigation would leave the project. */
internal fun workspaceRelativeDirectory(path: String, project: String): String? {
    if (project.isBlank()) return "".takeIf { path.isBlank() || path == "." }
    return try {
        val root = resolveServerPath(".", project).trimEnd('/')
        val target = resolveServerPath(path.ifEmpty { "." }, project).trimEnd('/')
        val windows = dev.minios.ocremote.data.api.isWindowsServerPath(project)
        when {
            target.equals(root, ignoreCase = windows) -> ""
            target.startsWith("$root/", ignoreCase = windows) -> target.substring(root.length + 1)
            else -> null
        }
    } catch (_: IllegalArgumentException) { null }
}

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
    return workspaceMime(node.name, content.mimeType, content.type == "text")
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
    val openedFromChatLink = !savedStateHandle.get<String>("filePath").isNullOrBlank()
    private var initialLink = savedStateHandle.get<String>("filePath").orEmpty().takeIf { it.isNotBlank() }
    private var requestedFile: FileNode? = null
    private var requestedLine: Int? = null
    private var requestedEndLine: Int? = null
    private val fileBackStack = ArrayDeque<Triple<FileNode, Int?, Int?>>()
    private val routeServerId = savedStateHandle.get<String>("serverId").orEmpty()
    private val serverUrl = savedStateHandle.get<String>("serverUrl").orEmpty()
    @Volatile private var effectiveServerId = routeServerId
    private val _uiState = MutableStateFlow(WorkspaceFilesUiState(directory = directory))
    val uiState = _uiState.asStateFlow()
    private val _saveResults = MutableSharedFlow<WorkspaceFileEvent>(extraBufferCapacity = 1)
    val saveResults = _saveResults.asSharedFlow()
    val wordWrap = settingsRepository.codeWordWrap.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false,
    )
    private var loadJob: Job? = null
    private var transferJob: Job? = null
    private var pendingDownload: WorkspaceFilePreview? = null
    val gridView = settingsRepository.workspaceGrid.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun setGridView(enabled: Boolean) { viewModelScope.launch { settingsRepository.setWorkspaceGrid(enabled) } }
    private val thumbnailSlots = kotlinx.coroutines.sync.Semaphore(2)
    private val thumbnails = object : android.util.LruCache<String, android.graphics.Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.allocationByteCount
    }

    suspend fun thumbnail(node: FileNode): android.graphics.Bitmap? {
        if (workspaceFileKind(node) != WorkspaceFileKind.Image || node.size?.let { it > 1_048_576L } == true) return null
        val key = "${node.path}:${node.modified}"
        thumbnails.get(key)?.let { return it }
        if (effectiveServerId !in connectionStateRepository.connectedServerIds.value) return null
        thumbnailSlots.acquire()
        try {
            thumbnails.get(key)?.let { return it }
            return withContext(Dispatchers.IO) {
                val content = api.readFile(connection, node.path, directory, maxResponseBytes = 1_048_576)
                val bytes = workspaceFileBytes(content) ?: return@withContext null
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 384) sample *= 2
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.also { thumbnails.put(key, it) }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.d(TAG, "Thumbnail unavailable for file: ${e.javaClass.simpleName}"); return null }
        finally { thumbnailSlots.release() }
    }

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
                        try {
                            val capabilities = api.fileCapabilities(connection)
                            _uiState.update { it.copy(capabilities = capabilities) }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Log.e(TAG, "Failed to load filesystem capabilities", e) }
                        val link = initialLink
                        if (link != null) { initialLink = null; openLink(link) }
                        else if (requestedFile != null) open(requestedFile!!, requestedLine, requestedEndLine)
                        else loadDirectory(_uiState.value.currentPath)
                    } else {
                        loadJob?.cancel()
                        _uiState.update { it.copy(isLoading = false, error = null) }
                    }
                }
        }
    }

    fun loadDirectory(path: String) {
        if (effectiveServerId !in connectionStateRepository.connectedServerIds.value) return
        val relative = workspaceRelativeDirectory(path, directory) ?: return
        loadJob?.cancel()
        requestedFile = null
        fileBackStack.clear()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(currentPath = relative, preview = null, isLoading = true, error = null, searchQuery = "", matches = emptyList(), gitStatuses = null)
            }
            try {
                val entries = api.listDirectory(connection, path = relative, directory = directory)
                _uiState.update { it.copy(entries = entries, isLoading = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to list workspace directory", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun searchFiles(query: String) {
        if (effectiveServerId !in connectionStateRepository.connectedServerIds.value) return
        if (query.isBlank()) { loadDirectory(_uiState.value.currentPath); return }
        loadJob?.cancel()
        requestedFile = null
        _uiState.update { it.copy(searchQuery = query, preview = null, isLoading = true, error = null, gitStatuses = null) }
        loadJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.delay(250)
                if (_uiState.value.searchContents && _uiState.value.capabilities.textSearch) {
                    val matches = api.searchText(connection, query, directory)
                    _uiState.update { it.copy(matches = matches, entries = emptyList(), isLoading = false) }
                    return@launch
                }
                val paths = api.findFiles(connection, query, type = "file", directory = directory, limit = 100)
                _uiState.update { it.copy(entries = paths.distinct().map { path ->
                    FileNode(path.replace('\\', '/').substringAfterLast('/'), path, "file")
                }, isLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "Failed to search workspace files", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun browseParent() {
        val file = _uiState.value.preview?.node
        if (file != null) {
            val parent = serverFileRequest(file.path, directory).directory
            fileBackStack.clear()
            loadDirectory(workspaceRelativeDirectory(parent, directory) ?: "")
            return
        }
        val current = _uiState.value.currentPath
        if (current.isNotEmpty()) loadDirectory(workspaceParentPath(current))
    }

    fun open(node: FileNode, line: Int? = null, endLine: Int? = null) {
        if (effectiveServerId !in connectionStateRepository.connectedServerIds.value) return
        if (node.type == "directory") {
            loadDirectory(node.path)
            return
        }
        loadJob?.cancel()
        requestedFile = node
        requestedLine = line
        requestedEndLine = endLine
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val content = if (node.name.endsWith(".apk", ignoreCase = true)) {
                    FileContent("binary", "", mimeType = workspaceMime(node.name))
                } else api.readFile(connection, node.path, directory)
                _uiState.update {
                    it.copy(preview = WorkspaceFilePreview(node, content, line, endLine,
                        requiresDownload = node.name.endsWith(".apk", ignoreCase = true)), isLoading = false)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is dev.minios.ocremote.data.api.V2ResponseLimitException) {
                    _uiState.update { it.copy(preview = WorkspaceFilePreview(node, FileContent("binary", ""), line, endLine,
                        requiresDownload = true), isLoading = false) }
                    return@launch
                }
                Log.e(TAG, "Failed to read workspace file", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun openLink(href: String) {
        val link = parseServerFileLink(href) ?: return
        val preview = _uiState.value.preview
        val base = preview?.node?.path?.let { serverFileRequest(it, directory).directory } ?: directory
        val path = resolveServerPath(link.path, base)
        if (preview != null) {
            fileBackStack.addLast(Triple(preview.node, preview.line, preview.endLine))
            if (fileBackStack.size > 20) fileBackStack.removeFirst()
        }
        val filename = path.substringAfterLast('/')
        open(FileNode(filename, path, "file"), link.line, link.endLine)
    }

    fun navigateUp(): Boolean {
        val state = _uiState.value
        if (state.gitStatuses != null && state.preview == null) {
            loadJob?.cancel()
            if (state.gitDiffPath != null) {
                _uiState.update { it.copy(gitDiffPath = null, gitDiff = null, gitDiffError = null, gitDiffLoading = false) }
            } else if (effectiveServerId in connectionStateRepository.connectedServerIds.value) {
                loadDirectory(state.currentPath)
            } else {
                _uiState.update { it.copy(gitStatuses = null, error = null, isLoading = false) }
            }
            return true
        }
        if (state.preview != null) {
            if (fileBackStack.isNotEmpty()) {
                val previous = fileBackStack.removeLast()
                open(previous.first, previous.second, previous.third)
                return true
            }
            val parent = requestedFile?.path?.let { serverFileRequest(it, directory).directory }
            if (parent != null) { loadDirectory(workspaceRelativeDirectory(parent, directory) ?: ""); return true }
            _uiState.update { it.copy(preview = null, error = null) }
            return true
        }
        if (state.currentPath.isNotEmpty()) {
            val parent = workspaceParentPath(state.currentPath)
            if (parent == state.currentPath) return false
            loadDirectory(parent)
            return true
        }
        return false
    }

    fun retry() {
        if (effectiveServerId !in connectionStateRepository.connectedServerIds.value) return
        val file = requestedFile
        if (file != null) open(file, requestedLine, requestedEndLine)
        else if (_uiState.value.gitStatuses != null) {
            val path = _uiState.value.gitDiffPath
            if (path != null) { gitDiffCache = null; openGitDiff(path) }
            else loadGitStatus(_uiState.value.gitMode)
        }
        else if (_uiState.value.searchQuery.isNotBlank()) searchFiles(_uiState.value.searchQuery)
        else loadDirectory(_uiState.value.currentPath)
    }

    fun setWordWrap(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCodeWordWrap(enabled) }
    }

    fun savePreview(uri: Uri) {
        val preview = pendingDownload ?: _uiState.value.preview ?: return
        pendingDownload = null
        transfer(preview, uri, null)
    }

    fun prepareDownload(): WorkspaceDownloadDestination? {
        val preview = _uiState.value.preview ?: return null
        if (_uiState.value.transfer != null) return null
        pendingDownload = preview
        return WorkspaceDownloadDestination(preview.node.name, workspaceFileMimeType(preview.node, preview.content))
    }

    fun cancelTransfer() { transferJob?.cancel() }

    fun prepareUpload(uri: Uri) {
        if (!_uiState.value.capabilities.write) return
        viewModelScope.launch {
            try {
            val name = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }.orEmpty().substringAfterLast('/').substringAfterLast('\\').ifBlank { "upload" }
            }
            _saveResults.emit(WorkspaceFileEvent.UploadDestination(uri.toString(),
                resolveServerPath(name, resolveServerPath(_uiState.value.currentPath.ifEmpty { "." }, directory))))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e(TAG, "Failed to read upload source", e); _saveResults.emit(WorkspaceFileEvent.Error(e.message)) }
        }
    }

    fun upload(source: Uri, path: String) {
        if (!_uiState.value.capabilities.write || transferJob?.isActive == true || path.isBlank()) return
        _uiState.update { it.copy(transfer = WorkspaceTransfer(path.substringAfterLast('/'))) }
        transferJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(source)?.use { input ->
                        api.uploadWorkspaceFile(connection, path, directory, input) { bytes ->
                            _uiState.update { it.copy(transfer = WorkspaceTransfer(path.substringAfterLast('/'), bytes)) }
                        }
                    } ?: error("Unable to read selected file")
                }
                _saveResults.emit(WorkspaceFileEvent.Uploaded)
                loadDirectory(_uiState.value.currentPath)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e(TAG, "File upload failed", e); _saveResults.emit(WorkspaceFileEvent.Error(e.message)) }
            finally { _uiState.update { it.copy(transfer = null) } }
        }
    }

    fun openExternally(share: Boolean = false) {
        val preview = _uiState.value.preview ?: return
        val downloaded = _uiState.value.downloaded?.takeIf { it.path == preview.node.path }
        if (downloaded != null) { viewModelScope.launch { _saveResults.emit(WorkspaceFileEvent.Open(downloaded, share)) }; return }
        transfer(preview, null, share)
    }

    private fun transfer(preview: WorkspaceFilePreview, destination: Uri?, share: Boolean?) {
        if (transferJob?.isActive == true) return
        _uiState.update { it.copy(transfer = WorkspaceTransfer(preview.node.name)) }
        transferJob = viewModelScope.launch {
            var cache: java.io.File? = null
            var completed = false
            try {
                val file = withContext(Dispatchers.IO) {
                    val uri = destination ?: run {
                        val root = java.io.File(context.cacheDir, "workspace").apply { mkdirs() }
                        root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.deleteRecursively() }
                        val folder = java.io.File(root, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
                        cache = java.io.File(folder, preview.node.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120).ifBlank { "download" })
                        androidx.core.content.FileProvider.getUriForFile(context, "${dev.minios.ocremote.BuildConfig.APPLICATION_ID}.fileprovider", cache!!)
                    }
                    var mime: String? = preview.content.mimeType
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        if (_uiState.value.capabilities.rawDownload || preview.requiresDownload) {
                            mime = api.downloadFile(connection, preview.node.path, directory, output,
                                base64Encoded = preview.content.type == "binary" || preview.content.encoding == "base64") { bytes, total ->
                                _uiState.update { it.copy(transfer = WorkspaceTransfer(preview.node.name, bytes, total)) }
                            }
                        } else {
                            val bytes = workspaceFileBytes(preview.content) ?: error("This server does not provide binary file contents")
                            output.write(bytes)
                        }
                    } ?: error("Unable to open destination")
                    DownloadedWorkspaceFile(uri.toString(), workspaceMime(preview.node.name, mime, preview.content.type == "text"), preview.node.name, preview.node.path)
                }
                _uiState.update { it.copy(downloaded = file) }
                completed = true
                _saveResults.emit(if (share == null) WorkspaceFileEvent.Saved(file) else WorkspaceFileEvent.Open(file, share))
            } catch (e: CancellationException) {
                cache?.delete()
                throw e
            } catch (e: Exception) {
                cache?.delete()
                Log.e(TAG, "File transfer failed", e)
                _saveResults.emit(WorkspaceFileEvent.Error(e.message))
            } finally {
                if (!completed && destination != null) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    try { android.provider.DocumentsContract.deleteDocument(context.contentResolver, destination) }
                    catch (e: Exception) { Log.e(TAG, "Could not remove incomplete download", e) }
                }
                _uiState.update { it.copy(transfer = null) }
            }
        }
    }

    fun setContentSearch(enabled: Boolean) {
        _uiState.update { it.copy(searchContents = enabled, matches = emptyList()) }
        searchFiles(_uiState.value.searchQuery)
    }

    private var gitDiffCache: List<WorkspaceVcsChange>? = null

    fun loadGitStatus(mode: WorkspaceDiffMode = WorkspaceDiffMode.WORKING) {
        if (!_uiState.value.capabilities.gitStatus || effectiveServerId !in connectionStateRepository.connectedServerIds.value) return
        loadJob?.cancel()
        gitDiffCache = null
        requestedFile = null
        fileBackStack.clear()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, preview = null, error = null, searchQuery = "", matches = emptyList(),
                gitStatuses = emptyList(), gitMode = mode, gitDiffPath = null, gitDiff = null, gitDiffError = null, gitDiffLoading = false) }
            try {
                val statuses = if (mode == WorkspaceDiffMode.WORKING) api.workspaceVcsStatus(connection, directory)
                    else api.workspaceVcsDiff(connection, directory, mode).also { gitDiffCache = it }
                _uiState.update { it.copy(gitStatuses = statuses, isLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e(TAG, "Failed to load file status", e); _uiState.update { it.copy(error = e.message, isLoading = false) } }
        }
    }

    fun openGitDiff(path: String) {
        if (effectiveServerId !in connectionStateRepository.connectedServerIds.value) return
        loadJob?.cancel()
        val mode = _uiState.value.gitMode
        _uiState.update { it.copy(gitDiffPath = path, gitDiff = null, gitDiffLoading = true, gitDiffError = null) }
        loadJob = viewModelScope.launch {
            try {
                val changes = gitDiffCache ?: api.workspaceVcsDiff(connection, directory, mode).also { gitDiffCache = it }
                _uiState.update { it.copy(gitDiff = changes.firstOrNull { change -> change.path == path }, gitDiffLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "Failed to load project diff", e)
                _uiState.update { it.copy(gitDiffError = e.message, gitDiffLoading = false) }
            }
        }
    }

    fun saveText(text: String) {
        val preview = _uiState.value.preview ?: return
        if (!_uiState.value.capabilities.write || _uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                api.writeWorkspaceFile(connection, preview.node.path, directory, text.toByteArray(Charsets.UTF_8))
                _uiState.update { it.copy(preview = preview.copy(content = preview.content.copy(content = text, encoding = null)), downloaded = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e(TAG, "Failed to write server file", e); _saveResults.emit(WorkspaceFileEvent.Error(e.message)) }
            finally { _uiState.update { it.copy(isSaving = false) } }
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
