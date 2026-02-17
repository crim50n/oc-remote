package dev.minios.ocremote.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import dev.minios.ocremote.domain.model.*
import dev.minios.ocremote.data.api.AgentInfo
import dev.minios.ocremote.data.api.CommandInfo
import dev.minios.ocremote.data.api.PromptPart
import dev.minios.ocremote.data.api.ProviderInfo
import dev.minios.ocremote.data.api.ProviderModel
import dev.minios.ocremote.ui.theme.CodeTypography
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import android.net.Uri
import android.content.res.Configuration
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import dev.minios.ocremote.R

/**
 * Chat Screen - conversation view with native markdown rendering.
 * Shows messages with streaming text rendered via mikepenz markdown renderer.
 */

/**
 * Slash command definition for the suggestion popup.
 * @param name Command name without the "/" prefix
 * @param description Human-readable description
 * @param type "server" commands are sent via API, "client" commands trigger local actions
 */
private data class SlashCommand(
    val name: String,
    val description: String?,
    val type: String // "server" or "client"
)

/** Client-side slash commands that mirror the original opencode TUI. */
@Composable
private fun clientCommands(): List<SlashCommand> = listOf(
    SlashCommand("new", stringResource(R.string.cmd_new), "client"),
    SlashCommand("compact", stringResource(R.string.cmd_compact), "client"),
    SlashCommand("fork", stringResource(R.string.cmd_fork), "client"),
    SlashCommand("share", stringResource(R.string.cmd_share), "client"),
    SlashCommand("unshare", stringResource(R.string.cmd_unshare), "client"),
    SlashCommand("undo", stringResource(R.string.cmd_undo), "client"),
    SlashCommand("redo", stringResource(R.string.cmd_redo), "client"),
    SlashCommand("rename", stringResource(R.string.cmd_rename), "client"),
)

/** Format a token count to a human-readable string (e.g., 1.2k, 45.3k, 1.2M). */
private fun formatTokenCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fk", count / 1_000.0)
        else -> count.toString()
    }
}

/**
 * VisualTransformation that highlights confirmed @file mentions as colored pills.
 * Only paths present in [confirmedFilePaths] are highlighted; unconfirmed @queries
 * remain unstyled so the user can see they haven't been selected yet.
 */
