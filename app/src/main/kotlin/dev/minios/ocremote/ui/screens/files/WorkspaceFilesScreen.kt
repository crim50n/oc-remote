package dev.minios.ocremote.ui.screens.files

import com.composables.icons.lucide.*
import dev.minios.ocremote.ui.components.backIcon
import dev.minios.ocremote.ui.components.mirrorForRtl

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.heightIn
import dev.minios.ocremote.ui.components.AppDropdownMenu
import dev.minios.ocremote.ui.components.AppDropdownMenuItem
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.data.api.FileNode
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import dev.minios.ocremote.data.api.parseServerFileLink
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.AppLoadingEdge
import dev.minios.ocremote.ui.components.ServerConnectionBanner
import dev.minios.ocremote.ui.screens.chat.LocalCodeWordWrap
import dev.minios.ocremote.ui.screens.chat.buildSafeHighlightedAnnotatedString
import dev.minios.ocremote.ui.screens.chat.horizontallyScrollableMarkdownTable
import dev.minios.ocremote.ui.screens.chat.safeHighlightedCodeBlock
import dev.minios.ocremote.ui.screens.chat.safeHighlightedCodeFence
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceFilesScreen(
    onNavigateBack: () -> Unit,
    isServerConnected: Boolean = true,
    isServerConnecting: Boolean = false,
    onConnectServer: () -> Unit = {},
    viewModel: WorkspaceFilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showActions by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editText by rememberSaveable(state.preview?.node?.path) { mutableStateOf("") }
    var uploadSource by rememberSaveable { mutableStateOf<String?>(null) }
    var uploadPath by rememberSaveable { mutableStateOf("") }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.prepareUpload(uri)
    }
    val wordWrap by viewModel.wordWrap.collectAsState()
    val gridView by viewModel.gridView.collectAsState()
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val fileSavedMessage = stringResource(R.string.workspace_file_saved)
    val fileSaveFailedMessage = stringResource(R.string.workspace_file_save_failed)
    val previewBytes = remember(state.preview) {
        state.preview?.content?.let(::workspaceFileBytes)
    }
    val previewMime = state.preview?.let { workspaceFileMimeType(it.node, it.content) }
    val isTextPreview = state.preview?.content?.type == "text" && previewMime?.startsWith("image/") != true
    val isMarkdownPreview = state.preview?.node?.name?.let(::isWorkspaceMarkdownFile) == true
    val isHtmlPreview = state.preview?.node?.name?.substringAfterLast('.', "")?.lowercase() in setOf("html", "htm")
    var renderMarkdown by rememberSaveable(state.preview?.node?.path) {
        mutableStateOf((isMarkdownPreview || isHtmlPreview) && state.preview?.line == null)
    }
    var showDiff by rememberSaveable(state.preview?.node?.path) { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(
        contract = WorkspaceDownloadContract(),
    ) { uri ->
        if (uri != null) viewModel.savePreview(uri)
    }
    val navigateBack = {
        if (viewModel.openedFromChatLink || !viewModel.navigateUp()) onNavigateBack()
    }

    BackHandler(onBack = navigateBack)
    fun openDownloaded(file: DownloadedWorkspaceFile, share: Boolean = false) {
        try {
            if (!share && file.mime == "application/vnd.android.package-archive" && !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:${context.packageName}")))
            } else {
                val intent = workspaceOpenIntent(file, share)
                context.startActivity(if (share) android.content.Intent.createChooser(intent, file.name) else intent)
            }
        } catch (e: Exception) {
            dev.minios.ocremote.logging.AppLogger.e("WorkspaceFiles", "Unable to open downloaded file", e)
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.workspace_no_viewer)) }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.saveResults.collectLatest { event ->
            when (event) {
                is WorkspaceFileEvent.Saved -> {
                    if (snackbarHostState.showSnackbar(fileSavedMessage, context.getString(R.string.workspace_open_file))
                        == SnackbarResult.ActionPerformed) openDownloaded(event.file)
                }
                is WorkspaceFileEvent.Open -> openDownloaded(event.file, event.share)
                is WorkspaceFileEvent.Error -> snackbarHostState.showSnackbar(event.message ?: fileSaveFailedMessage)
                is WorkspaceFileEvent.UploadDestination -> { uploadSource = event.source; uploadPath = event.path }
                WorkspaceFileEvent.Uploaded -> snackbarHostState.showSnackbar(context.getString(R.string.workspace_upload_complete))
            }
        }
    }

    Scaffold(
        snackbarHost = { dev.minios.ocremote.ui.components.AppSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val currentLocation = state.currentPath.ifEmpty { state.directory }
                    val preview = state.preview
                    if (state.gitStatuses != null && preview == null) {
                        Text(stringResource(R.string.workspace_changed_files), style = MaterialTheme.typography.titleMedium)
                    } else if (preview != null) {
                        Column {
                            Text(
                                text = preview.node.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = currentLocation,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = currentLocation,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(backIcon(), contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    val preview = state.preview
                    if (preview == null && state.gitStatuses == null) {
                        IconButton(onClick = { viewModel.setGridView(!gridView) }) {
                            Icon(if (gridView) Lucide.List else Lucide.LayoutGrid,
                                stringResource(if (gridView) R.string.workspace_list_view else R.string.workspace_grid_view))
                        }
                        IconButton(onClick = {
                            searchVisible = !searchVisible
                            if (!searchVisible) viewModel.searchFiles("")
                        }, enabled = isServerConnected) { Icon(Lucide.Search, stringResource(R.string.workspace_search_files)) }
                    }
                    IconButton(
                        onClick = { searchVisible = false; viewModel.browseParent() },
                        enabled = isServerConnected && (preview != null || state.currentPath.isNotEmpty()),
                    ) {
                        Icon(Lucide.FolderUp, stringResource(R.string.workspace_parent_directory))
                    }
                    if (isTextPreview) {
                        IconButton(onClick = { viewModel.setWordWrap(!wordWrap) }) {
                            Icon(
                                Lucide.WrapText,
                                contentDescription = stringResource(R.string.workspace_word_wrap),
                                 tint = if (wordWrap) {
                                     MaterialTheme.colorScheme.primary
                                 } else {
                                     MaterialTheme.colorScheme.onSurfaceVariant
                                 },
                                 modifier = Modifier.mirrorForRtl(),
                            )
                        }
                    }
                    if (isMarkdownPreview || isHtmlPreview) {
                        IconButton(onClick = { renderMarkdown = !renderMarkdown }) {
                            Icon(
                                imageVector = if (renderMarkdown) Lucide.Code else Lucide.ScanEye,
                                contentDescription = stringResource(
                                    if (renderMarkdown) R.string.workspace_show_source else R.string.workspace_show_preview,
                                ),
                            )
                        }
                    }
                    if (preview != null && (previewBytes != null || state.capabilities.rawDownload || preview.requiresDownload)) {
                        IconButton(onClick = { viewModel.openExternally() }, enabled = state.transfer == null && !state.isLoading) {
                            Icon(Lucide.ExternalLink, contentDescription = stringResource(R.string.workspace_open_file))
                        }
                        IconButton(onClick = { viewModel.prepareDownload()?.let(saveLauncher::launch) }, enabled = state.transfer == null && !state.isLoading) {
                            Icon(Lucide.Download, contentDescription = stringResource(R.string.workspace_download_file))
                        }
                    }
                    Box {
                        IconButton(onClick = { showActions = true }) { Icon(Lucide.EllipsisVertical, stringResource(R.string.more_options)) }
                        AppDropdownMenu(showActions, { showActions = false }) {
                            if (!preview?.content?.diff.isNullOrBlank()) AppDropdownMenuItem(
                                text = { Text(stringResource(if (showDiff) R.string.workspace_show_source else R.string.workspace_show_diff)) },
                                onClick = { showDiff = !showDiff }, leadingIcon = { Icon(Lucide.GitBranch, null) },
                            )
                            if (preview == null && state.capabilities.write) AppDropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_upload_file)) },
                                enabled = isServerConnected && state.transfer == null,
                                onClick = { uploadLauncher.launch(arrayOf("*/*")) }, leadingIcon = { Icon(Lucide.Upload, null) },
                            )
                            AppDropdownMenuItem(text = { Text(stringResource(R.string.retry)) }, enabled = isServerConnected,
                                onClick = viewModel::retry, leadingIcon = { Icon(Lucide.RefreshCw, null) })
                            if (preview != null && (previewBytes != null || state.capabilities.rawDownload || preview.requiresDownload)) {
                                AppDropdownMenuItem(text = { Text(stringResource(R.string.workspace_share_file)) },
                                    enabled = state.transfer == null, onClick = { viewModel.openExternally(share = true) },
                                    leadingIcon = { Icon(Lucide.Share2, null) })
                            }
                            if (state.capabilities.write && isTextPreview && preview != null && preview.content.content.length <= 1_000_000) {
                                AppDropdownMenuItem(text = { Text(stringResource(R.string.workspace_edit_file)) },
                                    enabled = isServerConnected && !state.isSaving,
                                    onClick = { editText = previewBytes?.toString(Charsets.UTF_8).orEmpty(); editing = true },
                                    leadingIcon = { Icon(Lucide.Pencil, null) })
                            }
                            if (preview == null && state.capabilities.textSearch) {
                                AppDropdownMenuItem(text = { Text(stringResource(R.string.workspace_search_contents)) }, enabled = isServerConnected,
                                    onClick = { searchVisible = true; viewModel.setContentSearch(!state.searchContents) },
                                    leadingIcon = { Icon(if (state.searchContents) Lucide.Check else Lucide.Search, null) })
                            }
                            if (preview == null && state.capabilities.gitStatus) {
                                AppDropdownMenuItem(text = { Text(stringResource(R.string.workspace_changed_files)) }, enabled = isServerConnected,
                                    onClick = { searchVisible = false; viewModel.loadGitStatus() }, leadingIcon = { Icon(Lucide.GitBranch, null) })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.transfer?.let { transfer ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.workspace_transfer_progress, transfer.bytes / 1024),
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::cancelTransfer) { Text(stringResource(R.string.cancel)) }
                }
            }
            if (searchVisible && state.preview == null) OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::searchFiles,
                label = { Text(stringResource(if (state.searchContents) R.string.workspace_search_contents else R.string.workspace_search_files)) },
                singleLine = true,
                enabled = isServerConnected,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (!isServerConnected) {
                ServerConnectionBanner(
                    connecting = isServerConnecting,
                    onConnect = onConnectServer,
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = state.error ?: stringResource(R.string.workspace_load_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = viewModel::retry, enabled = isServerConnected) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                state.preview != null -> WorkspaceFileContent(
                    preview = if (showDiff && state.preview!!.content.diff != null) state.preview!!.copy(
                        content = dev.minios.ocremote.data.api.FileContent("text", state.preview!!.content.diff!!),
                        line = null, endLine = null,
                    ) else state.preview!!,
                    bytes = previewBytes,
                    wordWrap = wordWrap,
                    renderMarkdown = renderMarkdown && !showDiff,
                    onOpenLink = viewModel::openLink,
                    canDownload = state.capabilities.rawDownload || state.preview?.requiresDownload == true,
                    modifier = Modifier.fillMaxSize(),
                )
                state.gitStatuses != null -> WorkspaceChangesPane(
                    changes = state.gitStatuses.orEmpty(), mode = state.gitMode,
                    committedSupported = state.capabilities.committedDiff,
                    path = state.gitDiffPath, diff = state.gitDiff,
                    loading = state.gitDiffLoading, error = state.gitDiffError, connected = isServerConnected,
                    onMode = viewModel::loadGitStatus, onSelect = viewModel::openGitDiff,
                    wordWrap = wordWrap, onWordWrapChange = viewModel::setWordWrap,
                )
                state.searchContents && state.searchQuery.isNotBlank() -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.matches) { match ->
                        ListItem(headlineContent = { Text("${match.path}:${match.lineNumber}") },
                            supportingContent = { Text(match.lines.trim(), maxLines = 3, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.clickable(enabled = isServerConnected) {
                                viewModel.open(FileNode(match.path.substringAfterLast('/'), match.path, "file"), match.lineNumber)
                            })
                    }
                }
                state.entries.isEmpty() && !state.isLoading -> {
                    Text(
                        text = stringResource(R.string.workspace_empty_directory),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    WorkspaceFileList(state.entries, gridView, isServerConnected, state.searchQuery.isNotBlank(),
                        onOpen = { viewModel.open(it) }, thumbnail = viewModel::thumbnail)
                }
            }

            if (state.isLoading) {
                AppLoadingEdge(
                    active = true,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        }
    }
    if (editing) AppDialog(onDismissRequest = { editing = false }, modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
        OutlinedTextField(editText, { editText = it }, label = { Text(state.preview?.node?.name.orEmpty()) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).padding(12.dp), minLines = 1, maxLines = 20)
        dev.minios.ocremote.ui.components.AppDialogActions(
            dismissText = stringResource(R.string.cancel), confirmText = stringResource(R.string.workspace_save_file),
            onDismiss = { editing = false }, onConfirm = { viewModel.saveText(editText); editing = false },
            confirmEnabled = isServerConnected && !state.isSaving, modifier = Modifier.padding(8.dp),
        )
    }
    if (uploadSource != null) AppDialog(onDismissRequest = { uploadSource = null }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.workspace_upload_file), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(uploadPath, { uploadPath = it }, label = { Text(stringResource(R.string.workspace_destination_path)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true)
        Text(stringResource(R.string.workspace_upload_overwrite), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        dev.minios.ocremote.ui.components.AppDialogActions(
            dismissText = stringResource(R.string.cancel), confirmText = stringResource(R.string.workspace_upload_file),
            onDismiss = { uploadSource = null }, onConfirm = {
                uploadSource?.let { viewModel.upload(android.net.Uri.parse(it), uploadPath) }
                uploadSource = null
            }, confirmEnabled = uploadPath.isNotBlank() && isServerConnected, modifier = Modifier.padding(8.dp),
        )
    }
    }
}

@Composable
private fun WorkspaceFileContent(
    preview: WorkspaceFilePreview,
    bytes: ByteArray?,
    wordWrap: Boolean,
    renderMarkdown: Boolean,
    onOpenLink: (String) -> Unit,
    canDownload: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val mime = workspaceFileMimeType(preview.node, preview.content)
    val external = LocalUriHandler.current
    val uriHandler = remember(external, onOpenLink) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (parseServerFileLink(uri) != null) onOpenLink(uri)
                else if (!uri.startsWith("file:", true)) external.openUri(uri)
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
    when {
        bytes == null -> {
            Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(if (canDownload) R.string.workspace_preview_unsupported else R.string.workspace_binary_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        mime.startsWith("image/") -> {
            AsyncImage(
                model = bytes,
                contentDescription = preview.node.name,
                modifier = modifier.padding(12.dp),
                contentScale = ContentScale.Fit,
            )
        }
        mime == "application/pdf" -> WorkspacePdfPreview(bytes, modifier)
        preview.node.name.substringAfterLast('.', "").lowercase() in setOf("html", "htm") && renderMarkdown ->
            WorkspaceHtmlPreview(preview.content.content, modifier)
        preview.content.type != "text" -> {
            Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.workspace_preview_unsupported), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        isWorkspaceMarkdownFile(preview.node.name) && renderMarkdown -> {
            WorkspaceMarkdownPreview(
                markdown = preview.content.content,
                wordWrap = wordWrap,
                modifier = modifier,
            )
        }
        else -> {
            val language = remember(preview.node.name) { workspaceSyntaxLanguage(preview.node.name) }
            val darkMode = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            val text = remember(preview.content.content, language, darkMode) {
                if (language == null || preview.content.content.length > MAX_HIGHLIGHTED_FILE_CHARS) {
                    AnnotatedString(preview.content.content)
                } else {
                    buildSafeHighlightedAnnotatedString(
                        code = preview.content.content,
                        language = language,
                        highlightsBuilder = Highlights.Builder().theme(SyntaxThemes.default(darkMode)),
                    )
                }
            }
            val vertical = rememberScrollState()
            val selectionColor = MaterialTheme.colorScheme.primaryContainer
            val selectedText = remember(text, preview.line, preview.endLine, selectionColor) {
                val line = preview.line
                if (line == null) text else AnnotatedString.Builder(text).apply {
                    val start = workspaceLineOffset(text.text, line)
                    val end = workspaceLineOffset(text.text, ((preview.endLine ?: line).toLong() + 1)
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    if (end > start) addStyle(SpanStyle(background = selectionColor), start, end)
                }.toAnnotatedString()
            }
            var textLayout by remember(preview.node.path) { mutableStateOf<TextLayoutResult?>(null) }
            LaunchedEffect(preview.node.path, preview.line, textLayout?.lineCount) {
                val layout = textLayout ?: return@LaunchedEffect
                val line = preview.line ?: return@LaunchedEffect
                val offset = workspaceLineOffset(preview.content.content, line)
                val visualLine = layout.getLineForOffset(offset)
                vertical.scrollTo(layout.getLineTop(visualLine).toInt())
            }
            Box(
                modifier = modifier
                    .verticalScroll(vertical)
                    .then(
                        if (wordWrap) Modifier else Modifier.horizontalScroll(rememberScrollState()),
                    )
                    .padding(12.dp),
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = selectedText,
                        onTextLayout = { textLayout = it },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        softWrap = wordWrap,
                        modifier = if (wordWrap) Modifier.fillMaxWidth() else Modifier,
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun WorkspaceMarkdownPreview(
    markdown: String,
    wordWrap: Boolean,
    modifier: Modifier = Modifier,
) {
    val components = remember {
        markdownComponents(
            codeBlock = safeHighlightedCodeBlock,
            codeFence = safeHighlightedCodeFence,
            heading1 = workspaceMarkdownHeading({ h1 }),
            heading2 = workspaceMarkdownHeading({ h2 }),
            heading3 = workspaceMarkdownHeading({ h3 }),
            heading4 = workspaceMarkdownHeading({ h4 }),
            heading5 = workspaceMarkdownHeading({ h5 }),
            heading6 = workspaceMarkdownHeading({ h6 }),
            setextHeading1 = workspaceMarkdownHeading({ h1 }, MarkdownTokenTypes.SETEXT_CONTENT),
            setextHeading2 = workspaceMarkdownHeading({ h2 }, MarkdownTokenTypes.SETEXT_CONTENT),
            table = horizontallyScrollableMarkdownTable,
        )
    }
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp)
    val typography = markdownTypography(
        h1 = MaterialTheme.typography.headlineMedium.copy(
            fontSize = 28.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
        ),
        h2 = MaterialTheme.typography.headlineSmall.copy(
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        h3 = MaterialTheme.typography.titleLarge.copy(
            fontSize = 21.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        h4 = MaterialTheme.typography.titleMedium.copy(
            fontSize = 18.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        h5 = MaterialTheme.typography.titleSmall.copy(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        h6 = MaterialTheme.typography.labelLarge.copy(
            fontSize = 14.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        text = bodyStyle,
        code = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        inlineCode = bodyStyle.copy(fontFamily = FontFamily.Monospace),
        quote = bodyStyle.copy(fontStyle = FontStyle.Italic),
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        link = bodyStyle.copy(fontWeight = FontWeight.Medium),
    )
    CompositionLocalProvider(LocalCodeWordWrap provides wordWrap) {
        Box(
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Markdown(
                    content = markdown,
                    annotator = dev.minios.ocremote.ui.components.rememberInteractiveMarkdownLinks(),
                    components = components,
                    typography = typography,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun workspaceMarkdownHeading(
    style: MarkdownTypography.() -> androidx.compose.ui.text.TextStyle,
    contentChildType: IElementType = MarkdownTokenTypes.ATX_CONTENT,
): MarkdownComponent = { model ->
    val headingStyle = style(model.typography)
    val headingTypography = model.typography.withInlineCodeSize(headingStyle)
    CompositionLocalProvider(LocalMarkdownTypography provides headingTypography) {
        MarkdownHeader(
            content = model.content,
            node = model.node,
            style = headingStyle,
            contentChildType = contentChildType,
        )
    }
}

private fun MarkdownTypography.withInlineCodeSize(
    headingStyle: androidx.compose.ui.text.TextStyle,
): MarkdownTypography = DefaultMarkdownTypography(
    h1 = h1,
    h2 = h2,
    h3 = h3,
    h4 = h4,
    h5 = h5,
    h6 = h6,
    text = text,
    code = code,
    inlineCode = inlineCode.copy(
        fontSize = headingStyle.fontSize,
        lineHeight = headingStyle.lineHeight,
        fontWeight = headingStyle.fontWeight,
    ),
    quote = quote,
    paragraph = paragraph,
    ordered = ordered,
    bullet = bullet,
    list = list,
    link = link,
)

internal fun workspaceFileIcon(kind: WorkspaceFileKind): ImageVector = when (kind) {
    WorkspaceFileKind.Directory -> Lucide.Folder
    WorkspaceFileKind.Code -> Lucide.Code
    WorkspaceFileKind.Config -> Lucide.Settings
    WorkspaceFileKind.Image -> Lucide.Image
    WorkspaceFileKind.Document -> Lucide.FileText
    WorkspaceFileKind.Pdf -> Lucide.FileType
    WorkspaceFileKind.Archive -> Lucide.Archive
    WorkspaceFileKind.Audio -> Lucide.FileAudio
    WorkspaceFileKind.Video -> Lucide.FileVideo
    WorkspaceFileKind.Table -> Lucide.Table
    WorkspaceFileKind.Generic -> Lucide.File
}

@Composable
internal fun workspaceFileIconColor(kind: WorkspaceFileKind): Color = when (kind) {
    WorkspaceFileKind.Directory -> MaterialTheme.colorScheme.primary
    WorkspaceFileKind.Code -> MaterialTheme.colorScheme.tertiary
    WorkspaceFileKind.Config -> MaterialTheme.colorScheme.secondary
    WorkspaceFileKind.Image -> MaterialTheme.colorScheme.primary
    WorkspaceFileKind.Pdf -> MaterialTheme.colorScheme.error
    WorkspaceFileKind.Archive -> MaterialTheme.colorScheme.secondary
    WorkspaceFileKind.Audio, WorkspaceFileKind.Video -> MaterialTheme.colorScheme.tertiary
    WorkspaceFileKind.Table -> MaterialTheme.colorScheme.primary
    WorkspaceFileKind.Document, WorkspaceFileKind.Generic -> MaterialTheme.colorScheme.onSurfaceVariant
}

private const val MAX_HIGHLIGHTED_FILE_CHARS = 500_000
