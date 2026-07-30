package dev.minios.ocremote.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.minios.ocremote.service.SessionNotificationCoordinator
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.model.DefaultMarkdownAnnotator
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.MarkdownTokenTypes
import dev.minios.ocremote.domain.model.*
import dev.minios.ocremote.data.api.AgentInfo
import dev.minios.ocremote.data.api.CommandInfo
import dev.minios.ocremote.data.api.PromptPart
import dev.minios.ocremote.data.api.ProviderInfo
import dev.minios.ocremote.data.api.ProviderModel
import dev.minios.ocremote.MainActivity
import dev.minios.ocremote.ui.theme.CodeTypography
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import java.util.Locale
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt
import kotlin.math.abs

import android.net.Uri
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.provider.OpenableColumns
import android.os.Build
import android.util.Base64
import dev.minios.ocremote.logging.AppLogger as Log
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.minios.ocremote.BuildConfig
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.ProviderIcon
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.ui.components.AppLoadingEdge
import dev.minios.ocremote.ui.components.AppPickerItemShape
import dev.minios.ocremote.ui.components.AppPrimaryButton
import dev.minios.ocremote.ui.components.AppSecondaryButton
import dev.minios.ocremote.ui.components.appAmoledBorder
import dev.minios.ocremote.ui.components.appSelectedItemColor
import dev.minios.ocremote.ui.components.appPopupBorder
import dev.minios.ocremote.ui.components.appPopupContainerColor
import dev.minios.ocremote.ui.components.isAmoledTheme


/**
 * Chat Screen - conversation view with native markdown rendering.
 * Shows messages with streaming text rendered via mikepenz markdown renderer.
 */

// ============ Chat Settings via CompositionLocal ============

/** Chat font size setting: "small", "medium", "large". */
val LocalChatFontSize = compositionLocalOf { "medium" }

/** Whether code blocks use word wrap instead of horizontal scroll. */
val LocalCodeWordWrap = compositionLocalOf { false }

/** Whether compact message spacing is enabled. */
val LocalCompactMessages = compositionLocalOf { false }

/** Whether tool cards are collapsed by default. */
val LocalCollapseTools = compositionLocalOf { false }

val LocalExpandReasoning = compositionLocalOf { false }

val LocalShowTurnDividers = compositionLocalOf { true }

/** Whether haptic feedback is enabled. */
val LocalHapticFeedbackEnabled = compositionLocalOf { true }

/** Image save request callback available to image preview composables. */
val LocalImageSaveRequest = compositionLocalOf<(ByteArray, String, String?) -> Unit> { { _, _, _ -> } }

@Composable
private fun toolOutputContainerColor(isAmoled: Boolean): Color {
    return when {
        isAmoled -> Color.Black
        isSystemInDarkTheme() -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.82f)
    }
}

@Composable
private fun Modifier.expandableToolHeader(
    expanded: Boolean,
    onClick: () -> Unit,
): Modifier {
    val state = stringResource(if (expanded) R.string.chat_collapse else R.string.chat_expand)
    return semantics { stateDescription = state }
        .clickable(role = Role.Button, onClick = onClick)
}

/**
 * Perform a light haptic tick if haptic feedback is enabled.
 * Call from composable context or from a click lambda that has access to a View.
 */
@Suppress("DEPRECATION")
private fun performHaptic(view: android.view.View, enabled: Boolean) {
    if (enabled) {
        view.performHapticFeedback(
            android.view.HapticFeedbackConstants.CLOCK_TICK,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }
}

/**
 * Agent color matching the TUI's opencode theme.
 * Color cycle: secondary, accent, success, warning, primary, error, info
 * (same order as TUI's local.tsx color array).
 */
private val agentColorCycle = listOf(
    Color(0xFF5C9CF5), // secondary — build (blue)
    Color(0xFF9D7CD8), // accent — plan (purple)
    Color(0xFF7FD88F), // success (green)
    Color(0xFFF5A742), // warning (orange)
    Color(0xFFFAB283), // primary (peach)
    Color(0xFFE06C75), // error (red)
    Color(0xFF56B6C2)  // info (cyan)
)

private fun agentColor(agentName: String, agents: List<AgentInfo> = emptyList()): Color {
    val index = agents.indexOfFirst { it.name == agentName }
    return if (index >= 0) {
        agentColorCycle[index % agentColorCycle.size]
    } else {
        agentColorCycle[0]
    }
}

/**
 * Conditionally applies horizontalScroll for code blocks.
 * When word wrap is enabled, no horizontal scroll is applied.
 */
@Composable
private fun Modifier.codeHorizontalScroll(): Modifier {
    return if (!LocalCodeWordWrap.current) {
        this.horizontalScroll(rememberScrollState())
    } else {
        this
    }
}

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

private enum class ChatInputMode {
    NORMAL,
    SHELL
}

/** Client-side slash commands that mirror the original opencode TUI. */
@Composable
private fun clientCommands(): List<SlashCommand> {
    return listOf(
        SlashCommand("new", stringResource(R.string.cmd_new), "client"),
        SlashCommand("compact", stringResource(R.string.cmd_compact), "client"),
        SlashCommand("fork", stringResource(R.string.cmd_fork), "client"),
        SlashCommand("share", stringResource(R.string.cmd_share), "client"),
        SlashCommand("unshare", stringResource(R.string.cmd_unshare), "client"),
        SlashCommand("undo", stringResource(R.string.cmd_undo), "client"),
        SlashCommand("redo", stringResource(R.string.cmd_redo), "client"),
        SlashCommand("rename", stringResource(R.string.cmd_rename), "client"),
        SlashCommand("shell", stringResource(R.string.cmd_shell_mode), "client"),
    )
}

/** Pulsing dots loading indicator — 3 dots that scale up/down in sequence. */
@Composable
private fun PulsingDotsIndicator(
    modifier: Modifier = Modifier,
    dotSize: Dp = 10.dp,
    dotSpacing: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "pulsing_dots")
    val scales = (0..2).map { index ->
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1200
                    0.4f at 0
                    1.0f at 300
                    0.4f at 600
                    0.4f at 1200
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "dot_$index"
        )
    }
    // Stagger: shift each dot's time by reading at offset phase
    val phaseShift = 150 // ms between dots
    val scales2 = (0..2).map { index ->
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1200
                    val offset = index * phaseShift
                    0.4f at 0 + offset
                    1.0f at 300 + offset
                    0.4f at 600 + offset
                    0.4f at 1200
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "dot_scale_$index"
        )
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        scales2.forEach { scale ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        alpha = 0.3f + 0.7f * ((scale.value - 0.4f) / 0.6f)
                    }
                    .background(color, CircleShape)
            )
        }
    }
}

/** Breathing circle loading indicator — single circle that pulses smoothly. */
@Composable
private fun BreathingCircleIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "breathing_circle")
    val scale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "circle_scale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "circle_alpha"
    )
    
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .background(color, CircleShape)
        )
    }
}

/** Format a token count to a human-readable string (e.g., 1.2k, 45.3k, 1.2M). */
private fun formatTokenCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fk", count / 1_000.0)
        else -> count.toString()
    }
}

private fun formatAssistantErrorMessage(error: Message.Assistant.ErrorInfo?): String? {
    if (error == null) return null
    val raw = error.message.ifBlank { error.name }
    return raw.ifBlank { null }
}

private enum class HtmlErrorViewMode {
    Page,
    Code,
}

