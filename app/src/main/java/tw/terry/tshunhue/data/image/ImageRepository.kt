package tw.terry.tshunhue.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import tw.terry.tshunhue.data.model.HttpMetadata
import tw.terry.tshunhue.data.remote.HttpCatalogClient
import tw.terry.tshunhue.data.validation.CatalogLimits
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class ImageAsset(val data: ByteArray, val bitmap: Bitmap, val localFile: File)

@Serializable
private data class ImageCacheEntry(
    val key: String,
    val sourceUrl: String,
    val filename: String,
    val byteCount: Int,
    val width: Int,
    val height: Int,
    val metadata: HttpMetadata,
    val downloadedAtEpochMillis: Long,
    val lastAccessEpochMillis: Long,
)

/** Disk-backed, bounded image cache shared by app UI, transfers, and the Android IME. */
class ImageRepository(
    context: Context,
    private val client: HttpCatalogClient,
    private val json: Json,
    private val byteBudget: Long = 256L * 1_024 * 1_024,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val directory = File(context.cacheDir, "tshunhue/images")
    private val indexFile = File(directory, "index.json")
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ImageAsset>>()
    private var entries = loadIndex()

    suspend fun image(url: String, maxPixelSize: Int): ImageBitmap? = runCatching {
        asset(url).bitmap.scaled(maxPixelSize).asImageBitmap()
    }.getOrNull()

    suspend fun asset(url: String): ImageAsset {
        val key = key(url)
        var owner = false
        val deferred = mutex.withLock {
            readCached(key)?.let { return@withLock CompletableDeferred(it) }
            inFlight[key] ?: CompletableDeferred<ImageAsset>().also { inFlight[key] = it; owner = true }
        }
        if (!owner) return deferred.await()
        try {
            val response = client.getDocument(url, null, CatalogLimits.IMAGE_BYTES, accept = "image/*")
            val data = response.body ?: error("影像伺服器回應沒有內容")
            val asset = store(url, key, data, response.metadata)
            deferred.complete(asset)
            return asset
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            mutex.withLock { inFlight.remove(key) }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            entries.values.forEach { File(directory, it.filename).delete() }
            entries = emptyMap()
            persistIndex()
        }
    }

    suspend fun cacheSize(): Long = mutex.withLock { entries.values.sumOf(ImageCacheEntry::byteCount).toLong() }

    private fun readCached(key: String): ImageAsset? {
        val entry = entries[key] ?: return null
        val file = File(directory, entry.filename)
        if (!file.isFile) {
            entries = entries - key
            persistIndex()
            return null
        }
        val data = runCatching { file.readBytes() }.getOrNull() ?: return null
        val bitmap = decode(data) ?: return null
        entries = entries + (key to entry.copy(lastAccessEpochMillis = nowEpochMillis()))
        persistIndex()
        return ImageAsset(data, bitmap, file)
    }

    private suspend fun store(url: String, key: String, data: ByteArray, metadata: HttpMetadata): ImageAsset = withContext(Dispatchers.IO) {
        val bitmap = decode(data) ?: error("檔案不是支援的靜態影像")
        val filename = "$key.img"
        val file = File(directory, filename)
        writeAtomic(file, data)
        val now = nowEpochMillis()
        mutex.withLock {
            entries = entries + (key to ImageCacheEntry(key, url, filename, data.size, bitmap.width, bitmap.height, metadata, now, now))
            evictIfNeeded(excluding = key)
            persistIndex()
        }
        ImageAsset(data, bitmap, file)
    }

    private fun decode(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "無效的影像" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= 3_840L * 2_160L) { "影像解析度過大" }
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    private fun Bitmap.scaled(maxPixelSize: Int): Bitmap {
        require(maxPixelSize > 0) { "縮圖尺寸必須大於零" }
        val largest = maxOf(width, height)
        if (largest <= maxPixelSize) return this
        val ratio = maxPixelSize.toFloat() / largest
        return Bitmap.createScaledBitmap(this, (width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun evictIfNeeded(excluding: String) {
        var total = entries.values.sumOf(ImageCacheEntry::byteCount).toLong()
        entries.values.sortedBy(ImageCacheEntry::lastAccessEpochMillis).forEach { entry ->
            if (total <= byteBudget || entry.key == excluding) return@forEach
            File(directory, entry.filename).delete()
            entries = entries - entry.key
            total -= entry.byteCount
        }
    }

    private fun loadIndex(): Map<String, ImageCacheEntry> = runCatching {
        val decoded = json.decodeFromString<List<ImageCacheEntry>>(readAtomic(indexFile).decodeToString())
        decoded.associateBy(ImageCacheEntry::key).filterValues { File(directory, it.filename).isFile }
    }.getOrDefault(emptyMap())

    private fun persistIndex() = writeAtomic(indexFile, json.encodeToString(ListSerializer(ImageCacheEntry.serializer()), entries.values.sortedBy(ImageCacheEntry::key)).encodeToByteArray())
    private fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    private fun readAtomic(file: File) = AtomicFile(file).openRead().use { it.readBytes() }
    private fun writeAtomic(file: File, data: ByteArray) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(data); atomic.finishWrite(stream) } catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
}
