package dev.minios.ocremote.ui.screens.files
import dev.minios.ocremote.ui.components.readableContentWidth

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.data.api.FileNode

@Composable
internal fun WorkspaceFileList(
    nodes: List<FileNode>, grid: Boolean, connected: Boolean, showPath: Boolean,
    onOpen: (FileNode) -> Unit, thumbnail: suspend (FileNode) -> Bitmap?,
) {
    if (grid) {
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().readableContentWidth(),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            gridItems(nodes, key = { it.path }) { node ->
                Column(Modifier.clip(MaterialTheme.shapes.medium).alpha(if (node.ignored) 0.55f else 1f)
                    .clickable(enabled = connected) { onOpen(node) }) {
                    Surface(Modifier.fillMaxWidth().aspectRatio(1f), shape = MaterialTheme.shapes.medium,
                        color = if (dev.minios.ocremote.ui.components.isAmoledTheme()) androidx.compose.ui.graphics.Color.Black
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = if (dev.minios.ocremote.ui.components.isAmoledTheme())
                            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null) {
                        WorkspaceThumbnail(node, connected, thumbnail, Modifier.fillMaxSize(), grid = true)
                    }
                    Text(node.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                        minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    FileMetadata(node, showPath)
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().readableContentWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(nodes, key = { it.path }) { node ->
                ListItem(
                    headlineContent = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { FileMetadata(node, showPath) },
                    leadingContent = { WorkspaceThumbnail(node, connected, thumbnail,
                        Modifier.size(48.dp).clip(MaterialTheme.shapes.small), grid = false) },
                    modifier = Modifier.fillMaxWidth().alpha(if (node.ignored) 0.55f else 1f)
                        .clickable(enabled = connected) { onOpen(node) },
                )
            }
        }
    }
}

@Composable
private fun WorkspaceThumbnail(node: FileNode, connected: Boolean, loader: suspend (FileNode) -> Bitmap?,
    modifier: Modifier, grid: Boolean) {
    val image by produceState<Bitmap?>(null, node.path, node.modified, connected) { value = loader(node) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(workspaceFileIcon(workspaceFileKind(node)), null, Modifier.size(if (grid) 44.dp else 28.dp),
            tint = workspaceFileIconColor(workspaceFileKind(node)))
    }
}

@Composable
private fun FileMetadata(node: FileNode, showPath: Boolean) {
    val context = LocalContext.current
    val details = buildList {
        node.size?.let { add(android.text.format.Formatter.formatShortFileSize(context, it)) }
        node.modified?.let { add(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT).format(java.util.Date(it))) }
    }.joinToString(" · ")
    if (showPath || details.isNotEmpty()) Text(if (showPath) node.path else details,
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}