private class FileMentionVisualTransformation(
    private val confirmedFilePaths: Set<String>,
    private val highlightColor: Color,
    private val bgColor: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (confirmedFilePaths.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val raw = text.text
        val annotated = buildAnnotatedString {
            append(raw)
            // For each confirmed path, find all occurrences of @path in the text
            for (path in confirmedFilePaths) {
                val needle = "@$path"
                var searchFrom = 0
                while (true) {
                    val idx = raw.indexOf(needle, searchFrom)
                    if (idx == -1) break
                    // Ensure the match is not part of a longer token:
                    // next char after needle should be whitespace, end-of-string, or another @
                    val endIdx = idx + needle.length
                    if (endIdx < raw.length) {
                        val next = raw[endIdx]
                        if (!next.isWhitespace() && next != '@') {
                            searchFrom = endIdx
                            continue
                        }
                    }
                    addStyle(
                        SpanStyle(
                            color = highlightColor,
                            background = bgColor,
                            fontWeight = FontWeight.SemiBold
                        ),
                        start = idx,
                        end = endIdx
                    )
                    searchFrom = endIdx
                }
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

/**
 * Splits raw input text into a list of [PromptPart] objects.
 * Text around confirmed @file mentions becomes type="text" parts,
 * and each @file mention becomes a type="file" part with a file:// URL.
 */
private fun buildPromptParts(
    text: String,
    confirmedPaths: Set<String>,
    sessionDirectory: String?
): List<PromptPart> {
    if (confirmedPaths.isEmpty()) {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) emptyList()
        else listOf(PromptPart(type = "text", text = trimmed))
    }

    // Find all confirmed @path mentions with their positions
    data class Mention(val start: Int, val end: Int, val path: String)
    val mentions = mutableListOf<Mention>()

    for (path in confirmedPaths) {
        val needle = "@$path"
        var searchFrom = 0
        while (true) {
            val idx = text.indexOf(needle, searchFrom)
            if (idx == -1) break
            val endIdx = idx + needle.length
            // Boundary check: next char must be whitespace, end-of-string, or @
            if (endIdx < text.length) {
                val next = text[endIdx]
                if (!next.isWhitespace() && next != '@') {
                    searchFrom = endIdx
                    continue
                }
            }
            mentions.add(Mention(idx, endIdx, path))
            searchFrom = endIdx
        }
    }

    if (mentions.isEmpty()) {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) emptyList()
        else listOf(PromptPart(type = "text", text = trimmed))
    }

    // Sort by position
    mentions.sortBy { it.start }

    val parts = mutableListOf<PromptPart>()
    var cursor = 0

    for (mention in mentions) {
        // Add text before this mention
        if (mention.start > cursor) {
            val segment = text.substring(cursor, mention.start).trim()
            if (segment.isNotEmpty()) {
                parts.add(PromptPart(type = "text", text = segment))
            }
        }
        // Add file part
        val isDir = mention.path.endsWith("/")
        val absPath = if (sessionDirectory != null) "$sessionDirectory/${mention.path}" else mention.path
        parts.add(
            PromptPart(
                type = "file",
                path = mention.path,
                mime = if (isDir) "application/x-directory" else "text/plain",
                url = "file:///$absPath"
            )
        )
        cursor = mention.end
    }

    // Trailing text
    if (cursor < text.length) {
        val segment = text.substring(cursor).trim()
        if (segment.isNotEmpty()) {
            parts.add(PromptPart(type = "text", text = segment))
        }
    }

    return parts
}

/** An image attachment ready to send. */
private data class ImageAttachment(
    val uri: Uri,
    val mime: String,
    val filename: String,
    val dataUrl: String // "data:<mime>;base64,..."
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (sessionId: String) -> Unit = {},
    onOpenInWebView: () -> Unit = {},
    initialSharedImages: List<Uri> = emptyList(),
    onSharedImagesConsumed: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()
    var showModelPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    // @ file mention state
    val fileSearchResults by viewModel.fileSearchResults.collectAsState()
    val confirmedFilePaths by viewModel.confirmedFilePaths.collectAsState()

    // Image attachments
    val attachments = remember { mutableStateListOf<ImageAttachment>() }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        for (uri in uris) {
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "image/png"
                // Only accept image types and PDF (matching WebUI)
                val acceptedTypes = listOf("image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf")
                if (mimeType !in acceptedTypes) continue

                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val dataUrl = "data:$mimeType;base64,$base64"

                // Derive filename from URI or fallback
                val filename = uri.lastPathSegment?.substringAfterLast('/') ?: "image.png"

                attachments.add(
                    ImageAttachment(
                        uri = uri,
                        mime = mimeType,
                        filename = filename,
                        dataUrl = dataUrl
                    )
                )
            } catch (e: Exception) {
                // Skip files that fail to read
            }
        }
    }

    // Consume images shared from other apps via ACTION_SEND (one-shot)
    LaunchedEffect(initialSharedImages) {
        if (initialSharedImages.isEmpty()) return@LaunchedEffect
        for (uri in initialSharedImages) {
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "image/png"
                val acceptedTypes = listOf("image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf")
                if (mimeType !in acceptedTypes) continue

                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val dataUrl = "data:$mimeType;base64,$base64"

                val filename = uri.lastPathSegment?.substringAfterLast('/') ?: "shared_image.png"

                attachments.add(
                    ImageAttachment(
                        uri = uri,
                        mime = mimeType,
                        filename = filename,
                        dataUrl = dataUrl
                    )
                )
            } catch (e: Exception) {
                Log.w("ChatScreen", "Failed to read shared image: ${e.message}")
            }
        }
        onSharedImagesConsumed()
    }

    // Show errors as snackbar when messages are already loaded
    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (error != null && uiState.messages.isNotEmpty()) {
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
        }
    }

    // Whether auto-scroll should follow new content.
    // Disabled when user manually scrolls up; re-enabled when user scrolls back to bottom.
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // True when the very bottom of the list is visible (accounting for offset within tall items)
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            val totalItems = info.totalItemsCount
            if (lastVisible.index < totalItems - 1) return@derivedStateOf false
            // Last item is visible — check if its bottom edge is within the viewport
            val itemBottom = lastVisible.offset + lastVisible.size
            val viewportEnd = info.viewportEndOffset
            itemBottom <= viewportEnd + 50 // 50px tolerance
        }
    }

    // When user touches the list, disable auto-scroll; re-enable when they reach the bottom
    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
        if (listState.isScrollInProgress) {
            // User is actively dragging/flinging — disable auto-scroll
            autoScrollEnabled = false
        } else if (isAtBottom) {
            // User stopped scrolling and ended up at the bottom — re-enable
            autoScrollEnabled = true
        }
    }


    // Auto-scroll to bottom when new content arrives (only if auto-scroll is enabled)
    // Track message count, part count, and content length of the last part to catch streaming updates
    val messageCount = uiState.messages.size
    val lastPartCount = uiState.messages.lastOrNull()?.parts?.size ?: 0
    val lastContentLength = uiState.messages.lastOrNull()?.parts?.lastOrNull()?.let { part ->
        when (part) {
            is Part.Text -> part.text.length
            is Part.Reasoning -> part.text.length
            is Part.Tool -> when (val s = part.state) {
                is ToolState.Completed -> s.output.length
                is ToolState.Error -> s.error.length
                is ToolState.Running -> s.title?.length ?: 1
                is ToolState.Pending -> 0
            }
            else -> 0
        }
    } ?: 0
    val pendingCount = uiState.pendingPermissions.size + uiState.pendingQuestions.size
    val isBusy = uiState.sessionStatus is SessionStatus.Busy
    LaunchedEffect(messageCount, lastPartCount, lastContentLength, pendingCount, isBusy) {
        if (messageCount > 0 && autoScrollEnabled) {
            val lastIndex = listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1
            listState.scrollToItem(lastIndex)
            // scrollToItem goes to the TOP of the last item; when a message is
            // taller than the viewport (e.g. streaming summarisation) we also
            // need to scroll past it so the user sees the bottom of that message.
            val lastItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            if (lastItem != null) {
                val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                val overflow = lastItem.size - viewport
                if (overflow > 0) {
                    listState.scrollBy(overflow.toFloat())
                }
            }
        }
    }

    // Also auto-scroll when first loading
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading && messageCount > 0) {
            val lastIndex = listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1
            listState.scrollToItem(lastIndex)
            val lastItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            if (lastItem != null) {
                val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                val overflow = lastItem.size - viewport
                if (overflow > 0) {
                    listState.scrollBy(overflow.toFloat())
                }
            }
            autoScrollEnabled = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.sessionTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Subtitle: working status or cost/token summary
                        if (uiState.sessionStatus is SessionStatus.Busy) {
                            Text(
                                text = stringResource(R.string.session_status_busy),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        } else if (uiState.totalCost > 0 || uiState.totalInputTokens > 0) {
                            val costStr = if (uiState.totalCost > 0) {
                                stringResource(R.string.chat_cost_format, String.format("%.4f", uiState.totalCost))
                            } else null
                            val tokenStr = formatTokenCount(uiState.totalInputTokens + uiState.totalOutputTokens)
                            Text(
                                text = listOfNotNull(costStr, stringResource(R.string.chat_tokens_summary, tokenStr)).joinToString(" \u00b7 "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (uiState.sessionStatus is SessionStatus.Busy) {
                        IconButton(onClick = { viewModel.abortSession() }) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = stringResource(R.string.chat_stop),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_open_in_web)) },
                                onClick = {
                                    showMenu = false
                                    onOpenInWebView()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Language, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_new_session)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.createNewSession { session ->
                                        if (session != null) {
                                            onNavigateToSession(session.id)
                                        } else {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_session_create_failed))
                                            }
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_fork_session)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.forkSession { session ->
                                        if (session != null) {
                                            onNavigateToSession(session.id)
                                        } else {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_fork_failed))
                                            }
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.CopyAll, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_compact_session)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.compactSession { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_session_compacted) else context.getString(R.string.chat_session_compact_failed)
                                            )
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Compress, contentDescription = null)
                                }
                            )
                            // Show Share or Unshare depending on current share status
                            if (uiState.shareUrl != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cmd_unshare)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.unshareSession { ok ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (ok) context.getString(R.string.chat_session_unshared) else context.getString(R.string.chat_session_unshare_failed)
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.LinkOff, contentDescription = null)
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_share_session)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.shareSession { url ->
                                            coroutineScope.launch {
                                                if (url != null) {
                                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(url))
                                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_share_url_copied))
                                                } else {
                                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_share_failed))
                                                }
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Share, contentDescription = null)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_rename_session)) },
                                onClick = {
                                    showMenu = false
                                    showRenameDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            val modelLabel = if (uiState.selectedModelId != null && uiState.providers.isNotEmpty()) {
                val provider = uiState.providers.find { it.id == uiState.selectedProviderId }
                val model = provider?.models?.get(uiState.selectedModelId)
                model?.name ?: uiState.selectedModelId ?: ""
            } else ""

            ChatInputBar(
                textFieldValue = inputText,
                onTextFieldValueChange = { newValue ->
                    inputText = newValue
                    // Detect @query before cursor for file mention
                    val cursorPos = newValue.selection.start
                    val textBefore = newValue.text.substring(0, cursorPos)
                    val atMatch = Regex("@(\\S*)$").find(textBefore)
                    if (atMatch != null) {
                        val query = atMatch.groupValues[1]
                        viewModel.searchFilesForMention(query)
                    } else {
                        viewModel.clearFileSearch()
                    }
                },
                onSend = {
                    val rawText = inputText.text
                    // Build prompt parts: split text around confirmed @file mentions
                    val allParts = buildPromptParts(rawText, confirmedFilePaths, viewModel.getSessionDirectory())
                    // Add image attachments
                    val attachmentParts = attachments.map { att ->
                        PromptPart(
                            type = "file",
                            mime = att.mime,
                            url = att.dataUrl,
                            filename = att.filename
                        )
                    }
                    viewModel.sendMessage(allParts, attachmentParts)
                    inputText = TextFieldValue("")
                    attachments.clear()
                    viewModel.clearConfirmedPaths()
                    viewModel.clearFileSearch()
                },
                isSending = uiState.isSending,
                isBusy = uiState.sessionStatus is SessionStatus.Busy,
                messages = uiState.messages,
                attachments = attachments,
                onAttach = { imagePickerLauncher.launch("image/*") },
                onRemoveAttachment = { index ->
                    if (index in attachments.indices) attachments.removeAt(index)
                },
                modelLabel = modelLabel,
                onModelClick = { showModelPicker = true },
                agents = uiState.agents,
                selectedAgent = uiState.selectedAgent,
                onAgentSelect = { viewModel.selectAgent(it) },
                variantNames = uiState.variantNames,
                selectedVariant = uiState.selectedVariant,
                onCycleVariant = { viewModel.cycleVariant() },
                commands = uiState.commands,
                fileSearchResults = fileSearchResults,
                confirmedFilePaths = confirmedFilePaths,
                onFileSelected = { path ->
                    // Replace @query with @path in text
                    val cursorPos = inputText.selection.start
                    val textBefore = inputText.text.substring(0, cursorPos)
                    val atMatch = Regex("@(\\S*)$").find(textBefore)
                    if (atMatch != null) {
                        val matchStart = atMatch.range.first
                        val replacement = "@$path "
                        val newText = inputText.text.substring(0, matchStart) + replacement +
                                inputText.text.substring(cursorPos)
                        val newCursor = matchStart + replacement.length
                        inputText = TextFieldValue(
                            text = newText,
                            selection = TextRange(newCursor)
                        )
                    }
                    viewModel.confirmFilePath(path)
                    viewModel.clearFileSearch()
                },
                onSlashCommand = { cmd ->
                    when (cmd.name) {
                        "new" -> {
                            // Create a new session and navigate to it
                            viewModel.createNewSession { session ->
                                if (session != null) {
                                    onNavigateToSession(session.id)
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_session_create_failed))
                                    }
                                }
                            }
                        }
                        "compact" -> {
                            viewModel.compactSession { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_session_compacted) else context.getString(R.string.chat_session_compact_failed)
                                    )
                                }
                            }
                        }
                        "fork" -> {
                            viewModel.forkSession { session ->
                                if (session != null) {
                                    onNavigateToSession(session.id)
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_fork_failed))
                                    }
                                }
                            }
                        }
                        "share" -> {
                            viewModel.shareSession { url ->
                                coroutineScope.launch {
                                    if (url != null) {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(url))
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_share_url_copied))
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_share_failed))
                                    }
                                }
                            }
                        }
                        "unshare" -> {
                            viewModel.unshareSession { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_session_unshared) else context.getString(R.string.chat_session_unshare_failed)
                                    )
                                }
                            }
                        }
                        "undo" -> {
                            viewModel.undoMessage { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_message_undone) else context.getString(R.string.chat_message_undo_failed)
                                    )
                                }
                            }
                        }
                        "redo" -> {
                            viewModel.redoMessage { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_message_redone) else context.getString(R.string.chat_message_redo_failed)
                                    )
                                }
                            }
                        }
                        "rename" -> {
                            showRenameDialog = true
                        }
                        else -> {
                            // Server command — execute via API
                            viewModel.executeCommand(cmd.name) { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_command_executed, cmd.name) else context.getString(R.string.chat_command_failed, cmd.name)
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading && uiState.messages.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.error != null && uiState.messages.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = uiState.error ?: stringResource(R.string.session_unknown_error),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { viewModel.loadMessages() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                uiState.messages.isEmpty() && !uiState.isLoading -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.chat_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.chat_type_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // "Load earlier messages" button at the top
                        if (uiState.hasOlderMessages) {
                            item(key = "load_older") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (uiState.isLoadingOlder) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Text(
                                                text = stringResource(R.string.chat_loading_earlier),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        TextButton(onClick = { viewModel.loadOlderMessages() }) {
                                            Text(stringResource(R.string.chat_load_earlier))
                                        }
                                    }
                                }
                            }
                        }

                        items(
                            uiState.messages,
                            key = { it.message.id }
                        ) { chatMessage ->
                            // Skip empty user messages (e.g. compact/summarize triggers)
                            val hasVisibleContent = if (chatMessage.isUser) {
                                chatMessage.parts.any { part ->
                                    part is Part.Text && part.synthetic != true && part.ignored != true && part.text.isNotBlank()
                                }
                            } else true
                            if (!hasVisibleContent) return@items

                            ChatMessageBubble(
                                chatMessage = chatMessage,
                                onRevert = if (chatMessage.isUser) {
                                    {
                                        viewModel.revertMessage(chatMessage.message.id) { ok ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (ok) context.getString(R.string.chat_message_reverted) else context.getString(R.string.chat_message_revert_failed)
                                                )
                                            }
                                        }
                                    }
                                } else null,
                                onCopyText = {
                                    val text = chatMessage.parts
                                        .filterIsInstance<Part.Text>()
                                        .joinToString("\n") { it.text }
                                    if (text.isNotBlank()) {
                                        clipboardManager.setText(
                                            androidx.compose.ui.text.AnnotatedString(text)
                                        )
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                        }
                                    }
                                }
                            )
                        }

                        // Revert banner
                        if (uiState.revert != null) {
                            item(key = "revert_banner") {
                                RevertBanner(onRedo = {
                                    viewModel.redoMessage { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                            )
                                        }
                                    }
                                })
                            }
                        }

                        // Pending permissions
                        items(
                            uiState.pendingPermissions,
                            key = { "perm_${it.id}" }
                        ) { permission ->
                            PermissionCard(
                                permission = permission,
                                onOnce = { viewModel.replyToPermission(permission.id, "once") },
                                onAlways = { viewModel.replyToPermission(permission.id, "always") },
                                onReject = { viewModel.replyToPermission(permission.id, "reject") }
                            )
                        }

                        // Pending questions
                        items(
                            uiState.pendingQuestions,
                            key = { "question_${it.id}" }
                        ) { question ->
                            QuestionCard(
                                question = question,
                                onSubmit = { answers ->
                                    viewModel.replyToQuestion(question.id, answers)
                                },
                                onReject = {
                                    viewModel.rejectQuestion(question.id)
                                }
                            )
                        }
                    }

                    // Scroll-to-bottom FAB
                    if (!isAtBottom && !autoScrollEnabled) {
                        SmallFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    val lastIndex = listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1
                                    listState.scrollToItem(lastIndex)
                                    val lastItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                                    if (lastItem != null) {
                                        val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                                        val overflow = lastItem.size - viewport
                                        if (overflow > 0) {
                                            listState.scrollBy(overflow.toFloat())
                                        }
                                    }
                                    autoScrollEnabled = true
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.chat_scroll_bottom),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Model picker dialog
    if (showModelPicker) {
        ModelPickerDialog(
            providers = uiState.providers,
            selectedProviderId = uiState.selectedProviderId,
            selectedModelId = uiState.selectedModelId,
            onSelect = { providerId, modelId ->
                viewModel.selectModel(providerId, modelId)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }

    // Rename dialog
    if (showRenameDialog) {
        var renameText by remember { mutableStateOf(uiState.sessionTitle) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.session_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.session_rename_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameSession(renameText) { ok ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (ok) context.getString(R.string.chat_session_renamed) else context.getString(R.string.chat_session_rename_failed)
                                )
                            }
                        }
                        showRenameDialog = false
                    },
                    enabled = renameText.isNotBlank()
                ) {
                    Text(stringResource(R.string.session_rename_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ModelPickerDialog(
    providers: List<ProviderInfo>,
    selectedProviderId: String?,
    selectedModelId: String?,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit
) {
    fun isModelFree(providerId: String, model: ProviderModel): Boolean {
        if (providerId != "opencode") return false
        val cost = model.cost ?: return true
        return cost.input == 0.0
    }

    // Sort providers: "opencode" first, then by name
    val sortedProviders = remember(providers) {
        providers
            .filter { it.models.isNotEmpty() }
            .sortedWith(compareBy<ProviderInfo> { it.id != "opencode" }.thenBy { it.name.lowercase() })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
            ) {
                for ((index, provider) in sortedProviders.withIndex()) {
                    val topPad = if (index == 0) 0.dp else 12.dp

                    // Sort models: free first, then by name
                    val sortedModels = provider.models.values
                        .sortedWith(compareBy<ProviderModel> { !isModelFree(provider.id, it) }.thenBy { it.name.lowercase() })

                    // Provider header
                    item(key = "provider_header_${provider.id}") {
                        Text(
                            text = (provider.name.ifEmpty { provider.id }).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            letterSpacing = 1.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = topPad, bottom = 2.dp, start = 4.dp)
                        )
                    }

                    items(
                        sortedModels,
                        key = { "model_${provider.id}_${it.id}" }
                    ) { model ->
                        val isSelected = provider.id == selectedProviderId && model.id == selectedModelId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else Color.Transparent
                                )
                                .clickable { onSelect(provider.id, model.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.name.ifEmpty { model.id },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isModelFree(provider.id, model)) {
                                    Text(
                                        text = stringResource(R.string.chat_free_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/**
 * Determine the "status text" for a group of step parts (like WebUI).
 * E.g., "Making edits", "Running commands", "Searching codebase", "Thinking"
 */
@Composable
private fun resolveStepsStatus(stepParts: List<Part>): String {
    val toolParts = stepParts.filterIsInstance<Part.Tool>()
    val hasRunning = toolParts.any { it.state is ToolState.Running }
    if (!hasRunning && toolParts.all { it.state is ToolState.Completed || it.state is ToolState.Error }) {
        // All done — summarize
        val editCount = toolParts.count { it.tool in listOf("edit", "write", "apply_patch", "multiedit") }
        val bashCount = toolParts.count { it.tool == "bash" }
        val searchCount = toolParts.count { it.tool in listOf("glob", "grep", "read", "list", "listDirectory") }
        return when {
            editCount > 0 && bashCount == 0 && searchCount == 0 -> {
                if (editCount == 1) 
                    stringResource(R.string.chat_status_edits, editCount)
                else 
                    stringResource(R.string.chat_status_edits_plural, editCount)
            }
            bashCount > 0 && editCount == 0 && searchCount == 0 -> {
                if (bashCount == 1)
                    stringResource(R.string.chat_status_commands, bashCount)
                else
                    stringResource(R.string.chat_status_commands_plural, bashCount)
            }
            else -> {
                if (toolParts.size == 1)
                    stringResource(R.string.chat_status_steps, toolParts.size)
                else
                    stringResource(R.string.chat_status_steps_plural, toolParts.size)
            }
        }
    }
    // Currently running — describe what's happening
    val runningTool = toolParts.lastOrNull { it.state is ToolState.Running }
    return when (runningTool?.tool) {
        "edit", "write", "multiedit" -> stringResource(R.string.chat_status_making_edits)
        "bash" -> stringResource(R.string.chat_status_running_commands)
        "read", "glob", "grep", "list", "listDirectory" -> stringResource(R.string.chat_status_searching)
        "webfetch" -> stringResource(R.string.chat_status_fetching_url)
        "task" -> stringResource(R.string.chat_status_running_subagent)
        "todowrite" -> stringResource(R.string.chat_status_updating_tasks)
        else -> stringResource(R.string.chat_status_thinking)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatMessageBubble(
    chatMessage: ChatMessage,
    onRevert: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null
) {
    val isUser = chatMessage.isUser
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val maxWidthFraction = if (isUser) 0.85f else 1f

    // Separate parts into text/reasoning (shown directly) and step parts (behind toggle)
    val visibleParts = if (isUser) {
        chatMessage.parts.filter { part ->
            when (part) {
                is Part.Text -> part.synthetic != true && part.ignored != true && part.text.isNotBlank()
                else -> true
            }
        }
    } else {
        chatMessage.parts
    }

    // For assistant messages: split into "content" (text, reasoning, patch) and "steps" (tool calls, step markers)
    val contentParts: List<Part>
    val stepParts: List<Part>
    if (!isUser) {
        contentParts = visibleParts.filter { part ->
            part is Part.Text || part is Part.Reasoning || part is Part.Patch ||
                    part is Part.File || part is Part.Permission || part is Part.Question ||
                    part is Part.Abort || part is Part.Retry
        }
        stepParts = visibleParts.filter { part ->
            part is Part.Tool || part is Part.StepStart || part is Part.StepFinish
        }
    } else {
        contentParts = visibleParts
        stepParts = emptyList()
    }

    val hasSteps = stepParts.isNotEmpty()
    var stepsExpanded by remember { mutableStateOf(false) }

    // Check if any tool is currently running (show spinner)
    val hasRunningTool = stepParts.any { it is Part.Tool && it.state is ToolState.Running }

        val bubbleContent: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 18.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            color = backgroundColor,
            tonalElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier.fillMaxWidth(maxWidthFraction)
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // "Response" header with copy button — assistant messages only
                    if (!isUser) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.chat_response),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 0.8.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = textColor.copy(alpha = 0.4f)
                            )
                            if (onCopyText != null) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.chat_copy),
                                    modifier = Modifier
                                        .size(15.dp)
                                        .clickable { onCopyText() },
                                    tint = textColor.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }

                    // Steps toggle (like WebUI "Show/Hide steps")
                    if (hasSteps) {
                        val stepsStatus = resolveStepsStatus(stepParts)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { stepsExpanded = !stepsExpanded }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasRunningTool) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            } else {
                                Icon(
                                    imageVector = if (stepsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = textColor.copy(alpha = 0.5f)
                                )
                            }
                            Text(
                                text = if (stepsExpanded) stringResource(R.string.chat_hide_steps) else stepsStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.6f)
                            )
                        }

                        // Expanded step parts
                        AnimatedVisibility(visible = stepsExpanded) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (part in stepParts) {
                                    PartContent(
                                        part = part,
                                        textColor = textColor,
                                        isUser = isUser
                                    )
                                }
                            }
                        }
                    }

                    // Content parts (text, reasoning, patches, etc.)
                    // Group image file parts into a compact thumbnail row
                    val imageFiles = contentParts.filterIsInstance<Part.File>()
                        .filter { it.mime.startsWith("image/") && !it.url.isNullOrBlank() }
                    val otherParts = contentParts.filter { part ->
                        !(part is Part.File && part.mime.startsWith("image/") && !part.url.isNullOrBlank())
                    }

                    // Render image thumbnails as a horizontal row
                    if (imageFiles.isNotEmpty()) {
                        ImageThumbnailRow(imageFiles)
                    }

                    // Render remaining parts
                    for (part in otherParts) {
                        PartContent(
                            part = part,
                            textColor = textColor,
                            isUser = isUser
                        )
                    }

                    // If no visible content, show empty state for user messages
                    if (visibleParts.isEmpty() && isUser) {
                        Text(
                            text = stringResource(R.string.chat_empty_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (isUser && onRevert != null) {
            // Swipe-to-revert for user messages with confirmation dialog
            var showRevertConfirmation by remember { mutableStateOf(false) }

            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) {
                        showRevertConfirmation = true
                    }
                    false // don't actually dismiss; wait for dialog confirmation
                }
            )

            if (showRevertConfirmation) {
                AlertDialog(
                    onDismissRequest = { showRevertConfirmation = false },
                    title = { Text(stringResource(R.string.chat_revert_title)) },
                    text = { Text(stringResource(R.string.chat_revert_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showRevertConfirmation = false
                                onRevert()
                            }
                        ) {
                            Text(stringResource(R.string.chat_revert), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRevertConfirmation = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }

            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    val direction = dismissState.dismissDirection
                    val bgColor = MaterialTheme.colorScheme.errorContainer
                    val iconAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                        Alignment.CenterStart
                    } else {
                        Alignment.CenterEnd
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 4.dp,
                                bottomStart = 18.dp,
                                bottomEnd = 18.dp
                            ))
                            .background(bgColor)
                            .padding(horizontal = 20.dp),
                        contentAlignment = iconAlignment
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = stringResource(R.string.chat_revert),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = stringResource(R.string.chat_revert),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                },
                enableDismissFromStartToEnd = true,
                enableDismissFromEndToStart = true
            ) {
                bubbleContent()
            }
        } else {
            bubbleContent()
        }
    }
}

/**
 * Banner shown when messages have been reverted.
 * Tapping restores (redo) the reverted messages.
 */
@Composable
private fun RevertBanner(onRedo: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { onRedo() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_messages_reverted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = stringResource(R.string.chat_tap_restore),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
            }
            Icon(
                Icons.Default.Restore,
                contentDescription = stringResource(R.string.chat_restore),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun PartContent(
    part: Part,
    textColor: Color,
    isUser: Boolean = false
) {
    when (part) {
        is Part.Text -> {
            // Hide synthetic/ignored text parts (internal system content)
            if (part.text.isNotBlank() && part.synthetic != true && part.ignored != true) {
                MarkdownContent(
                    markdown = part.text,
                    textColor = textColor,
                    isUser = isUser
                )
            }
        }
        is Part.Reasoning -> {
            if (part.text.isNotBlank()) {
                ReasoningBlock(text = part.text)
            }
        }
        is Part.Tool -> {
            // todoread parts are filtered out entirely (WebUI convention)
            if (part.tool == "todoread") {
                // skip
            } else if (part.tool == "todowrite") {
                TodoListCard(tool = part)
            } else {
                // Dispatch to tool-specific renderers (like WebUI)
                when (part.tool) {
                    "edit", "multiedit" -> EditToolCard(tool = part)
                    "write" -> WriteToolCard(tool = part)
                    "bash" -> BashToolCard(tool = part)
                    "read" -> ReadToolCard(tool = part)
                    "glob", "grep" -> SearchToolCard(tool = part)
                    "task" -> TaskToolCard(tool = part)
                    else -> ToolCallCard(tool = part)
                }
            }
        }
        is Part.StepStart -> {
            // Visual separator between steps (hidden - WebUI doesn't show these)
        }
        is Part.StepFinish -> {
            // Token/cost info hidden from message bubbles (WebUI convention)
        }
        is Part.Patch -> {
            PatchCard(patch = part)
        }
        is Part.File -> {
            FileCard(file = part)
        }
        is Part.Permission -> {
            Text(
                text = stringResource(R.string.chat_permission_label, part.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        is Part.Question -> {
            Text(
                text = stringResource(R.string.chat_question_inline, part.question),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        is Part.Abort -> {
            Text(
                text = stringResource(R.string.chat_aborted, part.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        is Part.Retry -> {
            Text(
                text = stringResource(R.string.chat_retry, part.attempt, part.errorMessage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        // Ignore less relevant parts
        is Part.Snapshot, is Part.Subtask, is Part.Compaction,
        is Part.Agent, is Part.SessionTurn, is Part.Unknown -> { /* skip */ }
    }
}

/**
 * Renders markdown content using mikepenz markdown renderer with code syntax highlighting.
 */
@Composable
private fun MarkdownContent(
    markdown: String,
    textColor: Color,
    isUser: Boolean
) {
    // Inline code: visible pill with accent text
    val inlineCodeBg = if (isUser) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val inlineCodeFg = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.primary
    }
    // Code blocks: distinct background
    val codeBlockBg = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val codeBlockFg = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    // Balanced text style with better line-height for readability
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        color = textColor,
        lineHeight = 22.sp
    )

    val colors = markdownColor(
        text = textColor,
        codeText = codeBlockFg,
        inlineCodeText = inlineCodeFg,
        linkText = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
        codeBackground = codeBlockBg,
        inlineCodeBackground = inlineCodeBg,
        dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )

    val typography = markdownTypography(
        h1 = MaterialTheme.typography.titleLarge.copy(
            color = textColor,
            fontWeight = FontWeight.Bold,
            lineHeight = 32.sp
        ),
        h2 = MaterialTheme.typography.titleMedium.copy(
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 28.sp
        ),
        h3 = MaterialTheme.typography.titleSmall.copy(
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 24.sp
        ),
        h4 = MaterialTheme.typography.bodyLarge.copy(
            color = textColor,
            fontWeight = FontWeight.SemiBold
        ),
        h5 = MaterialTheme.typography.bodyMedium.copy(
            color = textColor,
            fontWeight = FontWeight.SemiBold
        ),
        h6 = MaterialTheme.typography.bodyMedium.copy(
            color = textColor.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium
        ),
        text = bodyStyle,
        code = CodeTypography.copy(color = codeBlockFg),
        inlineCode = CodeTypography.copy(
            color = inlineCodeFg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        ),
        quote = bodyStyle.copy(
            color = textColor.copy(alpha = 0.65f),
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        ),
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        link = bodyStyle.copy(
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    )

    val components = markdownComponents(
        codeBlock = highlightedCodeBlock,
        codeFence = highlightedCodeFence
    )

    Markdown(
        content = markdown,
        colors = colors,
        typography = typography,
        components = components,
        imageTransformer = Coil2ImageTransformerImpl,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ReasoningBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Left accent border
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = stringResource(R.string.chat_status_thinking),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 0.6.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        lineHeight = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ToolCallCard(tool: Part.Tool) {
    val stateColor = when (tool.state) {
        is ToolState.Pending -> MaterialTheme.colorScheme.outline
        is ToolState.Running -> MaterialTheme.colorScheme.tertiary
        is ToolState.Completed -> MaterialTheme.colorScheme.primary
        is ToolState.Error -> MaterialTheme.colorScheme.error
    }

    // Extract input args for context-specific display
    val input = when (val state = tool.state) {
        is ToolState.Pending -> state.input
        is ToolState.Running -> state.input
        is ToolState.Completed -> state.input
        is ToolState.Error -> state.input
    }

    // Resolve display info based on tool type
    val toolDisplay = resolveToolDisplay(tool.tool, tool.state, input)

    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (tool.state is ToolState.Completed || tool.state is ToolState.Error) {
                            mod.clickable { expanded = !expanded }
                        } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when (tool.state) {
                            is ToolState.Running -> Icons.Default.Sync
                            is ToolState.Completed -> toolDisplay.icon
                            is ToolState.Error -> Icons.Default.Error
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (tool.state is ToolState.Error) stateColor else toolDisplay.iconTint ?: stateColor
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = toolDisplay.title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (toolDisplay.subtitle != null) {
                            Text(
                                text = toolDisplay.subtitle,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // Expand indicator for completed/errored tools
                if (tool.state is ToolState.Completed || tool.state is ToolState.Error) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                } else if (tool.state is ToolState.Running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = stateColor
                    )
                }
            }

            // Expandable details
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val output = when (val s = tool.state) {
                        is ToolState.Completed -> s.output
                        is ToolState.Error -> s.error
                        else -> ""
                    }
                    if (output.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = output.take(3000),
                                style = CodeTypography.copy(
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                modifier = Modifier
                                    .padding(8.dp)
                                    .horizontalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Display info for a tool call, resolved from tool name and input args.
 */
private data class ToolDisplayInfo(
    val title: String,
    val subtitle: String? = null,
    val icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Check,
    val iconTint: Color? = null
)

/**
 * Resolve display info for a tool call based on its type and input arguments.
 * Matches WebUI tool registry behavior with human-readable titles.
 */
@Composable
private fun resolveToolDisplay(
    toolName: String,
    state: ToolState,
    input: Map<String, kotlinx.serialization.json.JsonElement>
): ToolDisplayInfo {
    // Use server-provided title if available
    val serverTitle = when (state) {
        is ToolState.Running -> state.title
        is ToolState.Completed -> state.title
        else -> null
    }

    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull
        ?: input["file"]?.jsonPrimitive?.contentOrNull
    val shortPath = filePath?.substringAfterLast('/')

    return when (toolName) {
        "read" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_read_file),
                subtitle = shortPath ?: filePath,
                icon = Icons.Default.Description
            )
        }
        "write" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_write_file),
                subtitle = shortPath ?: filePath,
                icon = Icons.Default.EditNote
            )
        }
        "edit" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_edit_file),
                subtitle = shortPath ?: filePath,
                icon = Icons.Default.Edit
            )
        }
        "bash" -> {
            val command = input["command"]?.jsonPrimitive?.contentOrNull
            val shortCmd = command?.let {
                if (it.length > 60) it.take(57) + "..." else it
            }
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_terminal),
                subtitle = shortCmd,
                icon = Icons.Default.Terminal
            )
        }
        "glob" -> {
            val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_find_files),
                subtitle = pattern,
                icon = Icons.Default.FolderOpen
            )
        }
        "grep" -> {
            val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_search_code),
                subtitle = pattern,
                icon = Icons.Default.Search
            )
        }
        "list", "listDirectory" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_list_directory),
                subtitle = filePath,
                icon = Icons.Default.Folder
            )
        }
        "webfetch" -> {
            val url = input["url"]?.jsonPrimitive?.contentOrNull
            val shortUrl = url?.let {
                try { java.net.URI(it).host } catch (_: Exception) { it.take(40) }
            }
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_fetch_url),
                subtitle = shortUrl,
                icon = Icons.Default.Language
            )
        }
        "task" -> {
            val description = input["description"]?.jsonPrimitive?.contentOrNull
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_sub_agent),
                subtitle = description,
                icon = Icons.Default.AccountTree
            )
        }
        "apply_patch" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_apply_patch),
                subtitle = shortPath,
                icon = Icons.Default.Compare
            )
        }
        else -> {
            ToolDisplayInfo(
                title = serverTitle ?: toolName,
                subtitle = null,
                icon = Icons.Default.Build
            )
        }
    }
}