@Composable
private fun ErrorPayloadContent(
    text: String,
    textStyle: TextStyle,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    if (!looksLikeHtmlPayload(text)) {
        SelectionContainer {
            Text(
                text = text,
                style = textStyle,
                color = textColor,
                modifier = modifier,
            )
        }
        return
    }

    var mode by rememberSaveable(text) { mutableStateOf(HtmlErrorViewMode.Code) }
    val htmlForPreview = remember(text) { normalizeHtmlForEmbeddedPreview(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == HtmlErrorViewMode.Code,
                onClick = { mode = HtmlErrorViewMode.Code },
                label = { Text(stringResource(R.string.chat_error_view_code)) },
            )
            FilterChip(
                selected = mode == HtmlErrorViewMode.Page,
                onClick = { mode = HtmlErrorViewMode.Page },
                label = { Text(stringResource(R.string.chat_error_view_page)) },
            )
        }

        if (mode == HtmlErrorViewMode.Page) {
            val isAmoled = isAmoledTheme()
            val bgColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.textZoom = 85
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        webViewClient = WebViewClient()
                        setOnTouchListener { v, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            false
                        }
                        setBackgroundColor(bgColor.toArgb())
                    }
                },
                update = { webView ->
                    if (webView.tag != htmlForPreview) {
                        webView.tag = htmlForPreview
                        webView.loadDataWithBaseURL(
                            "https://localhost/",
                            htmlForPreview,
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 360.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            SelectionContainer {
                Text(
                    text = text,
                    style = textStyle,
                    color = textColor,
                )
            }
        }
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
        val displayName = mention.path.trimEnd('/').substringAfterLast('/')
        parts.add(
            PromptPart(
                type = "file",
                path = mention.path,
                mime = if (isDir) "application/x-directory" else "text/plain",
                url = "file:///$absPath",
                filename = displayName
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
    val dataUrl: String, // "data:<mime>;base64,..."
    val sizeBytes: Int = 0,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

internal enum class LocalAttachmentValidation { ACCEPTED, UNSUPPORTED, TOO_LARGE }

private const val MAX_DOCUMENT_ATTACHMENT_BYTES = 10 * 1024 * 1024
private const val MAX_TEXT_ATTACHMENT_BYTES = 2 * 1024 * 1024
private val TEXT_FILE_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "jsonl", "xml", "yaml", "yml", "toml", "csv", "tsv",
    "kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp",
    "cs", "swift", "sh", "bash", "zsh", "fish", "sql", "html", "css", "scss", "gradle", "properties",
    "ini", "conf", "config", "log", "env", "gitignore",
)

internal fun validateLocalAttachment(mime: String, filename: String, sizeBytes: Long): LocalAttachmentValidation {
    val extension = filename.substringAfterLast('.', "").lowercase()
    val isText = mime.startsWith("text/") || extension in TEXT_FILE_EXTENSIONS || mime in setOf(
        "application/json", "application/xml", "application/javascript", "application/x-yaml", "application/yaml",
    )
    val supported = mime.startsWith("image/") || mime == "application/pdf" || isText
    if (!supported) return LocalAttachmentValidation.UNSUPPORTED
    val limit = if (isText) MAX_TEXT_ATTACHMENT_BYTES else MAX_DOCUMENT_ATTACHMENT_BYTES
    return if (sizeBytes > limit) LocalAttachmentValidation.TOO_LARGE else LocalAttachmentValidation.ACCEPTED
}

private fun attachmentMetadata(contentResolver: android.content.ContentResolver, uri: Uri): Pair<String, Long?> {
    var name: String? = null
    var size: Long? = null
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    return (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment") to size
}

private fun readBytesLimited(input: java.io.InputStream, limit: Int): ByteArray? {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size() + count > limit) return null
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private data class ImageSaveRequest(
    val bytes: ByteArray,
    val mime: String,
    val filename: String,
)

private fun decodeDataUrlBytes(dataUrl: String): ByteArray? {
    val encoded = dataUrl.substringAfter(',', missingDelimiterValue = "")
    if (encoded.isBlank()) return null
    return try {
        Base64.decode(encoded, Base64.DEFAULT)
    } catch (_: Exception) {
        null
    }
}

private fun decodePartFileBytes(file: Part.File): ByteArray? {
    val url = file.url ?: return null
    val encoded = if (url.contains(',')) url.substringAfter(',') else url
    if (encoded.isBlank()) return null
    return try {
        Base64.decode(encoded, Base64.DEFAULT)
    } catch (_: Exception) {
        null
    }
}

private fun extensionForMime(mime: String): String {
    return when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "img"
    }
}

private fun imageThumbnailModel(attachment: ImageAttachment): Any {
    if (attachment.uri.scheme.equals("data", ignoreCase = true)) {
        val encoded = attachment.dataUrl.substringAfter(',', missingDelimiterValue = "")
        if (encoded.isNotBlank()) {
            return try {
                Base64.decode(encoded, Base64.DEFAULT)
            } catch (_: Exception) {
                attachment.dataUrl
            }
        }
    }
    return attachment.uri
}

private data class PreparedAttachment(
    val attachment: ImageAttachment,
    val comparison: AttachmentComparison? = null
)

private data class AttachmentComparison(
    val originalBytes: Int,
    val optimizedBytes: Int,
    val originalEstimatedTokens: Int,
    val optimizedEstimatedTokens: Int
)

private fun estimateVisionTokens(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return 0
    return ((width.toLong() * height.toLong()) / 750.0).toInt()
}

private fun formatFileSize(bytes: Int): String {
    val value = bytes.toDouble()
    return when {
        value >= 1024.0 * 1024.0 -> String.format("%.2f MB", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format("%.1f KB", value / 1024.0)
        else -> "$bytes B"
    }
}

private suspend fun buildAttachmentFromUri(
    contentResolver: android.content.ContentResolver,
    uri: Uri,
    compressImages: Boolean,
    maxLongSidePx: Int = 1440,
    webpQuality: Int = 60
): PreparedAttachment? = withContext(Dispatchers.IO) {
    val (originalFilename, declaredSize) = attachmentMetadata(contentResolver, uri)
    var mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
    val extension = originalFilename.substringAfterLast('.', "").lowercase()
    if (mimeType == "application/octet-stream" && extension in TEXT_FILE_EXTENSIONS) mimeType = "text/plain"
    if (validateLocalAttachment(mimeType, originalFilename, declaredSize ?: 0) != LocalAttachmentValidation.ACCEPTED) {
        return@withContext null
    }
    val isText = mimeType.startsWith("text/") || extension in TEXT_FILE_EXTENSIONS || mimeType in setOf(
        "application/json", "application/xml", "application/javascript", "application/x-yaml", "application/yaml",
    )
    val byteLimit = if (isText) MAX_TEXT_ATTACHMENT_BYTES else MAX_DOCUMENT_ATTACHMENT_BYTES
    val bytes = contentResolver.openInputStream(uri)?.use { readBytesLimited(it, byteLimit) } ?: return@withContext null

    val shouldOptimize = compressImages && (mimeType == "image/png" || mimeType == "image/jpeg")
    if (!shouldOptimize) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64",
                sizeBytes = bytes.size,
            )
        )
    }

    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bitmap == null) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64",
                sizeBytes = bytes.size,
            )
        )
    }

    val srcWidth = bitmap.width
    val srcHeight = bitmap.height
    val longSide = maxOf(srcWidth, srcHeight)
    val resizeEnabled = maxLongSidePx > 0
    val scale = if (resizeEnabled && longSide > maxLongSidePx) {
        maxLongSidePx.toFloat() / longSide.toFloat()
    } else {
        1f
    }
    val outWidth = (srcWidth * scale).toInt().coerceAtLeast(1)
    val outHeight = (srcHeight * scale).toInt().coerceAtLeast(1)
    val resizedBitmap = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true) else bitmap

    val output = java.io.ByteArrayOutputStream()
    @Suppress("DEPRECATION")
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        Bitmap.CompressFormat.WEBP
    }
    val compressed = resizedBitmap.compress(format, webpQuality.coerceIn(1, 100), output)
    if (resizedBitmap !== bitmap) {
        resizedBitmap.recycle()
    }
    bitmap.recycle()

    if (!compressed) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64",
                sizeBytes = bytes.size,
            )
        )
    }

    val webpBytes = output.toByteArray()
    if (scale >= 0.999f && webpBytes.size >= bytes.size) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64",
                sizeBytes = bytes.size,
            )
        )
    }
    val base64 = Base64.encodeToString(webpBytes, Base64.NO_WRAP)
    val optimizedFilename = originalFilename.substringBeforeLast('.', originalFilename) + ".webp"
    return@withContext PreparedAttachment(
        attachment = ImageAttachment(
            uri = uri,
            mime = "image/webp",
            filename = optimizedFilename,
            dataUrl = "data:image/webp;base64,$base64",
            sizeBytes = webpBytes.size,
        ),
        comparison = AttachmentComparison(
            originalBytes = bytes.size,
            optimizedBytes = webpBytes.size,
            originalEstimatedTokens = estimateVisionTokens(srcWidth, srcHeight),
            optimizedEstimatedTokens = estimateVisionTokens(outWidth, outHeight)
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (sessionId: String) -> Unit = {},
    onNavigateToChildSession: (sessionId: String) -> Unit = {},
    onOpenInWebView: () -> Unit = {},
    initialSharedAttachments: List<Uri> = emptyList(),
    onSharedAttachmentsConsumed: () -> Unit = {},
    startInTerminalMode: Boolean = false,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val draftText by viewModel.draftText.collectAsState()
    val draftAttachmentUris by viewModel.draftAttachmentUris.collectAsState()
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    // Sync inputText once from draft on first composition
    var draftTextInitialized by remember { mutableStateOf(false) }
    if (!draftTextInitialized && draftText.isNotEmpty()) {
        inputText = TextFieldValue(draftText, TextRange(draftText.length))
        draftTextInitialized = true
    } else if (!draftTextInitialized) {
        draftTextInitialized = true
    }
    // Listen for revert events that should restore text to the input field
    LaunchedEffect(Unit) {
        viewModel.revertedDraftEvent.collect { payload ->
            inputText = TextFieldValue(payload.text, TextRange(payload.text.length))
        }
    }
    val listState = rememberLazyListState()
    var showModelPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showAttachmentOptions by remember { mutableStateOf(false) }
    var showSubagentContextDetails by remember { mutableStateOf(false) }
    var isTerminalMode by rememberSaveable { mutableStateOf(startInTerminalMode) }
    var terminalCtrlLatched by rememberSaveable { mutableStateOf(false) }
    var terminalAltLatched by rememberSaveable { mutableStateOf(false) }
    var terminalVirtualCtrlDown by remember { mutableStateOf(false) }
    var terminalVirtualFnDown by remember { mutableStateOf(false) }
    val terminalFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val isAmoled = isAmoledTheme()
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val view = LocalView.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var terminalOverlayHeightPx by remember { mutableStateOf(0) }

    // @ file mention state
    val fileSearchResults by viewModel.fileSearchResults.collectAsState()
    val confirmedFilePaths by viewModel.confirmedFilePaths.collectAsState()

    // Settings
    val chatFontSize by viewModel.chatFontSize.collectAsState()
    val codeWordWrap by viewModel.codeWordWrap.collectAsState()
    val confirmBeforeSend by viewModel.confirmBeforeSend.collectAsState()
    val compactMessages by viewModel.compactMessages.collectAsState()
    val collapseTools by viewModel.collapseTools.collectAsState()
    val expandReasoning by viewModel.expandReasoning.collectAsState()
    val showTurnDividers by viewModel.showTurnDividers.collectAsState()
    val hapticEnabled by viewModel.hapticFeedback.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val compressImageAttachments by viewModel.compressImageAttachments.collectAsState()
    val imageAttachmentMaxLongSide by viewModel.imageAttachmentMaxLongSide.collectAsState()
    val imageAttachmentWebpQuality by viewModel.imageAttachmentWebpQuality.collectAsState()
    val terminalVersion by viewModel.terminalVersion.collectAsState()
    val terminalConnected by viewModel.terminalConnected.collectAsState()
    val terminalTabs by viewModel.terminalTabs.collectAsState()
    val activeTerminalTabId by viewModel.activeTerminalTabId.collectAsState()
    val activeTerminalTab = terminalTabs.firstOrNull { it.id == activeTerminalTabId }
    val terminalFontSizeSp by viewModel.terminalFontSizeSp.collectAsState()
    val terminalDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val lifecycleOwner = LocalLifecycleOwner.current
    var showSendConfirmDialog by remember { mutableStateOf(false) }
    // Pending send action: stored so the confirm dialog can trigger it
    var pendingSendAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var inputMode by rememberSaveable { mutableStateOf(ChatInputMode.NORMAL.name) }
    val isShellMode = inputMode == ChatInputMode.SHELL.name

    BackHandler(enabled = isTerminalMode) {
        if (terminalDrawerState.isOpen) {
            coroutineScope.launch { terminalDrawerState.close() }
        } else if (startInTerminalMode) {
            // Opened directly in terminal mode (e.g. from sessions list) —
            // back should navigate away, not show the chat view.
            onNavigateBack()
        } else {
            isTerminalMode = false
        }
    }

    LaunchedEffect(isTerminalMode) {
        if (isTerminalMode) {
            viewModel.openTerminalSession { ok ->
                if (!ok) {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_terminal_connect_failed))
                    }
                    isTerminalMode = false
                }
            }
        } else {
            terminalCtrlLatched = false
            terminalAltLatched = false
            terminalVirtualCtrlDown = false
            terminalVirtualFnDown = false
        }
    }

    DisposableEffect(lifecycleOwner, viewModel.serverId, viewModel.sessionId) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            SessionNotificationCoordinator.activate(context, viewModel.serverId, viewModel.sessionId)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    SessionNotificationCoordinator.activate(context, viewModel.serverId, viewModel.sessionId)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    SessionNotificationCoordinator.deactivate(viewModel.serverId, viewModel.sessionId)
                }
                Lifecycle.Event.ON_STOP -> {
                    terminalCtrlLatched = false
                    terminalAltLatched = false
                    terminalVirtualCtrlDown = false
                    terminalVirtualFnDown = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            SessionNotificationCoordinator.deactivate(viewModel.serverId, viewModel.sessionId)
        }
    }

    DisposableEffect(isTerminalMode) {
        val activity = context as? MainActivity
        if (isTerminalMode && activity != null) {
            activity.setTerminalKeyInterceptor { event ->
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        terminalVirtualCtrlDown = event.action == android.view.KeyEvent.ACTION_DOWN
                        true
                    }
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                        terminalVirtualFnDown = event.action == android.view.KeyEvent.ACTION_DOWN
                        if (BuildConfig.DEBUG) {
                            Log.d("TerminalInput", "VOL_UP: action=${if (event.action == android.view.KeyEvent.ACTION_DOWN) "DOWN" else "UP"} nowDown=$terminalVirtualFnDown")
                        }
                        true
                    }
                    else -> false
                }
            }
        } else {
            activity?.setTerminalKeyInterceptor(null)
        }
        onDispose {
            activity?.setTerminalKeyInterceptor(null)
            terminalVirtualCtrlDown = false
            terminalVirtualFnDown = false
        }
    }

    // Force status bar black while terminal is visible.
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    DisposableEffect(isTerminalMode) {
        val activity = context as? android.app.Activity
        if (isTerminalMode && activity != null) {
            activity.window.statusBarColor = android.graphics.Color.BLACK
            androidx.core.view.WindowCompat.getInsetsController(
                activity.window, activity.window.decorView
            ).isAppearanceLightStatusBars = false
        }
        onDispose {
            val act = context as? android.app.Activity ?: return@onDispose
            act.window.statusBarColor = android.graphics.Color.TRANSPARENT
            androidx.core.view.WindowCompat.getInsetsController(
                act.window, act.window.decorView
            ).isAppearanceLightStatusBars = !isDarkTheme
        }
    }

    LaunchedEffect(isTerminalMode, terminalConnected) {
        if (isTerminalMode && terminalConnected) {
            terminalFocusRequester.requestFocus()
        }
    }

    fun pasteClipboardToTerminal() {
        if (!terminalConnected) return
        val clip = clipboardManager.getText()?.text ?: return
        if (clip.isEmpty()) return
        val cleaned = clip
            .replace(Regex("[\u001B\u0080-\u009F]"), "")
            .replace("\r\n", "\r")
            .replace('\n', '\r')
        if (cleaned.isNotEmpty()) {
            viewModel.sendTerminalInput(cleaned)
        }
    }

    fun sendTerminalChunk(chunk: String) {
        if (BuildConfig.DEBUG) {
            val codes = chunk.map { String.format("%04x", it.code) }
            Log.d("TerminalInput", "sendTerminalChunk: chunk=$codes fnDown=$terminalVirtualFnDown")
        }

        val ctrlActive = terminalCtrlLatched || terminalVirtualCtrlDown
        val altActive = terminalAltLatched

        // Termux-compatible shortcut: Ctrl+Alt+V pastes clipboard into terminal.
        if (!terminalVirtualFnDown && ctrlActive && altActive && chunk.length == 1 && chunk[0].lowercaseChar() == 'v') {
            pasteClipboardToTerminal()
            if (terminalCtrlLatched) terminalCtrlLatched = false
            if (terminalAltLatched) terminalAltLatched = false
            return
        }

        val processed = if (terminalVirtualFnDown) {
            val fnResult = applyTermuxFnBindings(chunk, viewModel.terminalEmulator.cursorKeysApplicationMode)
            if (fnResult.showVolumeUi) {
                val audio = context.getSystemService(AudioManager::class.java)
                audio?.adjustSuggestedStreamVolume(
                    AudioManager.ADJUST_SAME,
                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            if (fnResult.toggleKeyboard) {
                if (imeVisible) {
                    keyboardController?.hide()
                } else {
                    terminalFocusRequester.requestFocus()
                    keyboardController?.show()
                }
            }
            fnResult.output
        } else {
            applyTerminalModifiers(
                input = chunk,
                ctrl = ctrlActive,
                alt = altActive
            )
        }
        if (processed.isEmpty()) return
        if (BuildConfig.DEBUG && processed.contains('~')) {
            Log.d("TerminalInput", "SENDING to server: '${processed.map { String.format("%04x", it.code) }}' fnDown=$terminalVirtualFnDown")
        }
        viewModel.sendTerminalInput(processed)
        if (terminalCtrlLatched) terminalCtrlLatched = false
        if (terminalAltLatched) terminalAltLatched = false
    }

    // Keep screen on while on chat screen (if enabled in settings)
    DisposableEffect(keepScreenOn) {
        val window = (context as? android.app.Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Image attachments — backed by ViewModel URIs for draft persistence
    val attachments = remember { mutableStateListOf<ImageAttachment>() }

    // Rebuild attachment objects from persisted draft URIs on first composition
    LaunchedEffect(draftAttachmentUris, compressImageAttachments, imageAttachmentMaxLongSide, imageAttachmentWebpQuality) {
        // Only rebuild if attachments list doesn't match URIs (e.g. on session restore)
        val currentUris = attachments.map { it.uri.toString() }.toSet()
        val draftUriSet = draftAttachmentUris.toSet()
        if (currentUris == draftUriSet) return@LaunchedEffect

        val restored = mutableListOf<ImageAttachment>()
        for (uriStr in draftAttachmentUris) {
            // Skip URIs already present
            if (uriStr in currentUris) {
                val existing = attachments.first { it.uri.toString() == uriStr }
                restored.add(existing)
                continue
            }
            try {
                val uri = android.net.Uri.parse(uriStr)
                if (uriStr.startsWith("data:image/", ignoreCase = true)) {
                    val mime = uriStr.substringAfter("data:").substringBefore(';').ifBlank { "image/png" }
                    val syntheticName = "image.${mime.substringAfter('/', "png")}".lowercase()
                    restored.add(
                        ImageAttachment(
                            uri = uri,
                            mime = mime,
                            filename = syntheticName,
                            dataUrl = uriStr,
                        )
                    )
                    continue
                }
                val prepared = buildAttachmentFromUri(
                    contentResolver = context.contentResolver,
                    uri = uri,
                    compressImages = compressImageAttachments,
                    maxLongSidePx = imageAttachmentMaxLongSide,
                    webpQuality = imageAttachmentWebpQuality
                )
                if (prepared != null) {
                    restored.add(prepared.attachment)
                }
            } catch (e: Exception) {
                Log.w("ChatScreen", "Failed to restore attachment $uriStr: ${e.message}")
                // Remove invalid URI from draft
                viewModel.removeDraftAttachment(draftAttachmentUris.indexOf(uriStr))
            }
        }
        attachments.clear()
        attachments.addAll(restored)
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        coroutineScope.launch {
            val optimizedComparisons = mutableListOf<AttachmentComparison>()
            for (uri in uris) {
                try {
                    // Take persistable URI permission so the URI survives app restarts
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        // Not all URIs support persistable permissions — that's OK
                    }

                    val prepared = buildAttachmentFromUri(
                        contentResolver = context.contentResolver,
                        uri = uri,
                        compressImages = compressImageAttachments,
                        maxLongSidePx = imageAttachmentMaxLongSide,
                        webpQuality = imageAttachmentWebpQuality
                    ) ?: continue

                    attachments.add(prepared.attachment)
                    viewModel.addDraftAttachment(uri.toString())
                    prepared.comparison?.let { optimizedComparisons.add(it) }
                } catch (_: Exception) {
                    // Skip files that fail to read
                }
            }
            if (optimizedComparisons.isNotEmpty()) {
                val totalOriginal = optimizedComparisons.sumOf { it.originalBytes }
                val totalOptimized = optimizedComparisons.sumOf { it.optimizedBytes }
                val totalTokensBefore = optimizedComparisons.sumOf { it.originalEstimatedTokens }
                val totalTokensAfter = optimizedComparisons.sumOf { it.optimizedEstimatedTokens }
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.chat_images_optimized_summary,
                        optimizedComparisons.size,
                        formatFileSize(totalOriginal),
                        formatFileSize(totalOptimized),
                        totalTokensBefore,
                        totalTokensAfter
                    )
                )
            }
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        coroutineScope.launch {
            var rejected = 0
            for (uri in uris) {
                try {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val prepared = buildAttachmentFromUri(
                        contentResolver = context.contentResolver,
                        uri = uri,
                        compressImages = compressImageAttachments,
                        maxLongSidePx = imageAttachmentMaxLongSide,
                        webpQuality = imageAttachmentWebpQuality,
                    )
                    if (prepared == null) {
                        rejected++
                    } else {
                        attachments.add(prepared.attachment)
                        viewModel.addDraftAttachment(uri.toString())
                    }
                } catch (e: Exception) {
                    Log.w("ChatScreen", "Failed to attach document $uri", e)
                    rejected++
                }
            }
            if (rejected > 0) {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_file_attachment_rejected, rejected))
            }
        }
    }

    // Session export via SAF (Storage Access Framework)
    // Flow: menu click → SAF file picker → stream API responses directly to file
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportSession(context, uri) { success ->
                coroutineScope.launch {
                    if (success) {
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_session_exported))
                    } else {
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_session_export_failed))
                    }
                }
            }
        }
    }

    var pendingImageSave by remember { mutableStateOf<ImageSaveRequest?>(null) }
    val saveImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/*")
    ) { uri: Uri? ->
        val request = pendingImageSave
        pendingImageSave = null
        if (uri == null || request == null) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(request.bytes) }
                    ?: error("Unable to open output stream")
            }.onSuccess {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_image_saved))
            }.onFailure {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_image_save_failed))
            }
        }
    }

    val requestSaveImage: (ByteArray, String, String?) -> Unit = { bytes, mime, filenameHint ->
        val baseName = filenameHint
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "image_${System.currentTimeMillis()}"
        val fileName = "$baseName.${extensionForMime(mime)}"
        pendingImageSave = ImageSaveRequest(bytes = bytes, mime = mime, filename = fileName)
        saveImageLauncher.launch(fileName)
    }

    // Consume attachments shared from other apps via ACTION_SEND (one-shot)
    LaunchedEffect(initialSharedAttachments) {
        if (initialSharedAttachments.isEmpty()) return@LaunchedEffect
        val optimizedComparisons = mutableListOf<AttachmentComparison>()
        var rejected = 0
        for (uri in initialSharedAttachments) {
            try {
                // Take persistable URI permission so the URI survives app restarts
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Not all URIs support persistable permissions — that's OK
                }

                val prepared = buildAttachmentFromUri(
                    contentResolver = context.contentResolver,
                    uri = uri,
                    compressImages = compressImageAttachments,
                    maxLongSidePx = imageAttachmentMaxLongSide,
                    webpQuality = imageAttachmentWebpQuality
                )
                if (prepared == null) {
                    rejected++
                    continue
                }

                attachments.add(prepared.attachment)
                prepared.comparison?.let { optimizedComparisons.add(it) }
                viewModel.addDraftAttachment(uri.toString())
            } catch (e: Exception) {
                Log.w("ChatScreen", "Failed to read shared attachment $uri", e)
                rejected++
            }
        }
        if (rejected > 0) {
            snackbarHostState.showSnackbar(context.getString(R.string.chat_file_attachment_rejected, rejected))
        }
        if (optimizedComparisons.isNotEmpty()) {
            val totalOriginal = optimizedComparisons.sumOf { it.originalBytes }
            val totalOptimized = optimizedComparisons.sumOf { it.optimizedBytes }
            val totalTokensBefore = optimizedComparisons.sumOf { it.originalEstimatedTokens }
            val totalTokensAfter = optimizedComparisons.sumOf { it.optimizedEstimatedTokens }
            snackbarHostState.showSnackbar(
                context.getString(
                    R.string.chat_images_optimized_summary,
                    optimizedComparisons.size,
                    formatFileSize(totalOriginal),
                    formatFileSize(totalOptimized),
                    totalTokensBefore,
                    totalTokensAfter
                )
            )
        }
        onSharedAttachmentsConsumed()
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
    val pendingInteractions = uiState.pendingInteractions
    val pendingCount = pendingInteractions.size
    val isBusy = isWorkingSessionStatus(uiState.sessionStatus)
    LaunchedEffect(messageCount, lastPartCount, lastContentLength, pendingCount, isBusy) {
        if (messageCount > 0 && autoScrollEnabled) {
            val lastIndex = listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1
            listState.scrollToItem(lastIndex)
        }
    }

    // Also auto-scroll when first loading
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading && messageCount > 0) {
            val lastIndex = listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1
            listState.scrollToItem(lastIndex)
            autoScrollEnabled = true
        }
    }

    CompositionLocalProvider(
        LocalChatFontSize provides chatFontSize,
        LocalCodeWordWrap provides codeWordWrap,
        LocalCompactMessages provides compactMessages,
        LocalCollapseTools provides collapseTools,
        LocalExpandReasoning provides expandReasoning,
        LocalShowTurnDividers provides showTurnDividers,
        LocalHapticFeedbackEnabled provides hapticEnabled,
        LocalImageSaveRequest provides requestSaveImage,
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!isTerminalMode && uiState.sessionLoaded) {
            Box {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.sessionTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Subtitle: total tokens and cost for the session
                        val totalTokens = uiState.totalInputTokens + uiState.totalOutputTokens
                        if (totalTokens > 0 || uiState.totalCost > 0) {
                            val parts = mutableListOf<String>()
                            if (totalTokens > 0) {
                                parts.add(stringResource(R.string.chat_tokens_summary, formatTokenCount(totalTokens)))
                            }
                            if (uiState.totalCost > 0) {
                                parts.add(stringResource(R.string.chat_cost_format, String.format("%.4f", uiState.totalCost)))
                            }
                            if (parts.isNotEmpty()) {
                                Text(
                                    text = parts.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (
                        uiState.parentSessionId != null &&
                        uiState.contextWindow > 0 &&
                        uiState.lastContextTokens > 0
                    ) {
                        val percentage = Math.round(
                            uiState.lastContextTokens.toDouble() / uiState.contextWindow * 100,
                        ).toInt()
                        val indicatorColor = when {
                            percentage >= 90 -> MaterialTheme.colorScheme.error
                            percentage >= 70 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                        IconButton(onClick = { showSubagentContextDetails = true }) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = {
                                        (uiState.lastContextTokens.toFloat() / uiState.contextWindow)
                                            .coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.size(30.dp),
                                    color = indicatorColor,
                                    trackColor = indicatorColor.copy(alpha = 0.16f),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = "$percentage%",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                    color = indicatorColor,
                                )
                            }
                        }
                    }
                    if (uiState.parentSessionId == null) Box {
                        val isAmoled = isAmoledTheme()
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        if (inputText.text.isNotEmpty()) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(start = 3.dp, top = 3.dp)
                                    .size(15.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.AttachFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.appPopupBorder(),
                            containerColor = appPopupContainerColor(),
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_attach)) },
                                leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    inputMode = ChatInputMode.NORMAL.name
                                    showAttachmentOptions = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tool_terminal)) },
                                leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    isTerminalMode = true
                                },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_reload_session)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.reloadSession()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                },
                            )
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
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
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
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_review_changes)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.executeCommand("review") { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_command_executed, "review") else context.getString(R.string.chat_command_failed, "review")
                                            )
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.RateReview, contentDescription = null)
                                },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                            )
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
                                text = { Text(stringResource(R.string.menu_export_session)) },
                                onClick = {
                                    showMenu = false
                                    val slug = uiState.sessionTitle
                                        .take(30)
                                        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                                        .ifBlank { "session" }
                                    exportLauncher.launch("$slug.json")
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FileDownload, contentDescription = null)
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
                AppLoadingEdge(
                    active = uiState.isLoading && uiState.messages.isEmpty(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            }
        },
        bottomBar = {
            val modelLabel = if (uiState.selectedModelId != null && uiState.providers.isNotEmpty()) {
                val provider = uiState.providers.find { it.id == uiState.selectedProviderId }
                val model = provider?.models?.get(uiState.selectedModelId)
                model?.name ?: uiState.selectedModelId ?: ""
            } else ""
            val hasRunningTool = uiState.messages.any { message ->
                message.parts.any { part -> part is Part.Tool && part.state is ToolState.Running }
            }

            if (!isTerminalMode && uiState.sessionLoaded && uiState.parentSessionId == null) {
            ChatInputBar(
                textFieldValue = inputText,
                onTextFieldValueChange = { newValue ->
                    val shouldAutoShell = !isShellMode && newValue.text.startsWith("!")
                    val normalizedValue = if (shouldAutoShell) {
                        val stripped = newValue.text.drop(1).trimStart()
                        val newCursor = (newValue.selection.start - 1).coerceAtLeast(0)
                        TextFieldValue(
                            text = stripped,
                            selection = TextRange(newCursor.coerceAtMost(stripped.length))
                        )
                    } else {
                        newValue
                    }

                    if (shouldAutoShell) {
                        inputMode = ChatInputMode.SHELL.name
                    }

                    inputText = normalizedValue
                    viewModel.updateDraftText(normalizedValue.text)
                    if (isShellMode || shouldAutoShell) {
                        viewModel.clearFileSearch()
                        return@ChatInputBar
                    }
                    // Detect @query before cursor for file mention
                    val cursorPos = normalizedValue.selection.start
                    val textBefore = normalizedValue.text.substring(0, cursorPos)
                    val atMatch = Regex("@(\\S*)$").find(textBefore)
                    if (atMatch != null) {
                        val query = atMatch.groupValues[1]
                        viewModel.searchFilesForMention(query)
                    } else {
                        viewModel.clearFileSearch()
                    }
                },
                onSend = {
                    val doSend = doSend@{
                        if (hapticEnabled) {
                            @Suppress("DEPRECATION")
                            val flags = android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM, flags)
                            } else {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK, flags)
                            }
                        }
                        val rawText = inputText.text
                        val shellCommand = when {
                            isShellMode -> rawText.trim()
                            rawText.startsWith("!") -> rawText.drop(1).trimStart()
                            else -> null
                        }
                        if (shellCommand != null) {
                            if (shellCommand.isBlank()) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_shell_empty))
                                }
                                return@doSend
                            }
                            if (attachments.isNotEmpty()) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_shell_attachments_unsupported))
                                }
                                return@doSend
                            }
                            viewModel.runShellCommand(shellCommand) { ok ->
                                if (!ok) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_shell_failed))
                                    }
                                }
                            }
                            inputText = TextFieldValue("")
                            if (isShellMode) {
                                inputMode = ChatInputMode.NORMAL.name
                            }
                            viewModel.clearConfirmedPaths()
                            viewModel.clearFileSearch()
                            viewModel.clearDraft()
                            return@doSend
                        }
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
                        if (viewModel.sendMessage(allParts, attachmentParts)) {
                            inputText = TextFieldValue("")
                            attachments.clear()
                            viewModel.clearConfirmedPaths()
                            viewModel.clearFileSearch()
                            viewModel.clearDraft()
                        }
                    }
                    if (confirmBeforeSend) {
                        pendingSendAction = doSend
                        showSendConfirmDialog = true
                    } else {
                        doSend()
                    }
                },
                inputMode = if (isShellMode) ChatInputMode.SHELL else ChatInputMode.NORMAL,
                onInputModeChange = {
                    inputMode = it.name
                    if (it == ChatInputMode.SHELL) {
                        viewModel.clearFileSearch()
                    }
                },
                onStop = viewModel::abortSession,
                isSending = uiState.isSending,
                isBusy = isWorkingSessionStatus(uiState.sessionStatus) || hasRunningTool,
                sessionStatus = uiState.sessionStatus,
                messages = uiState.messages,
                attachments = attachments,
                onAttach = { showAttachmentOptions = true },
                onRemoveAttachment = { index ->
                    if (index in attachments.indices) {
                        attachments.removeAt(index)
                        viewModel.removeDraftAttachment(index)
                    }
                },
                onSaveAttachment = { bytes, mime, filename ->
                    requestSaveImage(bytes, mime, filename)
                },
                modelLabel = modelLabel,
                selectedProviderId = uiState.selectedProviderId,
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
                        "shell" -> {
                            inputMode = ChatInputMode.SHELL.name
                        }
                        "review" -> {
                            viewModel.executeCommand("review") { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_command_executed, "review") else context.getString(R.string.chat_command_failed, "review")
                                    )
                                }
                            }
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
                },
                contextWindow = uiState.contextWindow,
                lastContextTokens = uiState.lastContextTokens,
                contextUsage = uiState.contextUsage,
            )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isTerminalMode -> {
                    // IME inset relative to content area. Some devices report 0 for
                    // ime.exclude(navigationBars), so keep a robust fallback to raw ime.
                    val imeBottomRaw = WindowInsets.ime.getBottom(density)
                    val navBottom = WindowInsets.navigationBars.getBottom(density)
                    val imeBottomPx = (imeBottomRaw - navBottom).coerceAtLeast(0).let { adjusted ->
                        if (adjusted == 0 && imeBottomRaw > 0) imeBottomRaw else adjusted
                    }
                    val imeBottomDp = with(density) { imeBottomPx.toDp() }
                    val overlayHeightDp = with(density) { terminalOverlayHeightPx.toDp() }

                    ModalNavigationDrawer(
                        drawerState = terminalDrawerState,
                        gesturesEnabled = true,
                        drawerContent = {
                            ModalDrawerSheet(
                                drawerContainerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                                drawerContentColor = MaterialTheme.colorScheme.onSurface,
                                drawerTonalElevation = 0.dp,
                                drawerShape = RoundedCornerShape(0.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .widthIn(min = 240.dp, max = 320.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .padding(vertical = 8.dp)
                                            .imePadding(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        items(terminalTabs, key = { it.id }) { tab ->
                                            val selected = tab.id == activeTerminalTabId
                                            val drawerItemShape = RoundedCornerShape(12.dp)
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(drawerItemShape)
                                                    .then(
                                                        if (isAmoled && selected) {
                                                            Modifier.border(
                                                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                                                drawerItemShape
                                                            )
                                                        } else Modifier
                                                    )
                                            ) {
                                                NavigationDrawerItem(
                                                    label = {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                        ) {
                                                            Column(
                                                                modifier = Modifier.weight(1f),
                                                                verticalArrangement = Arrangement.spacedBy(3.dp)
                                                            ) {
                                                                Text(
                                                                    text = tab.title,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    style = MaterialTheme.typography.titleMedium,
                                                                    fontWeight = FontWeight.SemiBold
                                                                )
                                                                if (!tab.connected) {
                                                                    val statusText = stringResource(terminalTabStateLabel(tab.state))
                                                                    Surface(
                                                                        shape = RoundedCornerShape(999.dp),
                                                                        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                                                    ) {
                                                                        Row(
                                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                                            verticalAlignment = Alignment.CenterVertically,
                                                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                                                        ) {
                                                                            if (tab.state == TerminalTabState.Starting ||
                                                                                tab.state == TerminalTabState.Reconnecting
                                                                            ) {
                                                                                CircularProgressIndicator(
                                                                                    modifier = Modifier.size(8.dp),
                                                                                    strokeWidth = 1.5.dp,
                                                                                )
                                                                            } else {
                                                                                Box(
                                                                                    modifier = Modifier
                                                                                        .size(6.dp)
                                                                                        .background(
                                                                                            MaterialTheme.colorScheme.error,
                                                                                            CircleShape,
                                                                                        )
                                                                                )
                                                                            }
                                                                            Text(
                                                                                text = statusText,
                                                                                style = MaterialTheme.typography.labelSmall,
                                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                            if (tab.recoveryAction != TerminalRecoveryAction.None) {
                                                                val recoveryDescription = stringResource(
                                                                    terminalRecoveryLabel(tab.recoveryAction),
                                                                )
                                                                IconButton(
                                                                    onClick = {
                                                                        viewModel.recoverTerminalTab(tab.id) { ok ->
                                                                            if (!ok) {
                                                                                coroutineScope.launch {
                                                                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_terminal_connect_failed))
                                                                                }
                                                                            }
                                                                        }
                                                                    },
                                                                    modifier = Modifier.size(48.dp),
                                                                    colors = IconButtonDefaults.iconButtonColors(
                                                                        containerColor = if (isAmoled) {
                                                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                                                                        } else {
                                                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                                                                        }
                                                                    )
                                                                ) {
                                                                    Icon(
                                                                        Icons.Default.Refresh,
                                                                        contentDescription = recoveryDescription,
                                                                    )
                                                                }
                                                            }
                                                            IconButton(
                                                                onClick = { viewModel.closeTerminalTab(tab.id) },
                                                                modifier = Modifier.size(48.dp),
                                                                colors = IconButtonDefaults.iconButtonColors(
                                                                    containerColor = if (isAmoled) {
                                                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                                                                    } else {
                                                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                                                    }
                                                                )
                                                            ) {
                                                                Icon(
                                                                    Icons.Default.Close,
                                                                    contentDescription = stringResource(R.string.chat_terminal_close_tab),
                                                                )
                                                            }
                                                        }
                                                    },
                                                    selected = selected,
                                                    shape = drawerItemShape,
                                                    colors = NavigationDrawerItemDefaults.colors(
                                                        selectedContainerColor = if (isAmoled) {
                                                            Color.Black
                                                        } else {
                                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                                        },
                                                        unselectedContainerColor = if (isAmoled) Color.Black else Color.Transparent,
                                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                                        unselectedTextColor = MaterialTheme.colorScheme.onSurface
                                                    ),
                                                    onClick = {
                                                        viewModel.switchTerminalTab(tab.id)
                                                        coroutineScope.launch { terminalDrawerState.close() }
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }

                                    HorizontalDivider()

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        AppSecondaryButton(
                                            onClick = {
                                                viewModel.createTerminalTab { ok ->
                                                    if (!ok) {
                                                        coroutineScope.launch {
                                                            snackbarHostState.showSnackbar(context.getString(R.string.chat_terminal_connect_failed))
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp),
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.chat_terminal_new_tab))
                                        }
                                        AppSecondaryButton(
                                            onClick = {
                                                keyboardController?.show()
                                                coroutineScope.launch { terminalDrawerState.close() }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp),
                                        ) {
                                            Icon(Icons.Default.Keyboard, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.chat_terminal_keyboard))
                                        }
                                    }

                                    }

                                    if (isAmoled) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .fillMaxHeight()
                                                .width(1.dp)
                                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                                        )
                                    }
                                }
                            }
                        }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            SessionTerminalInline(
                                emulator = viewModel.terminalEmulator,
                                terminalVersion = terminalVersion,
                                connected = terminalConnected,
                                focusRequester = terminalFocusRequester,
                                onSendInput = ::sendTerminalChunk,
                                onPaste = ::pasteClipboardToTerminal,
                                onResize = { cols, rows ->
                                    viewModel.resizeTerminal(cols, rows)
                                },
                                fontSizeSp = terminalFontSizeSp,
                                onFontSizeChange = viewModel::setTerminalFontSize,
                                contentBottomPadding = overlayHeightDp + imeBottomDp,
                                modifier = Modifier.fillMaxSize()
                            )

                            if (activeTerminalTab != null && !activeTerminalTab.connected) {
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(12.dp)
                                        .zIndex(2f),
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isAmoled) {
                                        Color.Black
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (activeTerminalTab.state == TerminalTabState.Starting ||
                                            activeTerminalTab.state == TerminalTabState.Reconnecting
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .background(MaterialTheme.colorScheme.error, CircleShape),
                                            )
                                        }
                                        Text(
                                            text = stringResource(terminalTabStateLabel(activeTerminalTab.state)),
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        if (activeTerminalTab.recoveryAction != TerminalRecoveryAction.None) {
                                            TextButton(
                                                onClick = {
                                                    viewModel.recoverTerminalTab(activeTerminalTab.id) { ok ->
                                                        if (!ok) {
                                                            coroutineScope.launch {
                                                                snackbarHostState.showSnackbar(
                                                                    context.getString(R.string.chat_terminal_connect_failed),
                                                                )
                                                            }
                                                        }
                                                    }
                                                },
                                            ) {
                                                Icon(
                                                    Icons.Default.Refresh,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Spacer(Modifier.width(5.dp))
                                                Text(
                                                    stringResource(
                                                        terminalRecoveryLabel(activeTerminalTab.recoveryAction),
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxHeight()
                                    .padding(bottom = overlayHeightDp + imeBottomDp)
                                    .width(18.dp)
                                    .zIndex(0f)
                                    .pointerInput(terminalDrawerState) {
                                        detectTapGestures(
                                            onLongPress = {
                                                if (!terminalDrawerState.isOpen) {
                                                    coroutineScope.launch { terminalDrawerState.open() }
                                                }
                                            }
                                        )
                                    }
                                    .pointerInput(terminalDrawerState) {
                                        var dragged = 0f
                                        detectHorizontalDragGestures(
                                            onHorizontalDrag = { _, dragAmount ->
                                                if (terminalDrawerState.isOpen) return@detectHorizontalDragGestures
                                                dragged += dragAmount
                                                if (dragged > 2f) {
                                                    coroutineScope.launch { terminalDrawerState.open() }
                                                    dragged = 0f
                                                }
                                            },
                                            onDragEnd = { dragged = 0f },
                                            onDragCancel = { dragged = 0f }
                                        )
                                    }
                            )

                        TerminalKeyboardOverlay(
                            connected = terminalConnected,
                            ctrlLatched = terminalCtrlLatched,
                            altLatched = terminalAltLatched,
                            cursorApp = viewModel.terminalEmulator.cursorKeysApplicationMode,
                            onToggleDrawer = { coroutineScope.launch { terminalDrawerState.apply { if (isOpen) close() else open() } } },
                            onToggleCtrl = { terminalCtrlLatched = !terminalCtrlLatched },
                            onToggleAlt = { terminalAltLatched = !terminalAltLatched },
                            onSendInput = ::sendTerminalChunk,
                            onCtrlC = { viewModel.sendTerminalInput("\u0003") },
                            onClear = { viewModel.clearTerminalBuffer() },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .zIndex(1f)
                                    .fillMaxWidth()
                                    .padding(bottom = imeBottomDp)
                                    .onSizeChanged { terminalOverlayHeightPx = it.height }
                            )

                        }
                    }
                }
                uiState.isLoading && uiState.messages.isEmpty() -> {
                    // Loading is shown consistently on the lower edge of the top app bar.
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
                        ErrorPayloadContent(
                            text = uiState.error ?: stringResource(R.string.session_unknown_error),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            textColor = MaterialTheme.colorScheme.error,
                        )
                        AppPrimaryButton(onClick = { viewModel.loadMessages() }) {
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
                        if (uiState.hasOlderMessages) {
                            AppPrimaryButton(
                                onClick = { viewModel.loadOlderMessages() },
                                enabled = !uiState.isLoadingOlder,
                            ) {
                                Text(stringResource(R.string.chat_load_earlier))
                            }
                        }
                    }
                }
                else -> {
                    val messageSpacing = if (LocalCompactMessages.current) 4.dp else 12.dp
                    val chatTurns = remember(uiState.messages) { groupChatTurns(uiState.messages) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(messageSpacing)
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
                                            PulsingDotsIndicator(
                                                dotSize = 6.dp,
                                                dotSpacing = 4.dp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            chatTurns,
                            key = { it.key },
                        ) { chatTurn ->
                            val chatMessage = chatTurn.messages.first()
                            // Detect compaction trigger messages (user messages with Part.Compaction)
                            val isCompactionTrigger = chatMessage.isUser &&
                                chatMessage.parts.any { it is Part.Compaction }

                            // Show compact system-style divider for compaction triggers.
                            if (isCompactionTrigger) {
                                var showRevertDialog by remember { mutableStateOf(false) }

                                if (showRevertDialog) {
                                    RevertConfirmationDialog(
                                        onDismiss = { showRevertDialog = false },
                                        onConfirm = {
                                            showRevertDialog = false
                                            viewModel.revertMessage(chatMessage.message.id) { ok ->
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        if (ok) context.getString(R.string.chat_message_reverted) else context.getString(R.string.chat_message_revert_failed)
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp, horizontal = 32.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )
                                    Text(
                                        text = stringResource(R.string.chat_summarized),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clickable { showRevertDialog = true },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Undo,
                                            contentDescription = stringResource(R.string.chat_revert),
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                        )
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )
                                }
                                return@items
                            }

                            ChatMessageBubble(
                                chatMessages = chatTurn.messages,
                                onNavigateToChildSession = onNavigateToChildSession,
                                onRevert = if (chatMessage.isUser) {
                                    {
                                        val revertText = chatMessage.parts
                                            .filterIsInstance<Part.Text>()
                                            .joinToString("\n") { it.text }
                                        viewModel.revertMessage(chatMessage.message.id, revertText) { ok ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (ok) context.getString(R.string.chat_message_reverted) else context.getString(R.string.chat_message_revert_failed)
                                                )
                                            }
                                        }
                                    }
                                } else null,
                                onCopyText = {
                                    val text = chatTurn.messages.flatMap { it.parts }
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

                        pendingInteractions.firstOrNull()?.let { interaction ->
                            item(key = "pending_${interaction::class.simpleName}_${interaction.sessionId}_${interaction.id}") {
                                val position = stringResource(R.string.pending_request_position, 1, pendingInteractions.size)
                                when (interaction) {
                                    is PendingInteraction.Permission -> PermissionCard(
                                        permission = interaction.request,
                                        position = position,
                                        onReply = { reply, onResult ->
                                            viewModel.replyToPermission(
                                                interaction.sessionId,
                                                interaction.id,
                                                reply,
                                            ) { success ->
                                                onResult(success)
                                                if (!success) coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(R.string.pending_request_reply_failed),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                    is PendingInteraction.Question -> QuestionCard(
                                        question = interaction.request,
                                        position = position,
                                        onSubmit = { answers, onResult ->
                                            viewModel.replyToQuestion(
                                                interaction.sessionId,
                                                interaction.id,
                                                answers,
                                            ) { success ->
                                                onResult(success)
                                                if (!success) coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(R.string.pending_request_reply_failed),
                                                    )
                                                }
                                            }
                                        },
                                        onReject = { onResult ->
                                            viewModel.rejectQuestion(interaction.sessionId, interaction.id) { success ->
                                                onResult(success)
                                                if (!success) coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(R.string.pending_request_reply_failed),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        // A stable final item lets scrollToItem clamp to the true content bottom,
                        // including spacing and padding below a tall or streaming message.
                        item(key = "conversation_bottom") {
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    // Scroll-to-bottom FAB
                    if (!isAtBottom && !autoScrollEnabled) {
                        SmallFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    val lastIndex = listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1
                                    listState.scrollToItem(lastIndex)
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
    if (showAttachmentOptions) {
        val sheetColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface
        val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        val sheetBorderColor = MaterialTheme.colorScheme.outlineVariant
        ModalBottomSheet(
            onDismissRequest = { showAttachmentOptions = false },
            dragHandle = null,
            shape = sheetShape,
            containerColor = sheetColor,
            tonalElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isAmoled) {
                            Modifier.drawBehind {
                                val strokeWidth = 1.dp.toPx()
                                val edge = strokeWidth / 2
                                val radius = 28.dp.toPx()
                                val outline = Path().apply {
                                    moveTo(edge, size.height)
                                    lineTo(edge, radius)
                                    arcTo(
                                        rect = Rect(edge, edge, radius * 2 - edge, radius * 2 - edge),
                                        startAngleDegrees = 180f,
                                        sweepAngleDegrees = 90f,
                                        forceMoveTo = false,
                                    )
                                    lineTo(size.width - radius, edge)
                                    arcTo(
                                        rect = Rect(
                                            size.width - radius * 2 + edge,
                                            edge,
                                            size.width - edge,
                                            radius * 2 - edge,
                                        ),
                                        startAngleDegrees = 270f,
                                        sweepAngleDegrees = 90f,
                                        forceMoveTo = false,
                                    )
                                    lineTo(size.width - edge, size.height)
                                }
                                drawPath(
                                    path = outline,
                                    color = sheetBorderColor,
                                    style = Stroke(strokeWidth),
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BottomSheetDefaults.DragHandle()
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.chat_attach_title),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )
                        AttachmentSourceCard(
                            icon = Icons.Default.Image,
                            title = stringResource(R.string.chat_attach_photo),
                            description = stringResource(R.string.chat_attach_photo_hint),
                            onClick = {
                                showAttachmentOptions = false
                                imagePickerLauncher.launch("image/*")
                            },
                        )
                        AttachmentSourceCard(
                            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                            title = stringResource(R.string.chat_attach_device_file),
                            description = stringResource(R.string.chat_attach_device_file_hint),
                            onClick = {
                                showAttachmentOptions = false
                                documentPickerLauncher.launch(arrayOf("*/*"))
                            },
                        )
                        AttachmentSourceCard(
                            icon = Icons.Default.FolderOpen,
                            title = stringResource(R.string.chat_attach_project_file),
                            description = stringResource(R.string.chat_attach_project_file_hint),
                            onClick = {
                                showAttachmentOptions = false
                                inputMode = ChatInputMode.NORMAL.name
                                val updated = if (inputText.text.isBlank()) "@" else inputText.text + " @"
                                inputText = TextFieldValue(updated, TextRange(updated.length))
                                viewModel.updateDraftText(updated)
                                viewModel.searchFilesForMention("")
                            },
                        )
                    }
                    Spacer(Modifier.navigationBarsPadding().height(8.dp))
                }
            }
        }
    }

    if (showSubagentContextDetails) {
        ContextUsageDialog(
            usage = uiState.contextUsage,
            contextWindow = uiState.contextWindow,
            onDismiss = { showSubagentContextDetails = false },
        )
    }

    if (showRenameDialog) {
        var renameText by remember { mutableStateOf(uiState.sessionTitle) }
        ChatDialog(onDismiss = { showRenameDialog = false }) {
            Text(stringResource(R.string.session_rename), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = renameText,
                onValueChange = { renameText = it },
                label = { Text(stringResource(R.string.session_rename_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppSecondaryButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
                AppPrimaryButton(
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
            }
        }
    }

    // Send confirmation dialog
    if (showSendConfirmDialog) {
        ChatDialog(onDismiss = {
                showSendConfirmDialog = false
                pendingSendAction = null
            }) {
            Text(stringResource(R.string.settings_confirm_send_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_confirm_send_body))
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppSecondaryButton(onClick = {
                    showSendConfirmDialog = false
                    pendingSendAction = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
                AppPrimaryButton(onClick = {
                    showSendConfirmDialog = false
                    pendingSendAction?.invoke()
                    pendingSendAction = null
                }) {
                    Text(stringResource(R.string.settings_send))
                }
            }
        }
    }
    } // CompositionLocalProvider
}

@Composable
private fun AttachmentSourceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val isAmoled = isAmoledTheme()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.primaryContainer,
                border = if (isAmoled) {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.72f))
                } else null,
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(23.dp),
                        tint = if (isAmoled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun ChatDialog(
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            content = content,
        )
    }
}

