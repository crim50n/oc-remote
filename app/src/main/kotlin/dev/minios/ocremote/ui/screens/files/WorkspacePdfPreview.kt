package dev.minios.ocremote.ui.screens.files

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.logging.AppLogger as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun WorkspacePdfPreview(bytes: ByteArray, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var page by remember(bytes) { mutableIntStateOf(0) }
    var pageCount by remember(bytes) { mutableIntStateOf(0) }
    var bitmap by remember(bytes) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(bytes) { mutableStateOf(false) }
    LaunchedEffect(bytes, page) {
        bitmap = null
        failed = false
        try {
            val rendered = withContext(Dispatchers.IO) {
                val file = File.createTempFile("workspace-pdf-", ".pdf", context.cacheDir)
                try {
                    file.writeBytes(bytes)
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            renderer.openPage(page.coerceIn(0, renderer.pageCount - 1)).use { pdfPage ->
                                val scale = (1600f / maxOf(pdfPage.width, pdfPage.height)).coerceAtMost(2f)
                                val image = Bitmap.createBitmap((pdfPage.width * scale).toInt().coerceAtLeast(1),
                                    (pdfPage.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                image.eraseColor(android.graphics.Color.WHITE)
                                pdfPage.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                image to renderer.pageCount
                            }
                        }
                    }
                } finally { file.delete() }
            }
            bitmap = rendered.first
            pageCount = rendered.second
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("WorkspacePdf", "Failed to render PDF", e)
            failed = true
        }
    }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val image = bitmap
            when {
                failed -> Text(stringResource(R.string.workspace_preview_unsupported), Modifier.padding(24.dp))
                image == null -> CircularProgressIndicator()
                else -> Image(image.asImageBitmap(), stringResource(R.string.workspace_pdf_page, page + 1, pageCount),
                    Modifier.fillMaxSize().padding(12.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { page-- }, enabled = page > 0 && bitmap != null) { Text(stringResource(R.string.workspace_previous_page)) }
            if (pageCount > 0) Text(stringResource(R.string.workspace_pdf_page, page + 1, pageCount))
            TextButton(onClick = { page++ }, enabled = page + 1 < pageCount && bitmap != null) { Text(stringResource(R.string.workspace_next_page)) }
        }
    }
}
