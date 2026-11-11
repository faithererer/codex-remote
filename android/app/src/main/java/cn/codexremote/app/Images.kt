package cn.codexremote.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
const val MAX_IMAGES = 5

fun imageSampleSize(width: Int, height: Int, maximum: Int): Int {
    require(width > 0 && height > 0 && maximum > 0)
    var sample = 1
    while (maxOf(width, height) / sample > maximum) sample *= 2
    return sample
}

data class DraftImage(val id: String, val name: String) {
    fun json() = JSONObject().put("id", id).put("name", name)
}

fun imageUploadId(key: ThreadKey, instance: String, draftId: String): String =
    UUID.nameUUIDFromBytes("${key.hostId}/${key.threadId}/$instance/$draftId".toByteArray(Charsets.UTF_8)).toString()

fun imageApiPath(key: ThreadKey, instance: String, imageId: String): String {
    UUID.fromString(key.hostId); UUID.fromString(key.threadId); UUID.fromString(instance)
    require(imageId.matches(Regex("[0-9a-f]{64}|[0-9a-f-]{36}"))) { "图片标识无效" }
    return "/api/v1/threads/${key.threadId}/images/$imageId?hostId=${key.hostId}&instanceId=$instance"
}

class ImageDrafts(private val context: Context, private val store: SecureStore) {
    private fun name(key: ThreadKey) = "image-draft-${key.hostId}-${key.threadId}"
    private fun file(key: ThreadKey, id: String): File {
        UUID.fromString(key.hostId); UUID.fromString(key.threadId); UUID.fromString(id)
        return File(context.filesDir, "image-drafts/${key.hostId}/${key.threadId}/$id.img")
    }
    @Synchronized fun list(key: ThreadKey): List<DraftImage> = JSONArray(store.get(name(key), "[]")).objects().map { DraftImage(it.getString("id"), it.getString("name")) }.filter { file(key, it.id).isFile }
    @Synchronized fun bytes(key: ThreadKey, id: String): ByteArray {
        require(list(key).any { it.id == id }) { "图片草稿已改变，请重新选择" }
        val file = file(key, id); require(file.length() in 1..MAX_IMAGE_BYTES.toLong()) { "图片草稿过大或损坏" }
        return file.readBytes()
    }
    @Synchronized fun remove(key: ThreadKey, ids: Set<String>) {
        val current = list(key); store.put(name(key), JSONArray(current.filterNot { it.id in ids }.map(DraftImage::json)).toString())
        current.filter { it.id in ids }.forEach { file(key, it.id).delete() }
    }
    suspend fun add(key: ThreadKey, uris: List<Uri>): List<DraftImage> = withContext(Dispatchers.IO) {
        require(list(key).size + uris.size <= MAX_IMAGES) { "每条消息最多 5 张图片，请先移除部分图片" }
        val startingCount = list(key).size
        val prepared = uris.mapIndexed { index, uri ->
            // Copy immediately into app-private storage; no persistent gallery permission is required.
            val source = context.contentResolver.openInputStream(uri)?.use { input ->
                val data = ByteArrayOutputStream(); val buffer = ByteArray(32768)
                while (true) { val count = input.read(buffer); if (count < 0) break; require(data.size() + count <= 30 * 1024 * 1024) { "原图超过 30 MB，请选择较小的图片" }; data.write(buffer, 0, count) }; data.toByteArray()
            } ?: error("无法读取所选图片")
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, info, _ ->
                require(info.size.width.toLong() * info.size.height in 1..100_000_000L) { "图片尺寸过大" }
                val scale = (2048.0 / maxOf(info.size.width, info.size.height)).coerceAtMost(1.0)
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1)); decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val data = ByteArrayOutputStream()
            try { check(bitmap.compress(if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, data)) { "图片转换失败" } } finally { bitmap.recycle() }
            require(data.size() in 1..MAX_IMAGE_BYTES) { "转换后的图片仍超过 10 MB，请选择较小的图片" }
            DraftImage(UUID.randomUUID().toString(), "图片 ${startingCount + index + 1}") to data.toByteArray()
        }
        synchronized(this@ImageDrafts) {
            val current = list(key); require(current.size + prepared.size <= MAX_IMAGES) { "每条消息最多 5 张图片" }
            val added = prepared.map { it.first }
            try {
                prepared.forEach { (image, bytes) -> file(key, image.id).apply { parentFile?.mkdirs(); writeBytes(bytes) } }
                store.put(name(key), JSONArray((current + added).map(DraftImage::json)).toString())
            } catch (e: Exception) { added.forEach { file(key, it.id).delete() }; throw e }
            current + added
        }
    }
}