@Composable
private fun RevertConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ChatDialog(onDismiss = onDismiss) {
        Text(
            stringResource(R.string.chat_revert_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.chat_revert_message))
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AppSecondaryButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
            AppSecondaryButton(onClick = onConfirm, destructive = true) {
                Text(stringResource(R.string.chat_revert))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerDialog(
    providers: List<ProviderInfo>,
    selectedProviderId: String?,
    selectedModelId: String?,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val isAmoled = isAmoledTheme()
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

    AppDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
    ) {
        Text(
            text = stringResource(R.string.chat_select_model),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 12.dp),
        ) {
                for ((index, provider) in sortedProviders.withIndex()) {
                    val topPad = if (index == 0) 0.dp else 12.dp

                    val sortedModels = provider.models.values
                        .sortedWith(compareBy<ProviderModel> { !isModelFree(provider.id, it) }.thenBy { it.name.lowercase() })

                    item(key = "provider_header_${provider.id}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = topPad, bottom = 2.dp, start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ProviderIcon(
                                providerId = provider.id,
                                size = 14.dp,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                text = (provider.name.ifEmpty { provider.id }).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    items(
                        sortedModels,
                        key = { "model_${provider.id}_${it.id}" }
                    ) { model ->
                        val isSelected = provider.id == selectedProviderId && model.id == selectedModelId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(AppPickerItemShape)
                                .background(
                                    if (isSelected) appSelectedItemColor()
                                    else Color.Transparent
                                )
                                .then(
                                    if (isSelected && isAmoled) {
                                        Modifier.border(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                                            AppPickerItemShape,
                                        )
                                    } else Modifier,
                                )
                                .clickable { onSelect(provider.id, model.id) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            AppSecondaryButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    }
}

internal fun terminalZoomFontSize(startFontSizeSp: Float, scale: Float): Float {
    return (startFontSizeSp * scale).coerceIn(6f, 20f)
}

internal fun terminalGestureIsPinch(pressedPointerCount: Int): Boolean = pressedPointerCount >= 2

@androidx.annotation.StringRes
private fun terminalTabStateLabel(state: TerminalTabState): Int = when (state) {
    TerminalTabState.Starting -> R.string.chat_terminal_starting
    TerminalTabState.Connected -> R.string.chat_terminal_connected
    TerminalTabState.Reconnecting -> R.string.chat_terminal_reconnecting
    TerminalTabState.Disconnected -> R.string.chat_terminal_disconnected
    TerminalTabState.Exited -> R.string.chat_terminal_exited
}

@androidx.annotation.StringRes
private fun terminalRecoveryLabel(action: TerminalRecoveryAction): Int = when (action) {
    TerminalRecoveryAction.Reconnect -> R.string.chat_terminal_reconnect_tab
    TerminalRecoveryAction.Restart -> R.string.chat_terminal_restart_tab
    TerminalRecoveryAction.None -> R.string.chat_terminal_connected
}

internal fun terminalFlingScrollVelocity(pointerVelocityY: Float, minimumFlingVelocity: Float): Float {
    val scrollVelocity = -pointerVelocityY
    return if (abs(scrollVelocity) >= minimumFlingVelocity) scrollVelocity else 0f
}

internal fun terminalInputDelta(previous: String, current: String): String {
    val commonPrefixLength = previous.commonPrefixWith(current).length
    val deleted = previous.length - commonPrefixLength
    return "\u007F".repeat(deleted) + current.drop(commonPrefixLength)
}

private data class TerminalMetrics(
    val fontSizePx: Float,
    val charWidthPx: Float,
    val rowHeightPx: Int,
    val baselinePx: Float,
    val columns: Int,
    val rows: Int,
)

@Composable
private fun SessionTerminalInline(
    emulator: TerminalEmulator,
    terminalVersion: Long,
    connected: Boolean,
    focusRequester: FocusRequester,
    onSendInput: (String) -> Unit,
    onPaste: () -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    contentBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val isAmoled = isAmoledTheme()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val baseTextToolbar = LocalTextToolbar.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val minimumTerminalFlingVelocity = remember(context) {
        android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    }
    val coroutineScope = rememberCoroutineScope()
    var inputCapture by remember { mutableStateOf(TextFieldValue("")) }
    var sentInputCapture by remember { mutableStateOf("") }
    val terminalScrollState = rememberScrollState()
    var terminalFollowMode by rememberSaveable { mutableStateOf(true) }
    var terminalFlingJob by remember { mutableStateOf<Job?>(null) }
    var terminalLifecycleActive by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { source, _ ->
            terminalLifecycleActive = source.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!terminalLifecycleActive) {
                terminalFlingJob?.cancel()
                terminalFlingJob = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val terminalTextToolbar = remember(baseTextToolbar, onPaste) {
        object : TextToolbar {
            override val status: TextToolbarStatus
                get() = baseTextToolbar.status

            override fun hide() {
                baseTextToolbar.hide()
            }

            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?
            ) {
                baseTextToolbar.showMenu(
                    rect = rect,
                    onCopyRequested = onCopyRequested,
                    onPasteRequested = {
                        onPaste()
                        onPasteRequested?.invoke()
                    },
                    onCutRequested = onCutRequested,
                    onSelectAllRequested = onSelectAllRequested
                )
            }
        }
    }

    var pinchActive by remember { mutableStateOf(false) }
    var pinchStartFontSizeSp by remember { mutableFloatStateOf(fontSizeSp) }
    var pinchPreviewFontSizeSp by remember { mutableFloatStateOf(fontSizeSp) }
    val latestFontSizeSp by rememberUpdatedState(fontSizeSp)

    LaunchedEffect(fontSizeSp, pinchActive) {
        if (!pinchActive) pinchPreviewFontSizeSp = fontSizeSp
    }
    val effectiveFontSizeSp = if (pinchActive) pinchPreviewFontSizeSp else fontSizeSp
    val terminalStyle = remember(effectiveFontSizeSp) {
        CodeTypography.copy(
            fontSize = effectiveFontSizeSp.sp,
            // Tight line spacing is required for continuous box-drawing in TUIs (mc, htop).
            lineHeight = effectiveFontSizeSp.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
    }

    Column(
        modifier = modifier
            .background(Color.Black)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        BasicTextField(
            value = inputCapture,
            onValueChange = { next ->
                if (!connected) {
                    inputCapture = TextFieldValue("")
                    sentInputCapture = ""
                    return@BasicTextField
                }
                val delta = terminalInputDelta(sentInputCapture, next.text)
                if (delta.isNotEmpty()) {
                    if (BuildConfig.DEBUG && delta.contains('~')) {
                        Log.d(
                            "TerminalInput",
                            "IME delta='$delta' old='$sentInputCapture' now='${next.text}' " +
                                "composition=${next.composition}",
                        )
                    }
                    val mapped = delta
                        .replace("\r\n", "\r")
                        .replace('\n', '\r')
                    onSendInput(mapped)
                }
                sentInputCapture = next.text
                // Keep IME context (caps/symbol lock, composing state) stable by
                // preserving TextFieldValue instead of clearing it after each key.
                inputCapture = next.copy(selection = TextRange(next.text.length))
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            onSendInput("\r")
                            true
                        }
                        Key.Tab -> {
                            onSendInput("\t")
                            true
                        }
                        Key.Backspace -> {
                            onSendInput("\u007F")
                            true
                        }
                        else -> {
                            val native = event.nativeKeyEvent
                            val unicode = native.unicodeChar
                            if (unicode > 0 && (unicode and android.view.KeyCharacterMap.COMBINING_ACCENT) == 0) {
                                if (native.isCtrlPressed) {
                                    val lower = unicode.toChar().lowercaseChar()
                                    if (lower in 'a'..'z') {
                                        val ctrl = (lower.code - 'a'.code + 1).toChar().toString()
                                        onSendInput(ctrl)
                                        true
                                    } else {
                                        false
                                    }
                                } else {
                                    onSendInput(String(Character.toChars(unicode)))
                                    true
                                }
                            } else {
                                val baseLetter = when (event.key) {
                                    Key.A -> 'a'
                                    Key.B -> 'b'
                                    Key.C -> 'c'
                                    Key.D -> 'd'
                                    Key.E -> 'e'
                                    Key.F -> 'f'
                                    Key.G -> 'g'
                                    Key.H -> 'h'
                                    Key.I -> 'i'
                                    Key.J -> 'j'
                                    Key.K -> 'k'
                                    Key.L -> 'l'
                                    Key.M -> 'm'
                                    Key.N -> 'n'
                                    Key.O -> 'o'
                                    Key.P -> 'p'
                                    Key.Q -> 'q'
                                    Key.R -> 'r'
                                    Key.S -> 's'
                                    Key.T -> 't'
                                    Key.U -> 'u'
                                    Key.V -> 'v'
                                    Key.W -> 'w'
                                    Key.X -> 'x'
                                    Key.Y -> 'y'
                                    Key.Z -> 'z'
                                    else -> null
                                }
                                if (baseLetter != null) {
                                    val upper = native.isShiftPressed.xor(native.isCapsLockOn)
                                    val out = if (upper) baseLetter.uppercaseChar() else baseLetter
                                    if (native.isCtrlPressed) {
                                        val ctrl = (baseLetter.code - 'a'.code + 1).toChar().toString()
                                        onSendInput(ctrl)
                                    } else {
                                        onSendInput(out.toString())
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                    }
                },
            singleLine = false,
            textStyle = terminalStyle,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = { onSendInput("\r") },
                onDone = { onSendInput("\r") },
                onGo = { onSendInput("\r") }
            )
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = contentBottomPadding)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                    )
                }
        ) {
            val density = LocalDensity.current
            val viewportWidthPx = constraints.maxWidth
            val viewportHeightPx = constraints.maxHeight
            val terminalMetrics = remember(
                effectiveFontSizeSp,
                density.density,
                density.fontScale,
                viewportWidthPx,
                viewportHeightPx,
            ) {
                // One native Paint supplies every grid and drawing metric so resize and Canvas stay aligned.
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = with(density) { effectiveFontSizeSp.sp.toPx() }
                }
                val fm = paint.fontMetrics
                val charWidthPx = paint.measureText("X")
                val rowHeightPx = kotlin.math.ceil((fm.descent - fm.ascent).toDouble()).toInt()
                TerminalMetrics(
                    fontSizePx = paint.textSize,
                    charWidthPx = charWidthPx,
                    rowHeightPx = rowHeightPx,
                    baselinePx = -fm.ascent,
                    columns = if (viewportWidthPx > 0) {
                        (viewportWidthPx / charWidthPx).toInt().coerceAtLeast(20)
                    } else 80,
                    rows = if (viewportHeightPx > 0) {
                        (viewportHeightPx / rowHeightPx).coerceAtLeast(8)
                    } else 24,
                )
            }
            val charWidthPx = terminalMetrics.charWidthPx
            val rowHeightPx = terminalMetrics.rowHeightPx
            val termCols = terminalMetrics.columns
            val termRows = terminalMetrics.rows
            val maxScrollbackOffsetRows = remember(terminalVersion, termRows) {
                emulator.maxScrollbackOffset(termRows)
            }
            val totalRows = remember(terminalVersion) {
                emulator.totalRowsWithScrollback().coerceAtLeast(1)
            }
            val renderedOutput = remember(terminalVersion, totalRows) {
                emulator.render(
                    scrollbackOffsetRows = 0,
                    windowRows = totalRows,
                )
            }
            val renderedRuns = remember(terminalVersion, totalRows) {
                emulator.renderRuns(
                    scrollbackOffsetRows = 0,
                    windowRows = totalRows,
                )
            }
            val maxScrollPx = maxScrollbackOffsetRows * rowHeightPx
            val followThresholdPx = (rowHeightPx * 2).coerceAtLeast(1)
            val isNearBottom = terminalScrollState.value >= (maxScrollPx - followThresholdPx).coerceAtLeast(0)
            LaunchedEffect(isNearBottom) {
                if (isNearBottom) {
                    terminalFollowMode = true
                }
            }
            LaunchedEffect(maxScrollPx, terminalVersion, terminalFollowMode) {
                when {
                    terminalFollowMode -> {
                        if (terminalScrollState.value != maxScrollPx) {
                            terminalScrollState.scrollTo(maxScrollPx)
                        }
                    }
                    terminalScrollState.value > maxScrollPx -> {
                        terminalScrollState.scrollTo(maxScrollPx)
                    }
                }
            }
            val firstVisibleRow = (terminalScrollState.value / rowHeightPx)
                .coerceIn(0, maxScrollbackOffsetRows)
            val scrollbackOffsetRows = (maxScrollbackOffsetRows - firstVisibleRow).coerceAtLeast(0)
            val verticalOffsetPx = firstVisibleRow * rowHeightPx
            LaunchedEffect(termCols, termRows, connected, pinchActive) {
                if (!pinchActive && connected && viewportWidthPx > 0 && viewportHeightPx > 0) {
                    delay(150)
                    onResize(termCols, termRows)
                }
            }

            val cursorPos = remember(terminalVersion, scrollbackOffsetRows, termRows) {
                emulator.getCursorPositionInWindow(
                    scrollbackOffsetRows = scrollbackOffsetRows,
                    windowRows = termRows,
                )
            }
            val cursorAlpha = if (terminalLifecycleActive) {
                val cursorAnim = rememberInfiniteTransition(label = "terminal_cursor")
                val animatedAlpha by cursorAnim.animateFloat(
                    initialValue = 1f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 700),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "terminal_cursor_alpha"
                )
                animatedAlpha
            } else {
                1f
            }

            val accessibilityOutput = remember(terminalVersion, scrollbackOffsetRows, termRows) {
                AnnotatedString(
                    emulator.renderSelectionText(
                        scrollbackOffsetRows = scrollbackOffsetRows,
                        windowRows = termRows,
                    ),
                )
            }

            val terminalBgColor = Color.Black
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { text = accessibilityOutput }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                            terminalFlingJob?.cancel()
                            terminalFlingJob = null
                            val velocityTracker = VelocityTracker().apply {
                                addPosition(firstDown.uptimeMillis, firstDown.position)
                            }
                            var gestureIsPinch = false
                            var gestureScale = 1f
                            var previewFontSizeSp = latestFontSizeSp
                            var gestureContinues: Boolean
                            do {
                                val event = awaitPointerEvent()
                                event.changes.firstOrNull { it.id == firstDown.id }?.let {
                                    velocityTracker.addPosition(it.uptimeMillis, it.position)
                                }
                                val pressedPointers = event.changes.count { it.pressed }
                                if (terminalGestureIsPinch(pressedPointers)) {
                                    if (!gestureIsPinch) {
                                        gestureIsPinch = true
                                        pinchActive = true
                                        pinchStartFontSizeSp = latestFontSizeSp
                                        gestureScale = 1f
                                        previewFontSizeSp = pinchStartFontSizeSp
                                        keyboard?.hide()
                                        if (BuildConfig.DEBUG) Log.d("TerminalGesture", "Pinch started")
                                    }
                                    val zoom = event.calculateZoom()
                                    if (zoom.isFinite() && zoom > 0f) {
                                        gestureScale *= zoom
                                        previewFontSizeSp = terminalZoomFontSize(pinchStartFontSizeSp, gestureScale)
                                        pinchPreviewFontSizeSp = previewFontSizeSp
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (!gestureIsPinch && maxScrollbackOffsetRows > 0) {
                                    val pan = event.calculatePan()
                                    if (pan.y != 0f) {
                                        terminalScrollState.dispatchRawDelta(-pan.y)
                                        val nearBottomAfterPan = terminalScrollState.value >=
                                            (maxScrollPx - followThresholdPx).coerceAtLeast(0)
                                        terminalFollowMode = nearBottomAfterPan
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                                gestureContinues = event.changes.any { it.pressed }
                            } while (gestureContinues)
                            if (gestureIsPinch) {
                                onFontSizeChange(previewFontSizeSp)
                                pinchActive = false
                                if (BuildConfig.DEBUG) {
                                    Log.d("TerminalGesture", "Pinch committed: ${previewFontSizeSp}sp")
                                }
                            } else if (maxScrollbackOffsetRows > 0) {
                                val flingVelocity = terminalFlingScrollVelocity(
                                    pointerVelocityY = velocityTracker.calculateVelocity().y,
                                    minimumFlingVelocity = minimumTerminalFlingVelocity,
                                )
                                if (flingVelocity != 0f) {
                                    terminalFlingJob = coroutineScope.launch {
                                        var previousValue = 0f
                                        AnimationState(
                                            initialValue = 0f,
                                            initialVelocity = flingVelocity,
                                        ).animateDecay(exponentialDecay()) {
                                            val requestedDelta = value - previousValue
                                            val consumedDelta = terminalScrollState.dispatchRawDelta(requestedDelta)
                                            previousValue = value
                                            terminalFollowMode = terminalScrollState.value >=
                                                (maxScrollPx - followThresholdPx).coerceAtLeast(0)
                                            if (abs(consumedDelta - requestedDelta) > 0.5f) {
                                                cancelAnimation()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            ) {
                // Canvas layer: draw each character at its exact grid position to
                // guarantee monospaced alignment for box-drawing characters.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val nativeCanvas = drawContext.canvas.nativeCanvas

                    // Paint for background fills — no anti-aliasing for pixel-perfect
                    // row tiling (matches Termux approach).
                    val bgPaint = android.graphics.Paint().apply {
                        isAntiAlias = false
                        style = android.graphics.Paint.Style.FILL
                    }

                    // Fill the entire terminal area with the default background.
                    bgPaint.color = terminalBgColor.toArgb()
                    nativeCanvas.drawRect(0f, 0f, size.width, size.height, bgPaint)

                    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = terminalMetrics.fontSizePx
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    val baseline = terminalMetrics.baselinePx
                    val rowH = rowHeightPx.toFloat()

                    for ((rowIdx, runs) in renderedRuns.withIndex()) {
                        val y = ((rowIdx * rowHeightPx) - verticalOffsetPx).toFloat()
                        if (y + rowH <= 0f || y >= size.height) continue
                        for (run in runs) {
                            val x = run.col * charWidthPx
                            // Draw background rectangle for the whole run.
                            // Integer row height with integer y-positions tiles exactly —
                            // no overlap needed (matches Termux).
                            if (run.bg != Color.Unspecified && run.bg != terminalBgColor) {
                                bgPaint.color = run.bg.toArgb()
                                nativeCanvas.drawRect(
                                    x, y,
                                    x + run.text.length * charWidthPx, y + rowH,
                                    bgPaint
                                )
                            }
                            // Configure paint for this run's style.
                            textPaint.color = run.fg.toArgb()
                            val typefaceStyle = when {
                                run.bold && run.italic -> android.graphics.Typeface.BOLD_ITALIC
                                run.bold -> android.graphics.Typeface.BOLD
                                run.italic -> android.graphics.Typeface.ITALIC
                                else -> android.graphics.Typeface.NORMAL
                            }
                            textPaint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, typefaceStyle)
                            textPaint.isUnderlineText = run.underline
                            // Draw each character individually at its grid position.
                            val textY = y + baseline
                            for ((i, ch) in run.text.withIndex()) {
                                if (ch != ' ') {
                                    nativeCanvas.drawText(
                                        ch.toString(),
                                        x + i * charWidthPx,
                                        textY,
                                        textPaint
                                    )
                                }
                            }
                        }
                    }
                }

                // Invisible text layer for native text selection (long-press copy).
                // We strip all explicit span colors so text is invisible, but the
                // Compose SelectionContainer still draws a visible selection highlight.
                val selectionOutput = remember(terminalVersion) {
                    buildAnnotatedString {
                        append(
                            emulator.renderSelectionText(
                                scrollbackOffsetRows = 0,
                                windowRows = totalRows,
                            )
                        )
                    }
                }
                // Match the selection overlay line height to the canvas row height
                // so selection handles align with the rendered text.
                val selectionLineHeight = with(LocalDensity.current) { rowHeightPx.toSp() }
                val selectionStyle = remember(fontSizeSp, selectionLineHeight) {
                    terminalStyle.copy(
                        color = Color.Transparent,
                        lineHeight = selectionLineHeight,
                    )
                }
                val selectionColors = TextSelectionColors(
                    handleColor = Color(0xFF4FC3F7),
                    backgroundColor = Color(0xFF4FC3F7).copy(alpha = 0.4f)
                )
                CompositionLocalProvider(
                    LocalTextToolbar provides terminalTextToolbar,
                    LocalTextSelectionColors provides selectionColors
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(terminalScrollState)
                    ) {
                        SelectionContainer {
                            Text(
                                text = selectionOutput,
                                style = selectionStyle,
                                softWrap = false,
                                maxLines = Int.MAX_VALUE,
                                modifier = Modifier.fillMaxWidth()
                                    .clearAndSetSemantics { }
                            )
                        }
                    }
                }

                if (connected && cursorPos != null) {
                    val cursorCol = cursorPos.second.coerceIn(0, (termCols - 1).coerceAtLeast(0))
                    val cursorRow = cursorPos.first.coerceIn(0, (termRows - 1).coerceAtLeast(0))
                    val cursorX = with(LocalDensity.current) { (cursorCol * charWidthPx).toDp() }
                    val cursorY = with(LocalDensity.current) { (cursorRow * rowHeightPx).toDp() }
                    val cursorW = with(LocalDensity.current) { charWidthPx.toDp() }
                    val cursorH = with(LocalDensity.current) { rowHeightPx.toDp() }

                    Box(
                        modifier = Modifier
                            .offset(x = cursorX, y = cursorY)
                            .size(width = cursorW, height = cursorH)
                            .background(Color(0xFFD3D7CF).copy(alpha = cursorAlpha))
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalKeyboardOverlay(
    connected: Boolean,
    ctrlLatched: Boolean,
    altLatched: Boolean,
    cursorApp: Boolean,
    onToggleDrawer: () -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onSendInput: (String) -> Unit,
    onCtrlC: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAmoled = isAmoledTheme()
    // Arrow / Home / End sequences depend on DECCKM
    val arrowUp    = if (cursorApp) "\u001BOA" else "\u001B[A"
    val arrowDown  = if (cursorApp) "\u001BOB" else "\u001B[B"
    val arrowRight = if (cursorApp) "\u001BOC" else "\u001B[C"
    val arrowLeft  = if (cursorApp) "\u001BOD" else "\u001B[D"
    val home       = if (cursorApp) "\u001BOH" else "\u001B[H"
    val end        = if (cursorApp) "\u001BOF" else "\u001B[F"

    Surface(
        modifier = modifier,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = if (isAmoled) Color.Black else Color(0xFF1A1A1A),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 0.dp),
        ) {
            // Row 1: matches Termux default extra keys
            TerminalKeyRow(
                isAmoled = isAmoled,
                keys = listOf(
                    TerminalKey("ESC", popupLabel = "☰", popupAction = onToggleDrawer) { onSendInput("\u001B") },
                    TerminalKey("/") { onSendInput("/") },
                    TerminalKey("-", popupLabel = "|", popupAction = { onSendInput("|") }) { onSendInput("-") },
                    TerminalKey("HOME") { onSendInput(home) },
                    TerminalKey("\u2191") { onSendInput(arrowUp) },
                    TerminalKey("END") { onSendInput(end) },
                    TerminalKey("PGUP") { onSendInput("\u001B[5~") },
                )
            )
            // Thin divider between rows
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        if (isAmoled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        else Color(0xFF333333)
                    )
            )
            // Row 2: matches Termux default extra keys
            TerminalKeyRow(
                isAmoled = isAmoled,
                keys = listOf(
                    TerminalKey("\u21B9") { onSendInput("\t") },
                    TerminalKey("CTRL", active = ctrlLatched, action = onToggleCtrl),
                    TerminalKey("ALT", active = altLatched, action = onToggleAlt),
                    TerminalKey("\u2190") { onSendInput(arrowLeft) },
                    TerminalKey("\u2193") { onSendInput(arrowDown) },
                    TerminalKey("\u2192") { onSendInput(arrowRight) },
                    TerminalKey("PGDN") { onSendInput("\u001B[6~") },
                )
            )
        }
    }
}

private data class TerminalKey(
    val label: String,
    val active: Boolean = false,
    val popupLabel: String? = null,
    val popupAction: (() -> Unit)? = null,
    val action: () -> Unit
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalKeyRow(keys: List<TerminalKey>, isAmoled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        keys.forEachIndexed { index, key ->
            if (index > 0 && !isAmoled) {
                // Thin vertical divider between keys
                Box(
                    Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(Color(0xFF333333))
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .then(
                        when {
                            isAmoled -> Modifier.border(
                                width = 0.5.dp,
                                color = if (key.active) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                },
                            )
                            key.active -> Modifier.background(Color(0xFF333333))
                            else -> Modifier
                        }
                    )
                    .combinedClickable(
                        onClick = key.action,
                        onLongClick = { key.popupAction?.invoke() }
                    )
            ) {
                Text(
                    text = key.label,
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 13.sp
                    ),
                    color = when {
                        isAmoled && key.active -> MaterialTheme.colorScheme.primary
                        isAmoled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                        key.active -> Color(0xFF80CBC4)
                        else -> Color(0xFFCCCCCC)
                    }
                )
            }
        }
    }
}

private fun applyTerminalModifiers(input: String, ctrl: Boolean, alt: Boolean): String {
    if (input.isEmpty()) return input
    var out = input
    if (ctrl) {
        out = out.map { ch -> ctrlTransform(ch) }.joinToString("")
    }
    if (alt) {
        out = "\u001B$out"
    }
    return out
}

private data class FnBindingResult(
    val output: String,
    val showVolumeUi: Boolean = false,
    val toggleKeyboard: Boolean = false,
)

private fun applyTermuxFnBindings(input: String, cursorApp: Boolean): FnBindingResult {
    if (input.isEmpty()) return FnBindingResult(output = "")

    val up = if (cursorApp) "\u001BOA" else "\u001B[A"
    val down = if (cursorApp) "\u001BOB" else "\u001B[B"
    val right = if (cursorApp) "\u001BOC" else "\u001B[C"
    val left = if (cursorApp) "\u001BOD" else "\u001B[D"

    val out = StringBuilder()
    var showVolumeUi = false
    var toggleKeyboard = false
    for (ch in input) {
        when (ch.lowercaseChar()) {
            'w' -> out.append(up)
            'a' -> out.append(left)
            's' -> out.append(down)
            'd' -> out.append(right)

            'p' -> out.append("\u001B[5~")
            'n' -> out.append("\u001B[6~")

            't' -> out.append('\t')
            'i' -> out.append("\u001B[2~")
            'h' -> out.append('~')
            'u' -> out.append('_')
            'l' -> out.append('|')

            '1' -> out.append("\u001BOP")
            '2' -> out.append("\u001BOQ")
            '3' -> out.append("\u001BOR")
            '4' -> out.append("\u001BOS")
            '5' -> out.append("\u001B[15~")
            '6' -> out.append("\u001B[17~")
            '7' -> out.append("\u001B[18~")
            '8' -> out.append("\u001B[19~")
            '9' -> out.append("\u001B[20~")
            '0' -> out.append("\u001B[21~")

            'e' -> out.append('\u001B')
            '.' -> out.append(28.toChar()) // Ctrl+\

            'b', 'f', 'x' -> {
                out.append('\u001B')
                out.append(ch.lowercaseChar())
            }

            // Termux also handles FN+v (volume UI) and FN+q/k (toggle toolbar),
            // which are app-specific actions. We consume them with no terminal output.
            'v' -> showVolumeUi = true
            'q', 'k' -> toggleKeyboard = true

            else -> Unit
        }
    }
    return FnBindingResult(
        output = out.toString(),
        showVolumeUi = showVolumeUi,
        toggleKeyboard = toggleKeyboard,
    )
}

private fun ctrlTransform(ch: Char): Char {
    return when {
        ch in 'a'..'z' -> (ch.code - 96).toChar()
        ch in 'A'..'Z' -> (ch.code - 64).toChar()
        ch == ' ' -> 0.toChar()
        ch == '[' -> 27.toChar()
        ch == '\\' -> 28.toChar()
        ch == ']' -> 29.toChar()
        ch == '^' -> 30.toChar()
        ch == '_' -> 31.toChar()
        else -> ch
    }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageBubble(
    chatMessages: List<ChatMessage>,
    onRevert: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
    onNavigateToChildSession: (String) -> Unit = {},
) {
    val chatMessage = chatMessages.last()
    val isUser = chatMessage.isUser
    val isAmoled = isAmoledTheme()
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val backgroundColor = if (isAmoled) {
        Color.Black
    } else if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isAmoled) {
        MaterialTheme.colorScheme.onSurface
    } else if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val bubbleBorder = if (isAmoled) {
        BorderStroke(
            1.dp,
            if (isUser) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
            }
        )
    } else {
        null
    }
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    // Separate parts into text/reasoning (shown directly) and step parts (behind toggle)
    val allParts = chatMessages.flatMap { it.parts }
    val visibleParts = if (isUser) {
        allParts.filter { part ->
            when (part) {
                is Part.Text -> part.synthetic != true && part.ignored != true && part.text.isNotBlank()
                else -> true
            }
        }
    } else {
        allParts
    }

    val userMessage = chatMessage.message as? Message.User
    val assistantMessage = chatMessages.mapNotNull { it.message as? Message.Assistant }.lastOrNull()
    val assistantErrorText = chatMessages.firstNotNullOfOrNull {
        formatAssistantErrorMessage((it.message as? Message.Assistant)?.error)
    }
    val userFallbackText = userMessage?.summary?.body?.takeIf { it.isNotBlank() }
        ?: userMessage?.summary?.title?.takeIf { it.isNotBlank() }
    val userCommandLabel = if (isUser) {
        resolveUserCommandLabel(chatMessage.parts)
    } else {
        null
    }

    // Tool cards belong in the response timeline. Each card owns its own collapse state.
    val contentParts: List<Part>
    val stepParts: List<Part>
    if (!isUser) {
        contentParts = visibleParts.filter { part ->
            part is Part.Text || part is Part.Reasoning || part is Part.Patch ||
                    part is Part.File || part is Part.Permission || part is Part.Question ||
                    part is Part.Abort || part is Part.Retry || part is Part.Tool
        }
        stepParts = emptyList()
    } else {
        contentParts = visibleParts
        stepParts = emptyList()
    }

    val hasRenderableUserPart = contentParts.any(::isBubbleRenderablePart)
    val hasRenderableUserContent = !isUser || hasRenderableUserPart || userFallbackText != null || userCommandLabel != null
    val hasRenderableAssistantContent = isUser ||
            contentParts.isNotEmpty() ||
            stepParts.isNotEmpty() ||
            assistantErrorText != null
    if (!hasRenderableUserContent || !hasRenderableAssistantContent) {
        return
    }

    val hasSteps = stepParts.isNotEmpty()
    val autoExpand = LocalCollapseTools.current
    var stepsExpanded by remember(autoExpand) { mutableStateOf(autoExpand) }

    // Check if any tool is currently running (show spinner)
    val hasRunningTool = stepParts.any { it is Part.Tool && it.state is ToolState.Running }
    var showRevertConfirmation by remember { mutableStateOf(false) }

    if (showRevertConfirmation && onRevert != null) {
        RevertConfirmationDialog(
            onDismiss = { showRevertConfirmation = false },
            onConfirm = {
                showRevertConfirmation = false
                onRevert()
            },
        )
    }

    val bubbleContent: @Composable (Modifier) -> Unit = { modifier ->
        Surface(
            shape = if (isUser) {
                RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 4.dp,
                    bottomStart = 12.dp,
                    bottomEnd = 12.dp,
                )
            } else {
                RoundedCornerShape(12.dp)
            },
            color = backgroundColor,
            border = bubbleBorder,
            tonalElevation = 0.dp,
            modifier = modifier.fillMaxWidth()
        ) {
            val compact = LocalCompactMessages.current
            Box {
                Column(
                    modifier = Modifier
                        .padding(
                            PaddingValues(
                                start = if (isUser) 8.dp else if (compact) 10.dp else 12.dp,
                                end = if (isUser) 8.dp else if (compact) 10.dp else 12.dp,
                                top = if (isUser) 4.dp else if (compact) 6.dp else 8.dp,
                                bottom = if (isUser || compact) 2.dp else 4.dp,
                            ),
                        ),
                    verticalArrangement = Arrangement.spacedBy(if (isUser) 1.dp else 2.dp),
                ) {
                    // Steps toggle (like WebUI "Show/Hide steps")
                    if (hasSteps) {
                        val stepsStatus = resolveStepsStatus(stepParts)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { performHaptic(hapticView, hapticOn); stepsExpanded = !stepsExpanded }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasRunningTool) {
                                PulsingDotsIndicator(
                                    dotSize = 5.dp,
                                    dotSpacing = 3.dp,
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
                                        isUser = isUser,
                                        onNavigateToChildSession = onNavigateToChildSession,
                                    )
                                }
                            }
                        }
                    }

                    val contentGroups = if (isUser) {
                        listOf(contentParts)
                    } else {
                        chatMessages.map { message ->
                            message.parts.filter { part ->
                                part is Part.Text || part is Part.Reasoning || part is Part.Patch ||
                                    part is Part.File || part is Part.Permission || part is Part.Question ||
                                    part is Part.Abort || part is Part.Retry || part is Part.Tool
                            }
                        }.filter { it.isNotEmpty() }
                    }
                    var renderedContent = false
                    contentGroups.forEachIndexed { groupIndex, groupParts ->
                        val imageFiles = groupParts.filterIsInstance<Part.File>()
                            .filter { it.mime.startsWith("image/") && !it.url.isNullOrBlank() }
                        val renderableParts = groupParts.filter { part ->
                            !(part is Part.File && part.mime.startsWith("image/") && !part.url.isNullOrBlank())
                        }.filter(::isBubbleRenderablePart)
                        if (imageFiles.isNotEmpty()) {
                            ImageThumbnailRow(imageFiles = imageFiles)
                            renderedContent = true
                        }
                        renderableParts.forEach { part ->
                            PartContent(
                                part = part,
                                textColor = textColor,
                                isUser = isUser,
                                onNavigateToChildSession = onNavigateToChildSession,
                            )
                            renderedContent = true
                        }
                        if (!isUser && LocalShowTurnDividers.current && groupIndex < contentGroups.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }

                    if (isUser && !renderedContent && userCommandLabel != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RateReview,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = textColor.copy(alpha = 0.7f)
                            )
                            Text(
                                text = userCommandLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor.copy(alpha = 0.85f)
                            )
                        }
                    }

                    if (!isUser && assistantErrorText != null) {
                        Surface(
                            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (isAmoled) 0.75f else 0.35f)),
                            tonalElevation = 0.dp,
                        ) {
                            ErrorPayloadContent(
                                text = assistantErrorText,
                                textStyle = MaterialTheme.typography.bodySmall,
                                textColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }

                    // If text parts are absent but server provided a summary, render it.
                    if (visibleParts.isEmpty() && isUser && userFallbackText != null) {
                        Text(
                            text = userFallbackText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor.copy(alpha = 0.5f)
                        )
                    }

                    MessageMetadataRow(
                        message = chatMessage.message,
                        textColor = textColor,
                        delivery = chatMessage.delivery,
                        onRevert = if (isUser && onRevert != null) {
                            { showRevertConfirmation = true }
                        } else null,
                        onCopyText = onCopyText,
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        bubbleContent(Modifier)
    }
}

@Composable
private fun MessageMetadataRow(
    message: Message,
    textColor: Color,
    delivery: MessageDelivery?,
    onRevert: (() -> Unit)?,
    onCopyText: (() -> Unit)?,
) {
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val timestamp = remember(message.time.created) {
        val millis = if (message.time.created < 10_000_000_000L) message.time.created * 1000 else message.time.created
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(millis))
    }
    val agent = when (message) {
        is Message.User -> message.agent
        is Message.Assistant -> message.agent
    }
    val model = when (message) {
        is Message.User -> message.model?.modelId
        is Message.Assistant -> message.modelId
    }
    val tokenSummary = when (message) {
        is Message.User -> null
        is Message.Assistant -> message.tokens?.let { tokens ->
            val input = tokens.input + tokens.cache.read
            "↑${formatTokenCount(input)} ↓${formatTokenCount(tokens.output)}"
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(timestamp, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.42f))
        if (delivery != null) {
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    text = stringResource(
                        if (delivery == MessageDelivery.PROMOTED) R.string.chat_message_sent
                        else R.string.chat_message_queued,
                    ),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        if (!agent.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = textColor.copy(alpha = 0.12f),
            ) {
                Text(
                    agent.replaceFirstChar { it.uppercase() },
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.68f),
                )
            }
        }
        if (!model.isNullOrBlank()) {
            Text(
                model,
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.48f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        if (tokenSummary != null) {
            Text(tokenSummary, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.42f))
        }
        if (onRevert != null) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .semantics { role = Role.Button }
                    .clickable {
                        performHaptic(hapticView, hapticOn)
                        onRevert()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.chat_revert),
                    modifier = Modifier.size(13.dp),
                    tint = textColor.copy(alpha = 0.58f),
                )
            }
        }
        if (onCopyText != null) {
            Spacer(Modifier.width(3.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .semantics { role = Role.Button }
                    .clickable { performHaptic(hapticView, hapticOn); onCopyText() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.chat_copy),
                    modifier = Modifier.size(13.dp),
                    tint = textColor.copy(alpha = 0.42f),
                )
            }
        }
    }
}

private fun isBubbleRenderablePart(part: Part): Boolean {
    return when (part) {
        is Part.Text,
        is Part.Reasoning,
        is Part.Patch,
        is Part.File,
        is Part.Permission,
        is Part.Question,
        is Part.Abort,
        is Part.Retry,
        is Part.Tool -> true
        else -> false
    }
}

@Composable
private fun resolveUserCommandLabel(parts: List<Part>): String? {
    val subtaskParts = parts.filterIsInstance<Part.Subtask>()

    val commandFromSubtask = subtaskParts
        .firstNotNullOfOrNull { it.command }
        ?.removePrefix("/")
        ?.trim()
        ?.lowercase()

    val commandFromText = parts
        .filterIsInstance<Part.Text>()
        .firstNotNullOfOrNull { textPart ->
            val text = textPart.text.trim()
            if (!text.startsWith("/")) return@firstNotNullOfOrNull null
            text.removePrefix("/").substringBefore(' ').trim().lowercase().takeIf { it.isNotBlank() }
        }

    val inferredReviewFromPrompt = subtaskParts.any { subtask ->
        val prompt = subtask.prompt.lowercase()
        val description = subtask.description?.lowercase().orEmpty()
        "review changes" in prompt || "review" in description
    }

    val command = commandFromSubtask ?: commandFromText ?: if (inferredReviewFromPrompt) "review" else null

    return when (command) {
        "review" -> stringResource(R.string.menu_review_changes)
        null -> {
            val hasNonRenderableOnly = parts.any { part ->
                part !is Part.Text &&
                        part !is Part.Reasoning &&
                        part !is Part.Patch &&
                        part !is Part.File &&
                        part !is Part.Permission &&
                        part !is Part.Question &&
                        part !is Part.Abort &&
                        part !is Part.Retry
            }
            if (hasNonRenderableOnly) stringResource(R.string.chat_tool_running_command) else null
        }
        else -> stringResource(R.string.chat_tool_running_command)
    }
}

/**
 * Banner shown when messages have been reverted.
 * Tapping restores (redo) the reverted messages.
 */
@Composable
private fun RevertBanner(onRedo: () -> Unit) {
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val isAmoled = isAmoledTheme()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
        border = if (isAmoled) appAmoledBorder() else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { role = Role.Button }
            .clickable { performHaptic(hapticView, hapticOn); onRedo() }
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
                tint = if (isAmoled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onTertiaryContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_messages_reverted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = stringResource(R.string.chat_tap_restore),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
            }
            Icon(
                Icons.Default.Restore,
                contentDescription = stringResource(R.string.chat_restore),
                modifier = Modifier.size(20.dp),
                tint = if (isAmoled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun PartContent(
    part: Part,
    textColor: Color,
    isUser: Boolean = false,
    onNavigateToChildSession: (String) -> Unit = {},
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
                ReasoningBlock(part = part)
            }
        }
        is Part.Tool -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // todoread parts are filtered out entirely (WebUI convention)
                if (part.tool == "todoread") {
                    // skip
                } else if (part.tool == "todowrite") {
                    TodoListCard(tool = part)
                } else {
                    when (part.tool) {
                        "edit", "multiedit" -> EditToolCard(tool = part)
                        "write" -> WriteToolCard(tool = part)
                        "apply_patch" -> ApplyPatchToolCard(tool = part)
                        "bash" -> BashToolCard(tool = part)
                        "read" -> ReadToolCard(tool = part)
                        "glob", "grep" -> SearchToolCard(tool = part)
                        "task" -> TaskToolCard(tool = part, onNavigateToChildSession = onNavigateToChildSession)
                        else -> ToolCallCard(tool = part)
                    }
                }
                val attachments = (part.state as? ToolState.Completed)?.attachments.orEmpty()
                    .mapIndexed { index, attachment ->
                        Part.File(
                            id = attachment.id.ifBlank { "${part.id}-attachment-$index" },
                            sessionId = attachment.sessionId.ifBlank { part.sessionId },
                            messageId = attachment.messageId.ifBlank { part.messageId },
                            mime = attachment.mime,
                            filename = attachment.filename,
                            url = attachment.url ?: attachment.data,
                            source = attachment.source,
                        )
                    }
                val images = attachments.filter { it.mime.startsWith("image/") && !it.url.isNullOrBlank() }
                if (images.isNotEmpty()) ImageThumbnailRow(images)
                attachments.filterNot { it in images }.forEach { FileCard(it) }
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
    val normalizedMarkdown = remember(markdown) {
        normalizeTaskListMarkers(preserveRawHtmlPayload(markdown))
    }
    val isAmoled = isAmoledTheme()

    // Inline code: keep text styling, but no opaque background so selection remains visible.
    val inlineCodeFg = when {
        isAmoled -> MaterialTheme.colorScheme.onSurface
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    // Code blocks: distinct background
    val codeBlockBg = when {
        isAmoled -> MaterialTheme.colorScheme.surfaceContainerLow
        isUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val codeBlockFg = when {
        isAmoled -> MaterialTheme.colorScheme.onSurface
        isUser -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    // Font size from settings: small=13sp, medium=14sp (default), large=16sp
    val fontSizeSetting = LocalChatFontSize.current
    val (bodyFontSize, bodyLineHeight) = when (fontSizeSetting) {
        "small" -> 13.sp to 18.sp
        "large" -> 16.sp to 26.sp
        else -> 14.sp to 22.sp // medium
    }
    val (codeFontSize, codeLineHeight) = when (fontSizeSetting) {
        "small" -> 11.sp to 16.sp
        "large" -> 15.sp to 22.sp
        else -> 13.sp to 20.sp // medium
    }

    // Balanced text style with better line-height for readability
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        color = textColor,
        fontSize = bodyFontSize,
        lineHeight = bodyLineHeight
    )

    val colors = markdownColor(
        text = textColor,
        codeText = codeBlockFg,
        inlineCodeText = inlineCodeFg,
        linkText = when {
            isAmoled -> MaterialTheme.colorScheme.primary
            isUser -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.primary
        },
        codeBackground = codeBlockBg,
        inlineCodeBackground = Color.Transparent,
        dividerColor = textColor.copy(alpha = 0.32f)
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
        code = CodeTypography.copy(color = codeBlockFg, fontSize = codeFontSize, lineHeight = codeLineHeight),
        inlineCode = CodeTypography.copy(
            color = inlineCodeFg,
            fontSize = codeFontSize,
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
        codeBlock = safeHighlightedCodeBlock,
        codeFence = safeHighlightedCodeFence,
        table = horizontallyScrollableMarkdownTable,
    )

    SelectionContainer {
        Markdown(
            content = normalizedMarkdown,
            colors = colors,
            typography = typography,
            flavour = ChatMarkdownFlavour,
            annotator = ChatMarkdownAnnotator,
            components = components,
            imageTransformer = Coil2ImageTransformerImpl,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private val HtmlDocumentHintRegex = Regex("(?is)<!doctype\\s+html\\b|<\\s*html\\b")
private val HtmlTagRegex = Regex("(?is)<\\s*/?\\s*[a-z][^>]*>")
private val MarkdownFenceStartRegex = Regex("^ {0,3}(`{3,}|~{3,})")
private val TaskListMarkerRegex = Regex("^(\\s*[-+*]\\s+)\\[([ xX])]([ \\t]+)")
internal val ChatMarkdownFlavour = GFMFlavourDescriptor()

internal fun normalizeTaskListMarkers(markdown: String): String {
    var fenceMarker: Char? = null
    var minimumFenceLength = 0
    return markdown.split('\n').joinToString("\n") { line ->
        val marker = MarkdownFenceStartRegex.find(line)?.groupValues?.get(1)
        if (fenceMarker != null) {
            if (marker != null && marker.first() == fenceMarker && marker.length >= minimumFenceLength) {
                fenceMarker = null
                minimumFenceLength = 0
            }
            line
        } else if (marker != null) {
            fenceMarker = marker.first()
            minimumFenceLength = marker.length
            line
        } else {
            TaskListMarkerRegex.replace(line) { match ->
                val checkbox = if (match.groupValues[2].equals("x", ignoreCase = true)) "\u2611" else "\u2610"
                match.groupValues[1] + checkbox + match.groupValues[3]
            }
        }
    }
}
internal val ChatMarkdownAnnotator = DefaultMarkdownAnnotator { content, node ->
    markdownTokenReplacement(content, node)?.let { replacement ->
        append(replacement)
        true
    } ?: false
}

private val EmailAutolinkRegex = Regex("<[^<>\\s@]+@[^<>\\s@]+>")

internal fun markdownTokenReplacement(content: String, node: org.intellij.markdown.ast.ASTNode): String? {
    val raw = content.substring(node.startOffset, node.endOffset)
    return when {
        node.type == GFMTokenTypes.TILDE && node.parent?.type != GFMElementTypes.STRIKETHROUGH -> raw
        node.type == MarkdownTokenTypes.EMAIL_AUTOLINK -> raw
        node.type == MarkdownTokenTypes.LT && EmailAutolinkRegex.matchAt(content, node.startOffset) != null -> ""
        node.type == MarkdownTokenTypes.GT -> {
            val openingOffset = content.lastIndexOf('<', node.startOffset)
            val match = openingOffset.takeIf { it >= 0 }?.let { EmailAutolinkRegex.matchAt(content, it) }
            if (match?.range?.last == node.startOffset) "" else null
        }
        node.type == GFMTokenTypes.CHECK_BOX -> {
            if (raw.contains('x', ignoreCase = true)) "\u2611" else "\u2610"
        }
        else -> null
    }
}

private fun looksLikeHtmlPayload(text: String): Boolean {
    if (text.isBlank()) return false
    if (HtmlDocumentHintRegex.containsMatchIn(text)) return true
    return HtmlTagRegex.findAll(text).take(12).count() >= 6
}

private fun normalizeHtmlForEmbeddedPreview(html: String): String {
    if (html.isBlank()) return html
    val overrideCss = """
        html, body {
          margin: 0 !important;
          padding: 8px !important;
          min-height: auto !important;
          height: auto !important;
        }
        body {
          display: block !important;
          align-items: flex-start !important;
          justify-content: flex-start !important;
          overflow: auto !important;
        }
        .container {
          align-items: flex-start !important;
          justify-content: flex-start !important;
          height: auto !important;
          min-height: auto !important;
          width: 100% !important;
          margin: 0 !important;
        }
    """.trimIndent()

    val styleBlock = "<style>$overrideCss</style>"
    return if (html.contains("</head>", ignoreCase = true)) {
        html.replaceFirst(Regex("(?i)</head>"), "$styleBlock</head>")
    } else {
        "<head>$styleBlock</head>$html"
    }
}

private fun preserveRawHtmlPayload(markdown: String): String {
    if (markdown.isBlank()) return markdown
    if ("```" in markdown) return markdown

    val looksLikeHtmlDocument = HtmlDocumentHintRegex.containsMatchIn(markdown)
    val htmlTagCount = HtmlTagRegex.findAll(markdown).take(16).count()
    if (!looksLikeHtmlDocument && htmlTagCount < 8) return markdown

    return buildString(markdown.length + 16) {
        append("```text\n")
        append(markdown.trimEnd())
        append("\n```")
    }
}

@Composable
private fun ReasoningBlock(part: Part.Reasoning) {
    val expandByDefault = LocalExpandReasoning.current
    var expanded by rememberSaveable(part.id, expandByDefault) { mutableStateOf(expandByDefault) }
    val extractedTitle = extractReasoningTitle(part.text)
    val reasoningBody = if (extractedTitle != null) removeReasoningTitleLine(part.text) else part.text
    val hasReasoningBody = reasoningBody.isNotBlank()
    val reasoningTitle = extractedTitle ?: part.time?.let { time ->
        time.end?.let { end ->
            val durationMs = (end - time.start).coerceAtLeast(0)
            val duration = if (durationMs < 1000) {
                "${durationMs}ms"
            } else {
                String.format(Locale.getDefault(), "%.1fs", durationMs / 1000.0)
            }
            stringResource(R.string.chat_reasoning_complete, duration)
        }
    } ?: stringResource(R.string.chat_reasoning_active)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { modifier ->
                    if (hasReasoningBody) {
                        modifier.expandableToolHeader(expanded) { expanded = !expanded }
                    } else modifier
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Text(
                text = reasoningTitle,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (hasReasoningBody) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.chat_collapse else R.string.chat_expand),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                )
            }
        }
        AnimatedVisibility(visible = expanded && hasReasoningBody) {
            MarkdownContent(
                markdown = reasoningBody,
                textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                isUser = false,
            )
        }
    }
}

@Composable
private fun ToolCallCard(tool: Part.Tool) {
    val isAmoled = isAmoledTheme()
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

    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by remember(autoExpand) { mutableStateOf(autoExpand) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (tool.state is ToolState.Completed || tool.state is ToolState.Error) {
                            mod.expandableToolHeader(expanded) {
                                performHaptic(hapticView, hapticOn)
                                expanded = !expanded
                            }
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
                    PulsingDotsIndicator(
                        modifier = Modifier.padding(end = 2.dp),
                        dotSize = 5.dp,
                        dotSpacing = 3.dp,
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
                            color = toolOutputContainerColor(isAmoled),
                            border = if (isAmoled) BorderStroke(1.dp, stateColor.copy(alpha = 0.6f)) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = output.take(3000),
                                style = CodeTypography.copy(
                                    fontSize = 11.sp,
                                    color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                modifier = Modifier
                                    .padding(8.dp)
                                    .codeHorizontalScroll()
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

@Composable
private fun ApplyPatchToolCard(tool: Part.Tool) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val metadata = when (val state = tool.state) {
        is ToolState.Running -> state.metadata
        is ToolState.Completed -> state.metadata
        is ToolState.Error -> state.metadata
        is ToolState.Pending -> null
    }
    val files = metadata?.get("files")?.let { element ->
        runCatching { element.jsonArray.mapNotNull { it.jsonObject } }.getOrDefault(emptyList())
    }.orEmpty()
    val patch = metadata?.get("diff")?.jsonPrimitive?.contentOrNull
        ?: input["patchText"]?.jsonPrimitive?.contentOrNull
        ?: metadata?.get("patch")?.jsonPrimitive?.contentOrNull
        ?: input["patch"]?.jsonPrimitive?.contentOrNull
        ?: extractToolOutput(tool)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull
        ?: files.firstOrNull()?.get("relativePath")?.jsonPrimitive?.contentOrNull
        ?: files.firstOrNull()?.get("filePath")?.jsonPrimitive?.contentOrNull
    val stats = remember(patch, files) {
        if (files.isNotEmpty()) {
            files.sumOf { it["additions"]?.jsonPrimitive?.intOrNull ?: 0 } to
                files.sumOf { it["deletions"]?.jsonPrimitive?.intOrNull ?: 0 }
        } else {
            countUnifiedPatchChanges(patch)
        }
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val autoExpand = LocalCollapseTools.current
    var expanded by rememberSaveable(tool.id, autoExpand) { mutableStateOf(autoExpand) }
    val isRunning = tool.state is ToolState.Running
    val hasContent = patch.isNotBlank()

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let {
                        if (hasContent) it.expandableToolHeader(expanded) {
                            performHaptic(hapticView, hapticOn)
                            expanded = !expanded
                        } else it
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(3.dp))
                Text(
                    text = buildString {
                        append(stringResource(R.string.tool_apply_patch))
                        filePath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (stats.first > 0 || stats.second > 0) DiffChangesInline(stats.first, stats.second)
                if (isRunning) {
                    Spacer(Modifier.width(4.dp))
                    PulsingDotsIndicator(
                        modifier = Modifier.padding(end = 2.dp),
                        dotSize = 5.dp,
                        dotSpacing = 3.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else if (hasContent) {
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(patch))
                            android.widget.Toast.makeText(context, R.string.chat_copied_clipboard, android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(22.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.chat_copy),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                        )
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded && hasContent,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                UnifiedPatchView(patch)
            }
        }
    }
}

internal fun countUnifiedPatchChanges(patch: String): Pair<Int, Int> {
    var additions = 0
    var deletions = 0
    patch.lineSequence().forEach { line ->
        if (line.startsWith("+") && !line.startsWith("+++")) additions++
        if (line.startsWith("-") && !line.startsWith("---")) deletions++
    }
    return additions to deletions
}

@Composable
private fun UnifiedPatchView(patch: String) {
    val isAmoled = isAmoledTheme()
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = toolOutputContainerColor(isAmoled),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp)
            .heightIn(max = (LocalConfiguration.current.screenHeightDp.dp / 2).coerceAtLeast(200.dp)),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .codeHorizontalScroll()
                    .verticalScroll(rememberScrollState())
                    .padding(4.dp),
            ) {
                patch.lineSequence().forEach { line ->
                    val added = line.startsWith("+") && !line.startsWith("+++")
                    val removed = line.startsWith("-") && !line.startsWith("---")
                    val header = line.startsWith("@@") || line.startsWith("***") || line.startsWith("---") || line.startsWith("+++")
                    Text(
                        text = line,
                        style = CodeTypography.copy(
                            color = when {
                                added -> Color(0xFF2E7D32)
                                removed -> Color(0xFFC62828)
                                header -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    added -> Color(0xFF4CAF50).copy(alpha = 0.10f)
                                    removed -> Color(0xFFE53935).copy(alpha = 0.10f)
                                    else -> Color.Transparent
                                },
                            ),
                    )
                }
            }
        }
    }
}

/**
 * Edit tool card — shows file path + diff with red/green colored lines.
 * Like WebUI: trigger = "Edit" + filename + DiffChanges, content = diff view.
 */
@Composable
private fun EditToolCard(tool: Part.Tool) {
    val isAmoled = isAmoledTheme()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
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
    val fileDiff = metadata?.get("filediff")?.jsonObject
    val authoritativePatch = fileDiff?.get("patch")?.jsonPrimitive?.contentOrNull
        ?: metadata?.get("diff")?.jsonPrimitive?.contentOrNull
    val filediffBefore = fileDiff?.get("before")?.jsonPrimitive?.contentOrNull
    val filediffAfter = fileDiff?.get("after")?.jsonPrimitive?.contentOrNull

    val diffBefore = filediffBefore ?: oldString
    val diffAfter = filediffAfter ?: newString

    // Compute additions/deletions
    val diffLines = remember(diffBefore, diffAfter) {
        computeSimpleDiff(
            if (diffBefore.isBlank()) emptyList() else diffBefore.lines(),
            if (diffAfter.isBlank()) emptyList() else diffAfter.lines(),
        )
    }
    val additions = fileDiff?.get("additions")?.jsonPrimitive?.intOrNull
        ?: authoritativePatch?.let(::countUnifiedPatchChanges)?.first
        ?: diffLines.count { it.type == DiffLineType.ADDED }
    val deletions = fileDiff?.get("deletions")?.jsonPrimitive?.intOrNull
        ?: authoritativePatch?.let(::countUnifiedPatchChanges)?.second
        ?: diffLines.count { it.type == DiffLineType.REMOVED }

    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by remember(autoExpand) { mutableStateOf(autoExpand) }
    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = !authoritativePatch.isNullOrBlank() || diffBefore.isNotBlank() || diffAfter.isNotBlank()

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasContent && !isRunning) mod.expandableToolHeader(expanded) {
                            performHaptic(hapticView, hapticOn)
                            expanded = !expanded
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
                        PulsingDotsIndicator(
                            modifier = Modifier.padding(end = 2.dp),
                            dotSize = 5.dp,
                            dotSpacing = 3.dp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else if (hasContent) {
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString("Edit: $filePath\n\n${authoritativePatch ?: diffAfter}"))
                                android.widget.Toast.makeText(context, R.string.chat_copied_clipboard, android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(22.dp),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.chat_copy),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                            )
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
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
                            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer,
                            border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ErrorPayloadContent(
                                text = errorText,
                                textStyle = CodeTypography.copy(
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                                textColor = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    } else {
                        if (!authoritativePatch.isNullOrBlank()) {
                            UnifiedPatchView(authoritativePatch)
                        } else {
                            DiffView(before = diffBefore, after = diffAfter)
                        }
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
    val isAmoled = isAmoledTheme()
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
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
    ) {
        Column(
            modifier = Modifier
                .codeHorizontalScroll()
                .verticalScroll(rememberScrollState())
                .padding(4.dp)
        ) {
            for (line in diffLines) {
                val (prefix, text, bgColor, fgColor) = when (line.type) {
                    DiffLineType.REMOVED -> DiffLineStyle("-", line.text, delBg, delColor)
                    DiffLineType.ADDED -> DiffLineStyle("+", line.text, addBg, addColor)
                    DiffLineType.UNCHANGED -> DiffLineStyle(" ", line.text, Color.Transparent, if (isAmoled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f))
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
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val shortPath = filePath.substringAfterLast('/')
    val content = input["content"]?.jsonPrimitive?.contentOrNull ?: ""

    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by remember(autoExpand) { mutableStateOf(autoExpand) }
    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = content.isNotBlank()

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasContent && !isRunning) mod.expandableToolHeader(expanded) {
                            performHaptic(hapticView, hapticOn)
                            expanded = !expanded
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
                    PulsingDotsIndicator(
                        modifier = Modifier.padding(end = 2.dp),
                        dotSize = 5.dp,
                        dotSpacing = 3.dp,
                        color = MaterialTheme.colorScheme.tertiary,
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

            AnimatedVisibility(visible = expanded && hasContent) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = toolOutputContainerColor(isAmoled),
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 400.dp)
                ) {
                    Text(
                        text = content.take(5000),
                        style = CodeTypography.copy(fontSize = 12.sp, color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer),
                        modifier = Modifier
                            .padding(8.dp)
                            .codeHorizontalScroll()
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
    val isAmoled = isAmoledTheme()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    val input = extractToolInput(tool)
    val command = input["command"]?.jsonPrimitive?.contentOrNull ?: ""
    val output = (tool.state as? ToolState.Running)
        ?.metadata
        ?.get("output")
        ?.jsonPrimitive
        ?.contentOrNull
        ?: extractToolOutput(tool)
    val cleanedOutput = output.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
    val displayText = buildString {
        if (command.isNotBlank()) {
            append("$ $command")
        }
        if (cleanedOutput.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(cleanedOutput.take(5000))
        }
    }

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by rememberSaveable(tool.id, autoExpand) { mutableStateOf(autoExpand) }
    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = command.isNotBlank() || output.isNotBlank()

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasContent) mod.expandableToolHeader(expanded) {
                            performHaptic(hapticView, hapticOn)
                            expanded = !expanded
                        } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Error else Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = serverTitle ?: stringResource(R.string.tool_shell),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (isRunning) {
                    PulsingDotsIndicator(
                        modifier = Modifier.padding(end = 2.dp),
                        dotSize = 5.dp,
                        dotSpacing = 3.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else if (hasContent) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (displayText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(displayText))
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.chat_copied_clipboard),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.chat_copy),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                                )
                            }
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded && hasContent,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = toolOutputContainerColor(isAmoled),
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp.dp / 2).coerceAtLeast(200.dp))
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = displayText,
                            style = CodeTypography.copy(color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer),
                            modifier = Modifier
                                .padding(4.dp)
                                .codeHorizontalScroll()
                        )
                    }
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
    val isAmoled = isAmoledTheme()
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
    val output = extractToolOutput(tool)
    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by rememberSaveable(tool.id, autoExpand) { mutableStateOf(autoExpand) }
    val hasContent = output.isNotBlank()

    // Build args string like WebUI: [offset=N, limit=N]
    val args = buildList {
        offset?.let { add("offset=$it") }
        limit?.let { add("limit=$it") }
    }.takeIf { it.isNotEmpty() }?.joinToString(", ", "[", "]")

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let {
                        if (hasContent) it.expandableToolHeader(expanded) {
                            performHaptic(hapticView, hapticOn)
                            expanded = !expanded
                        } else it
                    },
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
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
                if (isRunning) {
                    PulsingDotsIndicator(
                        modifier = Modifier.padding(end = 2.dp),
                        dotSize = 5.dp,
                        dotSpacing = 3.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else if (hasContent) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded && hasContent,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = toolOutputContainerColor(isAmoled),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp.dp / 2).coerceAtLeast(200.dp))
                        .verticalScroll(rememberScrollState()),
                ) {
                    SelectionContainer {
                        Text(
                            text = output,
                            style = CodeTypography.copy(fontSize = 11.sp),
                            modifier = Modifier.padding(4.dp).codeHorizontalScroll(),
                        )
                    }
                }
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
    val isAmoled = isAmoledTheme()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
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

    val label = when (tool.tool) {
        "glob" -> serverTitle ?: stringResource(R.string.tool_find_files)
        "grep" -> serverTitle ?: stringResource(R.string.tool_search_code)
        else -> serverTitle ?: tool.tool
    }
    val title = pattern?.takeIf { it.isNotBlank() }?.let {
        "$label · ${if (it.length > 40) it.take(37) + "..." else it}"
    } ?: label

    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by rememberSaveable(tool.id, autoExpand) { mutableStateOf(autoExpand) }
    val isRunning = tool.state is ToolState.Running
    val hasOutput = output.isNotBlank()
    val hasContent = hasOutput || pattern != null || !dirPath.isNullOrBlank() || include != null

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasContent) mod.expandableToolHeader(expanded) {
                            performHaptic(hapticView, hapticOn)
                            expanded = !expanded
                        } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (isRunning) {
                    PulsingDotsIndicator(
                        modifier = Modifier.padding(end = 2.dp),
                        dotSize = 5.dp,
                        dotSpacing = 3.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else if (hasContent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(if (hasOutput) output else title))
                                android.widget.Toast.makeText(context, R.string.chat_copied_clipboard, android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(22.dp),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.chat_copy),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                            )
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded && hasContent,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (pattern != null || !dirPath.isNullOrBlank() || include != null) {
                        Surface(shape = RoundedCornerShape(4.dp), color = toolOutputContainerColor(isAmoled)) {
                            Text(
                                text = buildList {
                                    pattern?.let { add("pattern: $it") }
                                    dirPath?.takeIf { it.isNotBlank() }?.let { add("path: $it") }
                                    include?.let { add("include: $it") }
                                }.joinToString("\n"),
                                style = CodeTypography.copy(fontSize = 11.sp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (hasOutput) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = toolOutputContainerColor(isAmoled),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = (LocalConfiguration.current.screenHeightDp.dp / 2).coerceAtLeast(200.dp))
                                .verticalScroll(rememberScrollState()),
                        ) {
                            SelectionContainer {
                                if (tool.tool == "grep") {
                                    Box(Modifier.padding(4.dp)) {
                                        MarkdownContent(output, MaterialTheme.colorScheme.onSecondaryContainer, false)
                                    }
                                } else {
                                    Text(
                                        text = output,
                                        style = CodeTypography.copy(fontSize = 11.sp),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
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
private fun TaskToolCard(
    tool: Part.Tool,
    onNavigateToChildSession: (String) -> Unit,
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val description = input["description"]?.jsonPrimitive?.contentOrNull
    val output = extractToolOutput(tool)

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by remember(autoExpand) { mutableStateOf(autoExpand) }
    val isRunning = tool.state is ToolState.Running
    val hasOutput = output.isNotBlank()
    val childSessionId = when (val state = tool.state) {
        is ToolState.Running -> state.metadata?.get("sessionId")?.jsonPrimitive?.contentOrNull
        is ToolState.Completed -> state.metadata?.get("sessionId")?.jsonPrimitive?.contentOrNull
        is ToolState.Error -> state.metadata?.get("sessionId")?.jsonPrimitive?.contentOrNull
        is ToolState.Pending -> null
    }?.takeIf { it.isNotBlank() }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        when {
                            childSessionId != null -> mod.clickable {
                                performHaptic(hapticView, hapticOn)
                                onNavigateToChildSession(childSessionId)
                            }
                            hasOutput && !isRunning -> mod.clickable {
                                performHaptic(hapticView, hapticOn)
                                expanded = !expanded
                            }
                            else -> mod
                        }
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
                    PulsingDotsIndicator(
                        modifier = Modifier.padding(end = 2.dp),
                        dotSize = 5.dp,
                        dotSpacing = 3.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else if (childSessionId != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                    )
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
                    color = toolOutputContainerColor(isAmoled),
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 300.dp)
                ) {
                    Text(
                        text = output.take(5000),
                        style = CodeTypography.copy(fontSize = 12.sp, color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer),
                        modifier = Modifier
                            .padding(8.dp)
                            .codeHorizontalScroll()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
@Composable
private fun TodoListCard(tool: Part.Tool) {
    val isAmoled = isAmoledTheme()
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
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .expandableToolHeader(expanded) {
                        performHaptic(hapticView, hapticOn)
                        expanded = !expanded
                    },
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

@Composable
private fun PatchCard(patch: Part.Patch) {
    val isAmoled = isAmoledTheme()
    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by remember(autoExpand) { mutableStateOf(autoExpand) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .expandableToolHeader(expanded) {
                        performHaptic(hapticView, hapticOn)
                        expanded = !expanded
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
                    for (filePath in patch.files) {
                        Text(
                            text = filePath.substringAfterLast('/'),
                            style = CodeTypography.copy(
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact horizontal row of image thumbnails with tap-to-preview.
 */
@Composable
private fun ImageThumbnailRow(
    imageFiles: List<Part.File>,
) {
    var previewIndex by remember { mutableStateOf(-1) }
    val requestSaveImage = LocalImageSaveRequest.current

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
        val imageBytes = remember(file.url) { decodePartFileBytes(file) }
        val bitmap = remember(imageBytes) {
            imageBytes?.let { bytes -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        }

        if (bitmap != null) {
            ImagePreviewDialog(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = file.filename ?: stringResource(R.string.chat_image),
                onDismiss = { previewIndex = -1 },
                onSave = {
                    if (imageBytes != null) {
                        requestSaveImage(imageBytes, file.mime, file.filename)
                    }
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ImagePreviewDialog(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    contentDescription: String?,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val isAmoled = isAmoledTheme()
    AppDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
            Box(modifier = Modifier.padding(14.dp)) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Fit,
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val actionContainerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    val actionBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isAmoled) 0.85f else 0.8f)
                    val actionTintColor = MaterialTheme.colorScheme.onSurface

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = actionContainerColor,
                        border = BorderStroke(1.dp, actionBorderColor),
                    ) {
                        IconButton(onClick = onSave, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = stringResource(R.string.chat_save_image),
                                tint = actionTintColor,
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = actionContainerColor,
                        border = BorderStroke(1.dp, actionBorderColor),
                    ) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = actionTintColor,
                            )
                        }
                    }
                }
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
    val isAmoled = isAmoledTheme()
    val containerColor = if (isAmoled) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = if (isAmoled) {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)
    }
    val contentColor = if (isAmoled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AttachFile,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = file.filename
                    ?: file.url?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: file.mime,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    permission: SseEvent.PermissionAsked,
    position: String,
    onReply: (reply: String, onResult: (Boolean) -> Unit) -> Unit,
) {
    val isAmoled = isAmoledTheme()
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.tertiaryContainer
    val contentColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onTertiaryContainer
    var submitting by remember(permission.sessionId, permission.id) { mutableStateOf(false) }
    var confirmAlways by remember(permission.sessionId, permission.id) { mutableStateOf(false) }

    if (confirmAlways) {
        ChatDialog(onDismiss = { confirmAlways = false }) {
            Text(stringResource(R.string.permission_always_confirm_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.permission_always_confirm_message))
                if (permission.always.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.permission_always_scope, permission.always.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppSecondaryButton(onClick = { confirmAlways = false }) { Text(stringResource(R.string.cancel)) }
                AppPrimaryButton(onClick = {
                    confirmAlways = false
                    submitting = true
                    onReply("always") { success -> submitting = false }
                }) {
                    Text(stringResource(R.string.permission_allow_always))
                }
            }
        }
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)) else null,
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
                    tint = if (isAmoled) MaterialTheme.colorScheme.tertiary else contentColor
                )
                Text(
                    text = stringResource(R.string.permission_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor
                )
                Spacer(Modifier.weight(1f))
                Text(position, style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
            }
            Text(
                text = permission.permission,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
            if (permission.patterns.isNotEmpty()) {
                Text(
                    text = permission.patterns.joinToString(", "),
                    style = CodeTypography.copy(
                        fontSize = 11.sp,
                        color = contentColor.copy(alpha = 0.7f)
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppSecondaryButton(
                    onClick = {
                        performHaptic(hapticView, hapticOn)
                        submitting = true
                        onReply("reject") { submitting = false }
                    },
                    enabled = !submitting,
                    modifier = Modifier.weight(1f),
                    destructive = true,
                ) {
                    Text(stringResource(R.string.permission_deny), maxLines = 1)
                }
                AppSecondaryButton(
                    onClick = {
                        performHaptic(hapticView, hapticOn)
                        submitting = true
                        onReply("once") { submitting = false }
                    },
                    enabled = !submitting,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.permission_allow_once), maxLines = 1)
                }
                AppPrimaryButton(
                    onClick = { performHaptic(hapticView, hapticOn); confirmAlways = true },
                    enabled = !submitting,
                    modifier = Modifier.weight(1f),
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

internal fun isWorkingSessionStatus(status: SessionStatus): Boolean =
    status is SessionStatus.Busy || status is SessionStatus.Retry

internal fun retryDelaySeconds(nextAtMillis: Long, nowMillis: Long): Long =
    ((nextAtMillis - nowMillis).coerceAtLeast(0) + 999) / 1_000

@Composable
private fun RetryStatusBanner(retry: SessionStatus.Retry) {
    var nowMillis by remember(retry.next) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retry.next) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            val delayMillis = retry.next - nowMillis
            if (delayMillis <= 0) break
            kotlinx.coroutines.delay(minOf(1_000L, delayMillis))
        }
    }
    val remainingSeconds = retryDelaySeconds(retry.next, nowMillis)
    val isAmoled = isAmoledTheme()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.sessions_retrying),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = retry.message.ifBlank { stringResource(R.string.error_unknown) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (remainingSeconds > 0) {
                        stringResource(R.string.chat_retry_waiting, remainingSeconds, retry.attempt)
                    } else {
                        stringResource(R.string.chat_retry_now, retry.attempt)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatInputBar(
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isSending: Boolean,
    isBusy: Boolean = false,
    sessionStatus: SessionStatus = SessionStatus.Idle,
    messages: List<ChatMessage> = emptyList(),
    attachments: List<ImageAttachment> = emptyList(),
    onAttach: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onSaveAttachment: (bytes: ByteArray, mime: String, filename: String?) -> Unit = { _, _, _ -> },
    modelLabel: String = "",
    selectedProviderId: String? = null,
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
    onSlashCommand: (SlashCommand) -> Unit = {},
    inputMode: ChatInputMode = ChatInputMode.NORMAL,
    onInputModeChange: (ChatInputMode) -> Unit = {},
    contextWindow: Int = 0,
    lastContextTokens: Int = 0,
    contextUsage: ContextUsageDetails = ContextUsageDetails(),
) {
    val isAmoled = isAmoledTheme()
    val isShellMode = inputMode == ChatInputMode.SHELL
    // Rotate placeholder hint every 4 seconds
    val hintIndex = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(4000)
            hintIndex.intValue = (hintIndex.intValue + 1) % placeholderHintResIds.size
        }
    }
    val placeholder = if (isShellMode) {
        stringResource(R.string.chat_shell_placeholder)
    } else {
        stringResource(placeholderHintResIds[hintIndex.intValue])
    }

    val text = textFieldValue.text
    val showInlineAttach = text.isEmpty() && !isShellMode
    val hasDraft = text.isNotBlank() || attachments.isNotEmpty()
    val action = composerAction(isBusy, isSending, hasDraft, isShellMode)
    val canSend = action == ComposerAction.SEND
    val retryStatus = sessionStatus as? SessionStatus.Retry
    var showContextDetails by remember { mutableStateOf(false) }
    var previewAttachmentIndex by remember { mutableStateOf(-1) }

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
    val showSlashSuggestions = !isShellMode && text.startsWith("/") && !text.contains(" ")
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

        retryStatus?.let { retry ->
            RetryStatusBanner(retry)
        }

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
                    .background(if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh)
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
            visible = !isShellMode && fileSearchResults.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val configuration = LocalConfiguration.current
            val maxHeight = (configuration.screenHeightDp * 0.4f).dp

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .background(if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh)
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
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }

        // Working status row; context usage lives with the model controls below.
        val showContext = contextWindow > 0 && lastContextTokens > 0
        val contextPercentage = if (showContext) {
            Math.round(lastContextTokens.toDouble() / contextWindow * 100).toInt()
        } else {
            0
        }
        val contextColor = when {
            contextPercentage >= 90 -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
            contextPercentage >= 70 -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
        }
        if (isBusy && retryStatus == null) {
            val lastRunningTool = if (isBusy) {
                messages.asReversed().firstNotNullOfOrNull { message ->
                    message.parts.filterIsInstance<Part.Tool>().lastOrNull { it.state is ToolState.Running }
                }
            } else null

            val statusText = if (isBusy) {
                if (lastRunningTool != null) {
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
            } else null

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: working status
                if (isBusy && statusText != null) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PulsingDotsIndicator(
                            dotSize = 4.dp,
                            dotSpacing = 3.dp,
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
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Agent + model + variant selectors followed by context usage.
            if (modelLabel.isNotEmpty() || agents.size > 1 || showContext) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Keep the full selector sequence horizontally scrollable on narrow screens.
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Agent selector — single button, tap to cycle
                        // Fixed width: all agent names rendered invisible to reserve max width
                        if (agents.size > 1) {
                            val agentColor = agentColor(selectedAgent, agents)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(agentColor.copy(alpha = 0.18f))
                                    .clickable {
                                        val currentIndex = agents.indexOfFirst { it.name == selectedAgent }
                                        val nextIndex = (currentIndex + 1) % agents.size
                                        onAgentSelect(agents[nextIndex].name)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                // Invisible ghost texts for all agent names — fixes width to the widest
                                agents.forEach { agent ->
                                    Text(
                                        text = agent.name.replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Transparent
                                    )
                                }
                                // Visible label with accent color
                                Text(
                                    text = selectedAgent.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = agentColor
                                )
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
                                if (selectedProviderId != null) {
                                    ProviderIcon(
                                        providerId = selectedProviderId,
                                        size = 13.dp,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
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

                        if (showContext) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .clickable { showContextDetails = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    progress = { (lastContextTokens.toFloat() / contextWindow).coerceIn(0f, 1f) },
                                    modifier = Modifier.size(27.dp),
                                    color = contextColor,
                                    trackColor = contextColor.copy(alpha = 0.16f),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = "$contextPercentage%",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                    color = contextColor,
                                    maxLines = 1,
                                )
                            }
                        }
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
                                .width(if (attachment.isImage) 56.dp else 180.dp)
                                .height(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                        ) {
                            if (attachment.isImage) {
                                AsyncImage(
                                    model = imageThumbnailModel(attachment),
                                    contentDescription = attachment.filename,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable { previewAttachmentIndex = index },
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 10.dp, end = 26.dp, top = 8.dp, bottom = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = if (attachment.mime == "application/pdf") {
                                                Icons.Default.PictureAsPdf
                                            } else {
                                                Icons.Default.Description
                                            },
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = attachment.filename,
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (attachment.sizeBytes > 0) {
                                                Text(
                                                    text = formatFileSize(attachment.sizeBytes),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
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

            if (previewAttachmentIndex >= 0 && previewAttachmentIndex < attachments.size) {
                val attachment = attachments[previewAttachmentIndex]
                val imageBytes = remember(attachment.dataUrl) { decodeDataUrlBytes(attachment.dataUrl) }
                val bitmap = remember(imageBytes) {
                    imageBytes?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                }

                if (bitmap != null) {
                    ImagePreviewDialog(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = attachment.filename,
                        onDismiss = { previewAttachmentIndex = -1 },
                        onSave = {
                            if (imageBytes != null) {
                                onSaveAttachment(imageBytes, attachment.mime, attachment.filename)
                            }
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = isShellMode,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isAmoled) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        )
                        .then(
                            if (isAmoled) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(10.dp),
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.chat_shell_mode_hold_send_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    if (isShellMode) {
                        VisualTransformation.None
                    } else {
                        FileMentionVisualTransformation(confirmedFilePaths, mentionHighlightColor, mentionBgColor)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (isAmoled) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                        .then(
                            when {
                                isShellMode -> Modifier.border(
                                    width = if (isAmoled) 1.5.dp else 1.dp,
                                    color = if (isAmoled) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                    },
                                    shape = RoundedCornerShape(22.dp)
                                )
                                isAmoled -> Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(22.dp)
                                )
                                else -> Modifier
                            }
                        )
                ) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = onTextFieldValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 16.dp,
                                end = if (showInlineAttach) 48.dp else 16.dp,
                                top = 10.dp,
                                bottom = 10.dp,
                            )
                            .heightIn(min = 24.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = if (isShellMode) FontFamily.Monospace else FontFamily.Default
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
                    if (showInlineAttach) {
                        Box(
                            modifier = Modifier.matchParentSize(),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            IconButton(
                                onClick = onAttach,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Default.AttachFile,
                                    contentDescription = stringResource(R.string.chat_attach),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                                )
                            }
                        }
                    }
                }

                // Send button — tap to send, long-press toggles shell mode
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (action == ComposerAction.STOP) {
                                if (isAmoled) Color.Transparent else MaterialTheme.colorScheme.errorContainer
                            } else if (isShellMode && !isSending) {
                                if (isAmoled) {
                                    Color.Black
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                }
                            } else {
                                Color.Transparent
                            }
                        )
                        .then(
                            if (action == ComposerAction.STOP && isAmoled) {
                                Modifier.border(
                                    width = 1.2.dp,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.88f),
                                    shape = RoundedCornerShape(24.dp),
                                )
                            } else if (isShellMode && !isSending) {
                                Modifier.border(
                                    width = if (isAmoled) 1.2.dp else 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (isAmoled) 0.88f else 0.75f),
                                    shape = RoundedCornerShape(24.dp),
                                )
                            } else {
                                Modifier
                            }
                        )
                        .combinedClickable(
                            onClick = {
                                when (action) {
                                    ComposerAction.SEND -> onSend()
                                    ComposerAction.STOP -> onStop()
                                    ComposerAction.DISABLED -> Unit
                                }
                            },
                            onLongClick = {
                                onInputModeChange(
                                    if (isShellMode) ChatInputMode.NORMAL else ChatInputMode.SHELL
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSending) {
                        BreathingCircleIndicator(
                            size = 20.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (action == ComposerAction.STOP) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = stringResource(R.string.chat_stop),
                            modifier = Modifier.size(20.dp),
                            tint = if (isAmoled) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.88f)
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (isShellMode) {
                                stringResource(R.string.chat_send_shell)
                            } else {
                                stringResource(R.string.chat_send)
                            },
                            modifier = Modifier.size(20.dp),
                            tint = if (canSend) {
                                MaterialTheme.colorScheme.primary
                            } else if (isShellMode && isAmoled && !isSending) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            }
                        )

                    }
                }
            }
        }
    }
    if (showContextDetails) {
        ContextUsageDialog(
            usage = contextUsage,
            contextWindow = contextWindow,
            onDismiss = { showContextDetails = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextUsageDialog(
    usage: ContextUsageDetails,
    contextWindow: Int,
    onDismiss: () -> Unit,
) {
    val isAmoled = isAmoledTheme()
    val used = usage.currentTotal
    val percentage = if (contextWindow > 0) (used.toDouble() / contextWindow * 100).roundToInt() else 0
    val remaining = (contextWindow - used).coerceAtLeast(0)
    val progressColor = when {
        percentage >= 90 -> MaterialTheme.colorScheme.error
        percentage >= 70 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    AppDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
    ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.chat_context_details), style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "$percentage%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = progressColor,
                    )
                    Text(
                        text = stringResource(
                            R.string.chat_context_used,
                            formatTokenCount(used),
                            formatTokenCount(contextWindow),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { if (contextWindow > 0) (used.toFloat() / contextWindow).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = progressColor,
                    trackColor = progressColor.copy(alpha = 0.16f),
                )
                Text(
                    text = stringResource(R.string.chat_context_remaining, formatTokenCount(remaining)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text(stringResource(R.string.chat_context_current_turn), style = MaterialTheme.typography.labelLarge)
                ContextTokenRow(stringResource(R.string.chat_context_input), usage.input)
                ContextTokenRow(stringResource(R.string.chat_context_output), usage.output)
                if (usage.reasoning > 0) ContextTokenRow(stringResource(R.string.chat_context_reasoning), usage.reasoning)
                if (usage.cacheRead > 0) ContextTokenRow(stringResource(R.string.chat_context_cache_read), usage.cacheRead)
                if (usage.cacheWrite > 0) ContextTokenRow(stringResource(R.string.chat_context_cache_write), usage.cacheWrite)
                HorizontalDivider()
                Text(stringResource(R.string.chat_context_session_totals), style = MaterialTheme.typography.labelLarge)
                ContextTokenRow(stringResource(R.string.chat_context_tokens_processed), usage.sessionTotal)
                ContextTokenRow(
                    stringResource(R.string.chat_context_messages),
                    usage.userMessages + usage.assistantMessages,
                    raw = true,
                )
                if (usage.totalCost > 0) {
                    ContextTokenRow(
                        stringResource(R.string.chat_context_cost),
                        0,
                        value = String.format(Locale.US, "$%.4f", usage.totalCost),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    AppSecondaryButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
    }
}

@Composable
private fun ContextTokenRow(label: String, tokens: Int, raw: Boolean = false, value: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: if (raw) tokens.toString() else formatTokenCount(tokens), style = MaterialTheme.typography.bodySmall)
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
    position: String,
    onSubmit: (answers: List<List<String>>, onResult: (Boolean) -> Unit) -> Unit,
    onReject: (onResult: (Boolean) -> Unit) -> Unit,
) {
    val isAmoled = isAmoledTheme()
    val isSingle = question.questions.size == 1 && question.questions[0].multiple != true

    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    // Prevent multiple submissions
    var submitted by remember(question.sessionId, question.id) { mutableStateOf(false) }

    // Track answers per question
    val answersPerQuestion = remember {
        mutableStateListOf<List<String>>().apply {
            repeat(question.questions.size) { add(emptyList()) }
        }
    }

    val containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
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
                Spacer(Modifier.weight(1f))
                Text(position, style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
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
                                    performHaptic(hapticView, hapticOn)
                                    if (isSingle) {
                                        submitted = true
                                        onSubmit(listOf(listOf(option.label))) { submitted = false }
                                    } else {
                                        if (index < answersPerQuestion.size) {
                                            answersPerQuestion[index] = listOf(option.label)
                                        }
                                    }
                                }
                            },
                                enabled = !submitted,
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) accentColor.copy(alpha = 0.12f) else if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                border = if (!isSelected && isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)) else null,
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
                                                    performHaptic(hapticView, hapticOn)
                                                    if (isSingle) {
                                                        submitted = true
                                                        onSubmit(listOf(listOf(trimmed))) { submitted = false }
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
                        performHaptic(hapticView, hapticOn)
                        submitted = true
                        onReject { submitted = false }
                    },
                    enabled = !submitted
                ) {
                    Text(stringResource(R.string.chat_dismiss), style = MaterialTheme.typography.labelMedium)
                }
                if (!isSingle) {
                    AppPrimaryButton(
                        onClick = {
                            performHaptic(hapticView, hapticOn)
                            submitted = true
                            onSubmit(answersPerQuestion.map { it.toList() }) { submitted = false }
                        },
                        enabled = answersPerQuestion.any { it.isNotEmpty() } && !submitted,
                    ) {
                        Text(stringResource(R.string.question_submit), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
