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
    val wordWrap by viewModel.wordWrap.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val fileSavedMessage = stringResource(R.string.workspace_file_saved)
    val fileSaveFailedMessage = stringResource(R.string.workspace_file_save_failed)
    val previewBytes = remember(state.preview) {
        state.preview?.content?.let(::workspaceFileBytes)
    }
    val previewMime = state.preview?.let { workspaceFileMimeType(it.node, it.content) }
    val isTextPreview = state.preview != null && previewBytes != null && previewMime?.startsWith("image/") != true
    val isMarkdownPreview = state.preview?.node?.name?.let(::isWorkspaceMarkdownFile) == true
    var renderMarkdown by rememberSaveable(state.preview?.node?.path) {
        mutableStateOf(isMarkdownPreview)
    }
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri != null) viewModel.savePreview(uri)
    }
    val navigateBack = {
        if (!viewModel.navigateUp()) onNavigateBack()
    }

    BackHandler(onBack = navigateBack)
    LaunchedEffect(Unit) {
        viewModel.saveResults.collect { saved ->
            snackbarHostState.showSnackbar(
                if (saved) fileSavedMessage else fileSaveFailedMessage,
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val currentLocation = state.currentPath.ifEmpty { state.directory }
                    val preview = state.preview
                    if (preview != null) {
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
                    if (isMarkdownPreview) {
                        IconButton(onClick = { renderMarkdown = !renderMarkdown }) {
                            Icon(
                                imageVector = if (renderMarkdown) Lucide.Code else Lucide.ScanEye,
                                contentDescription = stringResource(
                                    if (renderMarkdown) R.string.workspace_markdown_raw else R.string.workspace_markdown_preview,
                                ),
                            )
                        }
                    }
                    if (preview != null && previewBytes != null) {
                        IconButton(onClick = { saveLauncher.launch(preview.node.name) }) {
                            Icon(Lucide.Download, contentDescription = stringResource(R.string.workspace_download_file))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                    preview = state.preview!!,
                    bytes = previewBytes,
                    wordWrap = wordWrap,
                    renderMarkdown = renderMarkdown,
                    modifier = Modifier.fillMaxSize(),
                )
                state.entries.isEmpty() && !state.isLoading -> {
                    Text(
                        text = stringResource(R.string.workspace_empty_directory),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(state.entries, key = { it.path }) { node ->
                            val kind = workspaceFileKind(node)
                            ListItem(
                                headlineContent = {
                                    Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = if (node.ignored) {
                                    { Text(stringResource(R.string.workspace_ignored)) }
                                } else {
                                    null
                                },
                                leadingContent = {
                                    Icon(
                                         imageVector = workspaceFileIcon(kind),
                                         contentDescription = null,
                                         tint = workspaceFileIconColor(kind),
                                         modifier = if (kind == WorkspaceFileKind.Generic) {
                                             Modifier.mirrorForRtl()
                                         } else {
                                             Modifier
                                         },
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (node.ignored) 0.55f else 1f)
                                    .clickable(enabled = isServerConnected) { viewModel.open(node) },
                            )
                        }
                    }
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
}

@Composable
private fun WorkspaceFileContent(
    preview: WorkspaceFilePreview,
    bytes: ByteArray?,
    wordWrap: Boolean,
    renderMarkdown: Boolean,
    modifier: Modifier = Modifier,
) {
    val mime = workspaceFileMimeType(preview.node, preview.content)
    when {
        bytes == null -> {
            Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.workspace_binary_unavailable),
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
            Box(
                modifier = modifier
                    .verticalScroll(rememberScrollState())
                    .then(
                        if (wordWrap) Modifier else Modifier.horizontalScroll(rememberScrollState()),
                    )
                    .padding(12.dp),
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        softWrap = wordWrap,
                        modifier = if (wordWrap) Modifier.fillMaxWidth() else Modifier,
                    )
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

private fun workspaceFileIcon(kind: WorkspaceFileKind): ImageVector = when (kind) {
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
private fun workspaceFileIconColor(kind: WorkspaceFileKind): Color = when (kind) {
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