// ============================================================================
// Tool-specific card renderers (matching WebUI tool registry)
// ============================================================================

/**
 * Extract common tool input values.
 */
private fun extractToolInput(tool: Part.Tool): Map<String, kotlinx.serialization.json.JsonElement> {
    return when (val state = tool.state) {
        is ToolState.Pending -> state.input
        is ToolState.Running -> state.input
        is ToolState.Completed -> state.input
        is ToolState.Error -> state.input
    }
}

private fun extractToolOutput(tool: Part.Tool): String {
    return when (val s = tool.state) {
        is ToolState.Completed -> s.output
        is ToolState.Error -> s.error
        else -> ""
    }
}

/**
 * Edit tool card — shows file path + diff with red/green colored lines.
 * Like WebUI: trigger = "Edit" + filename + DiffChanges, content = diff view.
 */
@Composable
private fun EditToolCard(tool: Part.Tool) {
    val input = extractToolInput(tool)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull ?: ""
    val shortPath = filePath.substringAfterLast('/')
    val dirPath = if (filePath.contains('/')) filePath.substringBeforeLast('/') else ""
    val oldString = input["oldString"]?.jsonPrimitive?.contentOrNull ?: ""
    val newString = input["newString"]?.jsonPrimitive?.contentOrNull ?: ""

    // Try to get filediff from metadata (full file before/after)
    val metadata = when (val s = tool.state) {
        is ToolState.Completed -> s.metadata
        is ToolState.Running -> s.metadata
        else -> null
    }
    val filediffBefore = metadata?.get("filediff")?.jsonObject?.get("before")?.jsonPrimitive?.contentOrNull
    val filediffAfter = metadata?.get("filediff")?.jsonObject?.get("after")?.jsonPrimitive?.contentOrNull

    val diffBefore = filediffBefore ?: oldString
    val diffAfter = filediffAfter ?: newString

    // Compute additions/deletions
    val addCount = diffAfter.lines().size - diffBefore.lines().let { if (diffBefore.isBlank()) 0 else it.size }
    val additions = if (addCount > 0) addCount else 0
    val deletions = if (addCount < 0) -addCount else 0

    var expanded by remember { mutableStateOf(false) }
    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = oldString.isNotBlank() || newString.isNotBlank()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasContent && !isRunning) mod.clickable { expanded = !expanded } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Error else Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chat_edit_label),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                        if (shortPath.isNotBlank()) {
                            Text(
                                text = shortPath,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // Diff stats + expand indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (additions > 0 || deletions > 0) {
                        DiffChangesInline(additions = additions, deletions = deletions)
                    }
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else if (hasContent) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // Expanded diff view
            AnimatedVisibility(visible = expanded && hasContent) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    if (isError) {
                        val errorText = (tool.state as ToolState.Error).error
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorText,
                                style = CodeTypography.copy(fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    } else {
                        DiffView(before = diffBefore, after = diffAfter)
                    }
                }
            }
        }
    }
}

