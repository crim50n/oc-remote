package dev.minios.ocremote.ui.screens.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.WrapText
import kotlin.math.roundToInt
import dev.minios.ocremote.R
import dev.minios.ocremote.data.api.WorkspaceDiffMode
import dev.minios.ocremote.data.api.WorkspaceVcsChange

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceChangesPane(
    changes: List<WorkspaceVcsChange>, mode: WorkspaceDiffMode, committedSupported: Boolean,
    path: String?, diff: WorkspaceVcsChange?, loading: Boolean, error: String?, connected: Boolean,
    onMode: (WorkspaceDiffMode) -> Unit, onSelect: (String) -> Unit,
    wordWrap: Boolean, onWordWrapChange: (Boolean) -> Unit,
) {
    var textScale by rememberSaveable { mutableFloatStateOf(1f) }
    val zoomState = rememberTransformableState { zoom, _, _ ->
        textScale = (textScale * zoom).coerceIn(0.75f, 2.5f)
    }
    Column(Modifier.fillMaxSize()) {
        if (path == null) {
            val modes = WorkspaceDiffMode.entries.filter { committedSupported || it != WorkspaceDiffMode.COMMITTED }
            TabRow(selectedTabIndex = modes.indexOf(mode).coerceAtLeast(0), containerColor = MaterialTheme.colorScheme.surface) {
                modes.forEach { option ->
                    Tab(selected = option == mode, enabled = connected, onClick = { onMode(option) }, text = {
                        Text(stringResource(when (option) {
                            WorkspaceDiffMode.WORKING -> R.string.workspace_diff_working
                            WorkspaceDiffMode.BRANCH -> R.string.workspace_diff_branch
                            WorkspaceDiffMode.COMMITTED -> R.string.workspace_diff_committed
                        }), maxLines = 2, style = MaterialTheme.typography.labelMedium)
                    })
                }
            }
            if (changes.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.workspace_no_changes), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(changes, key = { it.path }) { file ->
                    ListItem(
                        headlineContent = { Text(file.path, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            val status = stringResource(when (file.status) {
                                "added" -> R.string.workspace_status_added
                                "deleted" -> R.string.workspace_status_deleted
                                else -> R.string.workspace_status_modified
                            })
                            Text(buildAnnotatedString {
                                append("$status · ")
                                withStyle(SpanStyle(color = Color(0xFF4CAF50))) { append("+${file.additions}") }
                                append(" ")
                                withStyle(SpanStyle(color = Color(0xFFE53935))) { append("−${file.deletions}") }
                            })
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.clickable(enabled = connected) { onSelect(file.path) },
                    )
                }
            }
        } else {
            Text(path, Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                error != null -> {
                    Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onSelect(path) }, enabled = connected) { Text(stringResource(R.string.retry)) }
                }
                diff?.patch.isNullOrBlank() -> Text(stringResource(R.string.workspace_diff_unavailable), Modifier.padding(16.dp))
                else -> {
                    val patch = requireNotNull(diff?.patch)
                    val clipboard = LocalClipboardManager.current
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(patch)) }) {
                            Icon(Lucide.Copy, contentDescription = stringResource(R.string.chat_copy))
                        }
                        Spacer(Modifier.weight(1f))
                        val zoomLabel = stringResource(R.string.workspace_diff_reset_zoom, (textScale * 100).roundToInt())
                        TextButton(onClick = { textScale = 1f }, modifier = Modifier.semantics { contentDescription = zoomLabel }) {
                            Text("${(textScale * 100).roundToInt()}%")
                        }
                        IconButton(onClick = { onWordWrapChange(!wordWrap) }, modifier = Modifier.semantics { selected = wordWrap }) {
                            Icon(Lucide.WrapText, stringResource(R.string.workspace_word_wrap),
                                tint = if (wordWrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val lines = remember(patch) { patch.lineSequence().take(20_001).toList() }
                    val horizontalScroll = rememberScrollState()
                    val textStyle = MaterialTheme.typography.bodySmall.let {
                        it.copy(fontSize = it.fontSize * textScale, lineHeight = it.lineHeight * textScale)
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()
                        .transformable(state = zoomState, canPan = { false })
                        .then(if (wordWrap) Modifier else Modifier.horizontalScroll(horizontalScroll)),
                        contentPadding = PaddingValues(12.dp)) {
                        items(lines.take(20_000)) { line ->
                            Text(line.take(4000), fontFamily = FontFamily.Monospace,
                                style = textStyle, softWrap = wordWrap,
                                modifier = if (wordWrap) Modifier.fillMaxWidth() else Modifier,
                                color = when {
                                    line.startsWith("+") -> MaterialTheme.colorScheme.tertiary
                                    line.startsWith("-") -> MaterialTheme.colorScheme.error
                                    line.startsWith("@@") -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                })
                        }
                        if (lines.size > 20_000 || lines.any { it.length > 4000 }) item {
                            Text(stringResource(R.string.workspace_diff_truncated), Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }
}
