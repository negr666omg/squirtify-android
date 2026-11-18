@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.squidify.android.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.squidify.android.App
import org.squidify.android.ui.theme.SqIcons
import org.squidify.android.util.AppLog

/**
 * Full-screen cover viewer: pinch-zoom + pan + double-tap reset, and a
 * download action that saves the original image into
 * Squidify/Downloaded Images/ (with a toast showing the path).
 */
@Composable
fun CoverViewer(
    url: String?,
    title: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var saving by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    // consume taps on the empty background so nothing behind reacts
                    detectTapGestures { }
                },
        ) {
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 8f)
                                scale = newScale
                                offset = if (newScale > 1f) {
                                    val maxX = (newScale - 1f) * size.width / 2f
                                    val maxY = (newScale - 1f) * size.height / 2f
                                    Offset(
                                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                                    )
                                } else Offset.Zero
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.05f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                            )
                        },
                )
            }

            // top bar: close, title, save
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 36.dp, start = 4.dp, end = 8.dp),
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 56.dp),
                    )
                }
                IconButton(
                    onClick = {
                        if (url == null || saving) return@IconButton
                        saving = true
                        scope.launch {
                            var result: String? = null
                            var error: String? = null
                            try {
                                val path = withContext(Dispatchers.IO) { saveCover(url, title) }
                                result = path
                            } catch (e: Exception) {
                                error = e.message ?: e.javaClass.simpleName
                                AppLog.w("CoverViewer", "Saving cover failed: $error")
                            }
                            saving = false
                            if (result != null) {
                                Toast.makeText(
                                    context,
                                    "Saved to $result",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Couldn't save the image: ${error ?: "unknown error"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Icon(
                            imageVector = SqIcons.Download,
                            contentDescription = "Save image",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Downloads the cover bytes and writes them into the images folder. */
private suspend fun saveCover(url: String, title: String?): String {
    val graph = App.graph()
    val bytes = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "*/*").build()
        graph.http.newBuilder().build().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            resp.body?.bytes() ?: throw java.io.IOException("Empty body")
        }
    }
    val ext = when {
        url.contains(".png", ignoreCase = true) -> "png"
        url.contains(".webp", ignoreCase = true) -> "webp"
        else -> "jpg"
    }
    val mime = when (ext) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    val safeTitle = (title ?: "cover")
        .replace(Regex("""[\\/:*?"<>|\u0000]"""), "_")
        .trim()
        .take(80)
        .ifBlank { "cover" }
    val fileName = "$safeTitle.$ext"
    return graph.storage.saveImage(fileName, mime, bytes)
}