/**
 * Inline diff change counts: +N -N with colors.
 */
@Composable
private fun DiffChangesInline(additions: Int, deletions: Int) {
    val addColor = Color(0xFF4CAF50)
    val delColor = Color(0xFFE53935)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (additions > 0) {
            Text(
                text = "+$additions",
                style = CodeTypography.copy(fontSize = 11.sp, color = addColor)
            )
        }
        if (deletions > 0) {
            Text(
                text = "-$deletions",
                style = CodeTypography.copy(fontSize = 11.sp, color = delColor)
            )
        }
    }
}

/**
 * Unified diff view — shows old lines in red, new lines in green.
 * Simple approach: compute line-level diff between before and after.
 */
@Composable
private fun DiffView(before: String, after: String) {
    val addColor = Color(0xFF4CAF50)
    val delColor = Color(0xFFE53935)
    val addBg = Color(0xFF4CAF50).copy(alpha = 0.1f)
    val delBg = Color(0xFFE53935).copy(alpha = 0.1f)

    // Simple diff: show removed lines, then added lines
    // For a proper diff we'd need a diff library, but line-level comparison works for edit tools
    val beforeLines = if (before.isBlank()) emptyList() else before.lines()
    val afterLines = if (after.isBlank()) emptyList() else after.lines()

    // Compute simple LCS-based diff
    val diffLines = remember(before, after) { computeSimpleDiff(beforeLines, afterLines) }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState())
                .padding(4.dp)
        ) {
            for (line in diffLines) {
                val (prefix, text, bgColor, fgColor) = when (line.type) {
                    DiffLineType.REMOVED -> DiffLineStyle("-", line.text, delBg, delColor)
                    DiffLineType.ADDED -> DiffLineStyle("+", line.text, addBg, addColor)
                    DiffLineType.UNCHANGED -> DiffLineStyle(" ", line.text, Color.Transparent, MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                ) {
                    Text(
                        text = "$prefix ",
                        style = CodeTypography.copy(fontSize = 13.sp, color = fgColor),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Text(
                        text = text,
                        style = CodeTypography.copy(fontSize = 13.sp, color = fgColor)
                    )
                }
            }
        }
    }
}

