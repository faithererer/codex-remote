package cn.codexremote.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun ChatImages(message: JSONObject, source: String, load: suspend (String) -> ByteArray) {
    val images = message.optJSONArray("images")?.objects().orEmpty()
    if (images.isEmpty()) return
    LazyRow(Modifier.fillMaxWidth().padding(top = 7.dp).testTag("chat-images"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(images, key = { it.getString("id") }) { image ->
            val id = image.getString("id")
            ImageTile("$source/$id", image.optString("name", "图片"), { load(id) })
        }
    }
}

@Composable
fun DraftImages(images: List<DraftImage>, source: String, enabled: Boolean, load: suspend (String) -> ByteArray, remove: (String) -> Unit) {
    if (images.isEmpty()) return
    LazyRow(Modifier.fillMaxWidth().padding(vertical = 5.dp).testTag("draft-images"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(images, key = { it.id }) { image ->
            Box {
                ImageTile("$source/${image.id}", image.name, { load(image.id) })
                FilledIconButton(onClick = { remove(image.id) }, enabled = enabled, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) { Icon(Icons.Outlined.Close, "移除待发图片", Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable
private fun ImageTile(source: String, name: String, load: suspend () -> ByteArray) {
    var expanded by remember(source) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(9.dp), color = Color(0xFFE7ECDD), modifier = Modifier.size(96.dp).clickable { expanded = true }) {
        LoadedImage(source, name, 320, load, Modifier.fillMaxSize(), ContentScale.Crop)
    }
    if (expanded) ImageViewer(source, name, load) { expanded = false }
}

@Composable
private fun LoadedImage(source: String, name: String, maxDimension: Int, load: suspend () -> ByteArray, modifier: Modifier, scale: ContentScale = ContentScale.Fit) {
    var retry by remember(source) { mutableIntStateOf(0) }
    var error by remember(source, retry) { mutableStateOf("") }
    val bitmap by produceState<ImageBitmap?>(null, source, maxDimension, retry) {
        value = null
        try {
            val bytes = load()
            value = withContext(Dispatchers.Default) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000L) { "图片尺寸过大或格式无法读取" }
                val options = BitmapFactory.Options().apply { inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight, maxDimension) }
                (BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("无法解码图片")).asImageBitmap()
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: "图片加载失败" }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, name, Modifier.fillMaxSize(), contentScale = scale) }
        if (bitmap == null && error.isEmpty()) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color(0xFF50794B))
        if (error.isNotEmpty()) Column(Modifier.fillMaxSize().clickable { retry++ }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.Refresh, "重试图片", tint = Color(0xFF8B6B54)); Text(if (maxDimension < 1000) "点击重试" else error, fontSize = 12.sp, color = Color(0xFF8B6B54))
        }
    }
}

@Composable
private fun ImageViewer(source: String, name: String, load: suspend () -> ByteArray, close: () -> Unit) {
    var zoom by remember(source) { mutableFloatStateOf(1f) }; var offset by remember(source) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { factor, pan, _ -> zoom = (zoom * factor).coerceIn(1f, 6f); offset = if (zoom == 1f) Offset.Zero else offset + pan }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF13241D)).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text(name, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1); IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭图片", tint = Color.White) } }
            Box(Modifier.fillMaxWidth().weight(1f).transformable(transform).pointerInput(source) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; offset = Offset.Zero }) }) {
                LoadedImage(source, name, 2048, load, Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = offset.x, translationY = offset.y))
            }
            Text("双指缩放 · 拖动查看 · 双击切换大小", color = Color(0xFFBBCCBC), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp))
        }
    }
}