private data class DiffLineStyle(val prefix: String, val text: String, val bgColor: Color, val fgColor: Color)

private enum class DiffLineType { REMOVED, ADDED, UNCHANGED }
private data class DiffLine(val type: DiffLineType, val text: String)

/**
 * Simple diff algorithm: find common prefix/suffix lines, show removed and added lines in between.
 * Not a full LCS but good enough for typical edit tool changes.
 */
private fun computeSimpleDiff(before: List<String>, after: List<String>): List<DiffLine> {
    if (before.isEmpty() && after.isEmpty()) return emptyList()
    if (before.isEmpty()) return after.map { DiffLine(DiffLineType.ADDED, it) }
    if (after.isEmpty()) return before.map { DiffLine(DiffLineType.REMOVED, it) }

    // Find common prefix
    var commonPrefixLen = 0
    while (commonPrefixLen < before.size && commonPrefixLen < after.size &&
        before[commonPrefixLen] == after[commonPrefixLen]) {
        commonPrefixLen++
    }

    // Find common suffix (after prefix)
    var commonSuffixLen = 0
    while (commonSuffixLen < (before.size - commonPrefixLen) &&
        commonSuffixLen < (after.size - commonPrefixLen) &&
        before[before.size - 1 - commonSuffixLen] == after[after.size - 1 - commonSuffixLen]) {
        commonSuffixLen++
    }

    val result = mutableListOf<DiffLine>()

    // Show a few context lines from prefix (max 3)
    val contextLines = 3
    val prefixStart = (commonPrefixLen - contextLines).coerceAtLeast(0)
    for (i in prefixStart until commonPrefixLen) {
        result.add(DiffLine(DiffLineType.UNCHANGED, before[i]))
    }

    // Removed lines (from before, between prefix and suffix)
    for (i in commonPrefixLen until (before.size - commonSuffixLen)) {
        result.add(DiffLine(DiffLineType.REMOVED, before[i]))
    }

    // Added lines (from after, between prefix and suffix)
    for (i in commonPrefixLen until (after.size - commonSuffixLen)) {
        result.add(DiffLine(DiffLineType.ADDED, after[i]))
    }

    // Show a few context lines from suffix (max 3)
    val suffixEnd = commonSuffixLen.coerceAtMost(contextLines)
    for (i in 0 until suffixEnd) {
        result.add(DiffLine(DiffLineType.UNCHANGED, before[before.size - commonSuffixLen + i]))
    }

    return result
}

/**
 * Write tool card — shows file path + code content.
 * Like WebUI: trigger = "Write" + filename, content = code view.
 */
@Composable
private fun WriteToolCard(tool: Part.Tool) {
    val input = extractToolInput(tool)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val shortPath = filePath.substringAfterLast('/')
    val content = input["content"]?.jsonPrimitive?.contentOrNull ?: ""

    var expanded by remember { mutableStateOf(false) }
    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = content.isNotBlank()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasContent && !isRunning) mod.clickable { expanded = !expanded } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Error else Icons.Default.EditNote,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chat_write_label),
                            style = MaterialTheme.typography.labelMedium
                        )
                        if (shortPath.isNotBlank()) {
                            Text(
                                text = shortPath,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.tertiary)
                } else if (hasContent) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            AnimatedVisibility(visible = expanded && hasContent) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 400.dp)
                ) {
                    Text(
                        text = content.take(5000),
                        style = CodeTypography.copy(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer),
                        modifier = Modifier
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState())
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

/**
 * Bash tool card — shows $ command + output.
 * Like WebUI: trigger = "Shell" + description, content = code block with command+output.
 */
@Composable
private fun BashToolCard(tool: Part.Tool) {
    val input = extractToolInput(tool)
    val command = input["command"]?.jsonPrimitive?.contentOrNull ?: ""
    val description = input["description"]?.jsonPrimitive?.contentOrNull
    val output = extractToolOutput(tool)

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    var expanded by remember { mutableStateOf(false) }
    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = command.isNotBlank() || output.isNotBlank()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasContent && !isRunning) mod.clickable { expanded = !expanded } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Error else Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = serverTitle ?: stringResource(R.string.tool_shell),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (description != null) {
                            Text(
                                text = description,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.tertiary)
                } else if (hasContent) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            AnimatedVisibility(visible = expanded && hasContent) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 400.dp)
                ) {
                        val displayText = buildString {
                        if (command.isNotBlank()) {
                            append("$ $command")
                        }
                        if (output.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                        // Strip ANSI escape codes
                            append(output.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").take(5000))
                        }
                    }
                    Text(
                        text = displayText,
                        style = CodeTypography.copy(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer),
                        modifier = Modifier
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState())
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

/**
 * Read tool card — shows file path only, no expandable content (like WebUI).
 */
@Composable
private fun ReadToolCard(tool: Part.Tool) {
    val input = extractToolInput(tool)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val shortPath = filePath.substringAfterLast('/')
    val offset = input["offset"]?.jsonPrimitive?.contentOrNull
    val limit = input["limit"]?.jsonPrimitive?.contentOrNull

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error

    // Build args string like WebUI: [offset=N, limit=N]
    val args = buildList {
        offset?.let { add("offset=$it") }
        limit?.let { add("limit=$it") }
    }.takeIf { it.isNotEmpty() }?.joinToString(", ", "[", "]")

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isError) Icons.Default.Error else Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = serverTitle ?: stringResource(R.string.tool_read),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (shortPath.isNotBlank()) {
                            Text(
                                text = shortPath,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (args != null) {
                            Text(
                                text = args,
                                style = CodeTypography.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            if (isRunning) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

/**
 * Search tool card (glob/grep) — shows pattern + expandable output.
 * Like WebUI: trigger = "Glob"/"Grep" + directory + [pattern=...], content = markdown output.
 */
@Composable
private fun SearchToolCard(tool: Part.Tool) {
    val input = extractToolInput(tool)
    val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull
    val include = input["include"]?.jsonPrimitive?.contentOrNull
    val dirPath = input["path"]?.jsonPrimitive?.contentOrNull
    val output = extractToolOutput(tool)

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val title = when (tool.tool) {
        "glob" -> serverTitle ?: stringResource(R.string.tool_find_files)
        "grep" -> serverTitle ?: stringResource(R.string.tool_search_code)
        else -> serverTitle ?: tool.tool
    }

    // Build args display
    val argsText = buildList {
        pattern?.let { add("pattern=$it") }
        include?.let { add("include=$it") }
    }.takeIf { it.isNotEmpty() }?.joinToString(", ", "[", "]")

    var expanded by remember { mutableStateOf(false) }
    val isRunning = tool.state is ToolState.Running
    val hasOutput = output.isNotBlank()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasOutput && !isRunning) mod.clickable { expanded = !expanded } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (dirPath != null) {
                                Text(
                                    text = dirPath.substringAfterLast('/').ifEmpty { dirPath },
                                    style = CodeTypography.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (argsText != null) {
                                Text(
                                    text = argsText,
                                    style = CodeTypography.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.tertiary)
                } else if (hasOutput) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            AnimatedVisibility(visible = expanded && hasOutput) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 300.dp)
                ) {
                    Text(
                        text = output.take(5000),
                        style = CodeTypography.copy(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer),
                        modifier = Modifier
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState())
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

/**
 * Task (sub-agent) tool card — shows description + child info.
 * Like WebUI: trigger = "Agent (task)" + description, content = child tool list.
 */
@Composable
private fun TaskToolCard(tool: Part.Tool) {
    val input = extractToolInput(tool)
    val description = input["description"]?.jsonPrimitive?.contentOrNull
    val output = extractToolOutput(tool)

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    var expanded by remember { mutableStateOf(false) }
    val isRunning = tool.state is ToolState.Running
    val hasOutput = output.isNotBlank()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasOutput && !isRunning) mod.clickable { expanded = !expanded } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = serverTitle ?: stringResource(R.string.tool_sub_agent),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                        if (description != null) {
                            Text(
                                text = description,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.tertiary)
                } else if (hasOutput) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            AnimatedVisibility(visible = expanded && hasOutput) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 300.dp)
                ) {
                    Text(
                        text = output.take(5000),
                        style = CodeTypography.copy(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer),
                        modifier = Modifier
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState())
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
@Composable
private fun TodoListCard(tool: Part.Tool) {
    // Extract todos from metadata first, then fall back to input
    val todos = remember(tool) {
        val source = when (val state = tool.state) {
            is ToolState.Completed -> state.metadata?.get("todos") ?: state.input["todos"]
            is ToolState.Running -> state.metadata?.get("todos") ?: state.input["todos"]
            is ToolState.Pending -> state.input["todos"]
            is ToolState.Error -> state.metadata?.get("todos") ?: state.input["todos"]
        }
        if (source != null) {
            try {
                source.jsonArray.mapNotNull { element ->
                    try {
                        val obj = element.jsonObject
                        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
                        val priority = obj["priority"]?.jsonPrimitive?.contentOrNull ?: "medium"
                        TodoItem(content = content, status = status, priority = priority)
                    } catch (_: Exception) { null }
                }
            } catch (_: Exception) { emptyList() }
        } else {
            emptyList()
        }
    }

    if (todos.isEmpty()) {
        // Fallback to generic tool card if we can't parse todos
        ToolCallCard(tool = tool)
        return
    }

    val completedCount = todos.count { it.status == "completed" }
    val totalCount = todos.size
    var expanded by remember { mutableStateOf(true) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Checklist,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (completedCount == totalCount) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = stringResource(R.string.chat_tasks_label),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$completedCount/$totalCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            // Todo items
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (todo in todos) {
                        TodoItemRow(todo = todo)
                    }
                }
            }
        }
    }
}

private data class TodoItem(
    val content: String,
    val status: String,
    val priority: String
)

@Composable
private fun TodoItemRow(todo: TodoItem) {
    val isCompleted = todo.status == "completed"
    val isInProgress = todo.status == "in_progress"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isCompleted,
            onCheckedChange = null,
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = if (isInProgress) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
        )
        Text(
            text = todo.content,
            style = MaterialTheme.typography.bodySmall.copy(
                color = if (isCompleted) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StepFinishInfo(step: Part.StepFinish) {
    if (step.tokens != null || step.cost != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            step.tokens?.let { tokens ->
                Text(
                    text = stringResource(R.string.chat_tokens_format, tokens.input, tokens.output),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
            step.cost?.let { cost ->
                Text(
                    text = stringResource(R.string.chat_cost_format, String.format("%.4f", cost)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

/**
 * Compute additions/deletions from a unified diff string by counting lines starting with +/-.
 * Skips the --- and +++ header lines.
 */
private fun parseDiffCounts(diff: String?): Pair<Int, Int> {
    if (diff.isNullOrBlank()) return 0 to 0
    var additions = 0
    var deletions = 0
    for (line in diff.lineSequence()) {
        when {
            line.startsWith("+++") || line.startsWith("---") -> { /* skip header */ }
            line.startsWith("+") -> additions++
            line.startsWith("-") -> deletions++
        }
    }
    return additions to deletions
}

@Composable
private fun PatchCard(patch: Part.Patch) {
    // Compute additions/deletions from diff text for each file
    val fileDiffCounts = remember(patch.files) {
        patch.files.map { file -> file to parseDiffCounts(file.diff) }
    }
    val totalAdditions = fileDiffCounts.sumOf { (_, counts) -> counts.first }
    val totalDeletions = fileDiffCounts.sumOf { (_, counts) -> counts.second }

    var expanded by remember { mutableStateOf(false) }

    val addColor = Color(0xFF4CAF50)
    val delColor = Color(0xFFE53935)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row with total stats
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (patch.files.size == 1) 
                            stringResource(R.string.chat_files_changed, patch.files.size)
                        else 
                            stringResource(R.string.chat_files_changed_plural, patch.files.size),
                        style = MaterialTheme.typography.labelMedium
                    )
                    // +N/-N summary
                    if (totalAdditions > 0 || totalDeletions > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (totalAdditions > 0) {
                                Text(
                                    text = "+$totalAdditions",
                                    style = CodeTypography.copy(fontSize = 11.sp, color = addColor),
                                )
                            }
                            if (totalDeletions > 0) {
                                Text(
                                    text = "-$totalDeletions",
                                    style = CodeTypography.copy(fontSize = 11.sp, color = delColor),
                                )
                            }
                        }
                    }
                    // Mini color bar (5 blocks like WebUI)
                    if (totalAdditions > 0 || totalDeletions > 0) {
                        DiffColorBar(
                            additions = totalAdditions,
                            deletions = totalDeletions,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            // Expanded file list
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for ((file, counts) in fileDiffCounts) {
                        PatchFileRow(
                            file = file,
                            additions = counts.first,
                            deletions = counts.second,
                            addColor = addColor,
                            delColor = delColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * 5-block color bar mimicking the WebUI DiffChanges "bars" variant.
 * Green blocks for additions, red for deletions, gray for neutral.
 */
@Composable
private fun DiffColorBar(
    additions: Int,
    deletions: Int,
    modifier: Modifier = Modifier
) {
    val total = additions + deletions
    if (total == 0) return

    val addColor = Color(0xFF4CAF50)
    val delColor = Color(0xFFE53935)
    val neutralColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    // Calculate proportional blocks (5 total, at least 1 each if non-zero)
    val addBlocks: Int
    val delBlocks: Int
    if (additions > 0 && deletions > 0) {
        val addRatio = additions.toFloat() / total
        addBlocks = (addRatio * 5).toInt().coerceIn(1, 4)
        delBlocks = (5 - addBlocks).coerceIn(1, 4)
    } else if (additions > 0) {
        addBlocks = 5
        delBlocks = 0
    } else {
        addBlocks = 0
        delBlocks = 5
    }
    val neutralBlocks = 5 - addBlocks - delBlocks

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(addBlocks) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 10.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(addColor)
            )
        }
        repeat(delBlocks) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 10.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(delColor)
            )
        }
        repeat(neutralBlocks) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 10.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(neutralColor)
            )
        }
    }
}

/**
 * A single file row in the expanded patch card.
 * Shows action label + file path + +N/-N counts.
 */
@Composable
private fun PatchFileRow(
    file: Part.Patch.FilePatch,
    additions: Int,
    deletions: Int,
    addColor: Color,
    delColor: Color
) {
    var showDiff by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { mod ->
                    if (!file.diff.isNullOrBlank()) mod.clickable { showDiff = !showDiff } else mod
                }
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Action label
            val (actionLabel, actionColor) = when (file.type) {
                "add" -> stringResource(R.string.patch_created) to addColor
                "delete" -> stringResource(R.string.patch_deleted) to delColor
                "move" -> stringResource(R.string.patch_moved) to Color(0xFFFFA726)
                else -> stringResource(R.string.patch_patched) to MaterialTheme.colorScheme.onSurface
            }
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelSmall.copy(color = actionColor),
                modifier = Modifier.widthIn(min = 48.dp)
            )

            // File path
            Text(
                text = file.path.substringAfterLast('/'),
                style = CodeTypography.copy(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // +N/-N per file
            if (file.type != "delete") {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (additions > 0) {
                        Text(
                            text = "+$additions",
                            style = CodeTypography.copy(fontSize = 10.sp, color = addColor)
                        )
                    }
                    if (deletions > 0) {
                        Text(
                            text = "-$deletions",
                            style = CodeTypography.copy(fontSize = 10.sp, color = delColor)
                        )
                    }
                }
            } else if (deletions > 0) {
                Text(
                    text = "-$deletions",
                    style = CodeTypography.copy(fontSize = 10.sp, color = delColor)
                )
            }

            // Expand indicator if diff is available
            if (!file.diff.isNullOrBlank()) {
                Icon(
                    imageVector = if (showDiff) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
        }

        // Expandable diff content
        AnimatedVisibility(visible = showDiff && !file.diff.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, bottom = 4.dp)
                    .heightIn(max = 300.dp)
            ) {
                Text(
                    text = file.diff ?: "",
                    style = CodeTypography.copy(
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier
                        .padding(6.dp)
                        .horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}

/**
 * Compact horizontal row of image thumbnails with tap-to-preview.
 */
@Composable
private fun ImageThumbnailRow(imageFiles: List<Part.File>) {
    var previewIndex by remember { mutableStateOf(-1) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for ((index, file) in imageFiles.withIndex()) {
            val bitmap = remember(file.url) {
                try {
                    val url = file.url ?: return@remember null
                    val base64Data = if (url.contains(",")) url.substringAfter(",") else url
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    Log.e("FileCard", "Failed to decode image: ${e.message}")
                    null
                }
            }

            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = file.filename ?: stringResource(R.string.chat_image),
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { previewIndex = index },
                    contentScale = ContentScale.Crop
                )
            } else {
                // Fallback placeholder for failed decode
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }

    // Fullscreen image preview dialog
    if (previewIndex >= 0 && previewIndex < imageFiles.size) {
        val file = imageFiles[previewIndex]
        val bitmap = remember(file.url) {
            try {
                val url = file.url ?: return@remember null
                val base64Data = if (url.contains(",")) url.substringAfter(",") else url
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }

        if (bitmap != null) {
            AlertDialog(
                onDismissRequest = { previewIndex = -1 },
                confirmButton = {
                    TextButton(onClick = { previewIndex = -1 }) {
                        Text(stringResource(R.string.close))
                    }
                },
                text = {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                    contentDescription = file.filename ?: stringResource(R.string.chat_image),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            )
        }
    }
}

@Composable
private fun FileCard(file: Part.File) {
    // Images are handled by ImageThumbnailRow, so FileCard only handles non-image files
    FileCardFallback(file)
}

@Composable
private fun FileCardFallback(file: Part.File) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AttachFile,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = file.filename ?: file.mime,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PermissionCard(
    permission: SseEvent.PermissionAsked,
    onOnce: () -> Unit,
    onAlways: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = stringResource(R.string.permission_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Text(
                text = permission.permission,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            if (permission.patterns.isNotEmpty()) {
                Text(
                    text = permission.patterns.joinToString(", "),
                    style = CodeTypography.copy(
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.permission_deny), maxLines = 1)
                }
                OutlinedButton(
                    onClick = onOnce,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.permission_allow_once), maxLines = 1)
                }
                Button(
                    onClick = onAlways,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.permission_allow_always), maxLines = 1)
                }
            }
        }
    }
}

/** Rotating placeholder hints for the input bar, similar to the WebUI prompt input. */
private val placeholderHintResIds = listOf(
    R.string.chat_hint_ask,
    R.string.chat_hint_fix,
    R.string.chat_hint_refactor,
    R.string.chat_hint_tests,
    R.string.chat_hint_explain,
    R.string.chat_hint_help,
)

@Composable
private fun ChatInputBar(
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    isBusy: Boolean = false,
    messages: List<ChatMessage> = emptyList(),
    attachments: List<ImageAttachment> = emptyList(),
    onAttach: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    modelLabel: String = "",
    onModelClick: () -> Unit = {},
    agents: List<AgentInfo> = emptyList(),
    selectedAgent: String = "build",
    onAgentSelect: (String) -> Unit = {},
    variantNames: List<String> = emptyList(),
    selectedVariant: String? = null,
    onCycleVariant: () -> Unit = {},
    commands: List<CommandInfo> = emptyList(),
    fileSearchResults: List<String> = emptyList(),
    confirmedFilePaths: Set<String> = emptySet(),
    onFileSelected: (String) -> Unit = {},
    onSlashCommand: (SlashCommand) -> Unit = {}
) {
    // Rotate placeholder hint every 4 seconds
    val hintIndex = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(4000)
            hintIndex.intValue = (hintIndex.intValue + 1) % placeholderHintResIds.size
        }
    }
    val placeholder = stringResource(placeholderHintResIds[hintIndex.intValue])

    val text = textFieldValue.text
    val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && !isSending

    // Build merged slash commands: client commands + server commands (deduplicated)
    val clientCmds = clientCommands()
    val allCommands = remember(commands, clientCmds) {
        val clientNames = clientCmds.map { it.name }.toSet()
        val serverSlash = commands
            .filter { it.source != "skill" && it.name !in clientNames }
            .map { SlashCommand(it.name, it.description, "server") }
        clientCmds + serverSlash
    }

    // Slash command suggestions
    val showSlashSuggestions = text.startsWith("/") && !text.contains(" ")
    val slashQuery = if (showSlashSuggestions) text.removePrefix("/").lowercase() else ""
    val filteredCommands = if (showSlashSuggestions) {
        allCommands.filter { cmd ->
            slashQuery.isEmpty() || cmd.name.lowercase().contains(slashQuery)
        }
    } else emptyList()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Thin divider
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 0.5.dp
        )

        // Slash command suggestions popup (scrollable, max 40% screen height)
        AnimatedVisibility(
            visible = showSlashSuggestions && filteredCommands.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val configuration = LocalConfiguration.current
            val maxHeight = (configuration.screenHeightDp * 0.4f).dp

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(vertical = 4.dp)
            ) {
                items(filteredCommands, key = { it.name }) { cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onTextFieldValueChange(TextFieldValue(""))
                                onSlashCommand(cmd)
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "/${cmd.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                        if (cmd.description != null) {
                            Text(
                                text = cmd.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // @ file mention suggestions popup
        AnimatedVisibility(
            visible = fileSearchResults.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val configuration = LocalConfiguration.current
            val maxHeight = (configuration.screenHeightDp * 0.4f).dp

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(vertical = 4.dp)
            ) {
                items(
                    fileSearchResults.take(10),
                    key = { it }
                ) { path ->
                    val isDir = path.endsWith("/")
                    // Split into directory part + filename for display
                    val displayPath = if (isDir) path.trimEnd('/') else path
                    val lastSlash = displayPath.lastIndexOf('/')
                    val dirPart = if (lastSlash >= 0) displayPath.substring(0, lastSlash + 1) else ""
                    val namePart = if (lastSlash >= 0) displayPath.substring(lastSlash + 1) else displayPath

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFileSelected(path) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isDir) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isDir)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = buildAnnotatedString {
                                if (dirPart.isNotEmpty()) {
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))) {
                                        append(dirPart)
                                    }
                                }
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                                    append(namePart)
                                }
                                if (isDir) {
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))) {
                                        append("/")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Working status indicator — inline, subtle
        if (isBusy) {
            val lastRunningTool = messages.lastOrNull()?.parts
                ?.filterIsInstance<Part.Tool>()
                ?.lastOrNull { it.state is ToolState.Running }

            val statusText = if (lastRunningTool != null) {
                val title = (lastRunningTool.state as ToolState.Running).title
                when (lastRunningTool.tool) {
                    "read" -> title ?: stringResource(R.string.chat_tool_reading_file)
                    "write" -> title ?: stringResource(R.string.chat_tool_writing_file)
                    "edit" -> title ?: stringResource(R.string.chat_tool_editing_file)
                    "bash" -> title ?: stringResource(R.string.chat_tool_running_command)
                    "glob", "list" -> title ?: stringResource(R.string.chat_tool_searching_files)
                    "grep" -> title ?: stringResource(R.string.chat_tool_searching_code)
                    "webfetch" -> title ?: stringResource(R.string.chat_tool_fetching_url)
                    "task" -> title ?: stringResource(R.string.chat_tool_running_subagent)
                    "todowrite" -> title ?: stringResource(R.string.chat_tool_updating_tasks)
                    else -> title ?: stringResource(R.string.chat_tool_running_tool, lastRunningTool.tool)
                }
            } else {
                stringResource(R.string.chat_tool_thinking)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Agent + Model + Variant + Attach selector row — small, subtle
            if (modelLabel.isNotEmpty() || agents.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Scrollable area for agent/model/variant so paperclip always stays visible
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Agent selector (build/plan toggle) — FIRST
                        if (agents.size > 1) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                agents.forEach { agent ->
                                    val isActive = agent.name == selectedAgent
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .then(
                                                if (isActive) Modifier.background(
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                ) else Modifier
                                            )
                                            .clickable { onAgentSelect(agent.name) }
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = agent.name.replaceFirstChar { it.uppercase() },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isActive) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Model selector — SECOND
                        if (modelLabel.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onModelClick() }
                                    .padding(horizontal = 3.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = modelLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Icon(
                                    Icons.Default.UnfoldMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }

                        // Variant cycle button (thinking effort) — THIRD
                        if (variantNames.isNotEmpty()) {
                            Text(
                                text = selectedVariant?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.chat_default_variant),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selectedVariant != null) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onCycleVariant() }
                                    .padding(horizontal = 3.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // Attach button (paperclip) — always visible, pinned right
                    IconButton(
                        onClick = onAttach,
                        modifier = Modifier
                            .size(32.dp)
                            .padding(end = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = stringResource(R.string.chat_attach),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Image attachment thumbnails
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(attachments.size) { index ->
                        val attachment = attachments[index]
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                        ) {
                            AsyncImage(
                                model = attachment.uri,
                                contentDescription = attachment.filename,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(18.dp)
                                    .clickable { onRemoveAttachment(index) },
                                shape = RoundedCornerShape(9.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.chat_remove),
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onError
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Input row
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Text field — minimal style, no heavy outline
                val mentionHighlightColor = MaterialTheme.colorScheme.primary
                val mentionBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                val visualTransformation = remember(confirmedFilePaths, mentionHighlightColor, mentionBgColor) {
                    FileMentionVisualTransformation(confirmedFilePaths, mentionHighlightColor, mentionBgColor)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = onTextFieldValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 24.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        maxLines = 5,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = visualTransformation,
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                // Send button — icon only, compact
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.size(44.dp)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send),
                            modifier = Modifier.size(20.dp),
                            tint = if (canSend) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card that displays a pending question from the server.
 *
 * Single-select: each option is an OutlinedButton that immediately submits.
 * Multi-select: checkboxes + Submit button.
 * "Type your own answer" expands an inline text field.
 */
@Composable
private fun QuestionCard(
    question: SseEvent.QuestionAsked,
    onSubmit: (answers: List<List<String>>) -> Unit,
    onReject: () -> Unit
) {
    val isSingle = question.questions.size == 1 && question.questions[0].multiple != true

    // Prevent multiple submissions
    var submitted by remember { mutableStateOf(false) }

    // Track answers per question
    val answersPerQuestion = remember {
        mutableStateListOf<List<String>>().apply {
            repeat(question.questions.size) { add(emptyList()) }
        }
    }

    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header row — matches PermissionCard style
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    @Suppress("DEPRECATION")
                    Icons.Default.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = accentColor
                )
                Text(
                    text = stringResource(R.string.chat_question_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor
                )
            }

            // Question sections
            question.questions.forEachIndexed { index, q ->
                if (q.header.isNotBlank()) {
                    Text(
                        text = q.header,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor
                    )
                }
                Text(
                    text = q.question,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f)
                )

                Spacer(Modifier.height(2.dp))

                if (q.multiple) {
                    // ── Multi-select: checkboxes ──
                    val selectedLabels = remember { mutableStateListOf<String>() }

                    q.options.forEach { option ->
                        val checked = option.label in selectedLabels
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (checked) accentColor.copy(alpha = 0.12f)
                                    else Color.Transparent
                                )
                                .toggleable(
                                    value = checked,
                                    enabled = !submitted,
                                    role = Role.Checkbox,
                                    onValueChange = {
                                        if (it) selectedLabels.add(option.label) else selectedLabels.remove(option.label)
                                        if (index < answersPerQuestion.size) {
                                            answersPerQuestion[index] = selectedLabels.toList()
                                        }
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = accentColor,
                                    uncheckedColor = contentColor.copy(alpha = 0.5f)
                                )
                            )
                            Column {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor
                                )
                                if (option.description.isNotBlank()) {
                                    Text(
                                        text = option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = contentColor.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ── Single-select: tappable option rows ──
                    q.options.forEach { option ->
                        val isSelected = index < answersPerQuestion.size && option.label in answersPerQuestion[index]
                        Surface(
                            onClick = {
                                if (!submitted) {
                                    if (isSingle) {
                                        submitted = true
                                        onSubmit(listOf(listOf(option.label)))
                                    } else {
                                        if (index < answersPerQuestion.size) {
                                            answersPerQuestion[index] = listOf(option.label)
                                        }
                                    }
                                }
                            },
                            enabled = !submitted,
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) accentColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isSelected) accentColor else accentColor.copy(alpha = 0.7f)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelected) accentColor else contentColor
                                    )
                                    if (option.description.isNotBlank()) {
                                        Text(
                                            text = option.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = contentColor.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // "Type your own answer" — inline text field
                if (q.custom != false) {
                    val currentAnswers = if (index < answersPerQuestion.size) answersPerQuestion[index] else emptyList()
                    val customAnswer = currentAnswers.firstOrNull { ans -> q.options.none { it.label == ans } }
                    
                    if (customAnswer != null) {
                        // Show selected custom answer
                         Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = accentColor.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.RadioButtonChecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = accentColor
                                )
                                Text(
                                    text = customAnswer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = accentColor,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        if (!submitted && index < answersPerQuestion.size) {
                                            answersPerQuestion[index] = emptyList()
                                        }
                                    },
                                    enabled = !submitted,
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.chat_clear),
                                        modifier = Modifier.size(16.dp),
                                        tint = accentColor.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    } else {
                        var isEditingCustom by remember { mutableStateOf(false) }
                        var customText by remember { mutableStateOf("") }

                        if (!isEditingCustom) {
                            Surface(
                                onClick = {
                                    isEditingCustom = true
                                },
                                enabled = !submitted,
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = accentColor.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        text = stringResource(R.string.question_custom_answer),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = accentColor.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = customText,
                                onValueChange = { customText = it },
                                enabled = !submitted,
                                placeholder = {
                                    Text(
                                        stringResource(R.string.chat_type_answer),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                                shape = RoundedCornerShape(8.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                trailingIcon = {
                                    Row {
                                        IconButton(
                                            onClick = {
                                                val trimmed = customText.trim()
                                                if (trimmed.isNotBlank()) {
                                                    if (isSingle) {
                                                        submitted = true
                                                        onSubmit(listOf(listOf(trimmed)))
                                                    } else {
                                                        if (index < answersPerQuestion.size) {
                                                            answersPerQuestion[index] = listOf(trimmed)
                                                        }
                                                        isEditingCustom = false
                                                        customText = "" 
                                                    }
                                                }
                                            },
                                            enabled = customText.isNotBlank() && !submitted
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Send,
                                                contentDescription = stringResource(R.string.question_submit),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(onClick = { isEditingCustom = false; customText = "" }) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.question_cancel),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Bottom actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(
                    onClick = {
                        submitted = true
                        onReject()
                    },
                    enabled = !submitted
                ) {
                    Text(stringResource(R.string.chat_dismiss), style = MaterialTheme.typography.labelMedium)
                }
                if (!isSingle) {
                    Button(
                        onClick = {
                            submitted = true
                            onSubmit(answersPerQuestion.map { it.toList() })
                        },
                        enabled = answersPerQuestion.any { it.isNotEmpty() } && !submitted,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.question_submit), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
