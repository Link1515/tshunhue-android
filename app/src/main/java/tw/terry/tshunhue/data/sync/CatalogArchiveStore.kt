package tw.terry.tshunhue.data.sync

import android.content.Context
import android.util.AtomicFile
import tw.terry.tshunhue.data.model.CachedDocument
import tw.terry.tshunhue.data.model.SourceArchive
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * File layout for durable catalog archives. Archive metadata and remote document bytes are
 * deliberately separated so startup can inspect sources without decoding every catalog.
 */
class CatalogArchiveStore(context: Context, private val json: Json) {
    private val root = File(context.filesDir, "tshunhue/sources")

    fun archive(sourceId: String): SourceArchive? = runCatching {
        json.decodeFromString<SourceArchive>(readAtomic(archiveFile(sourceId)).decodeToString())
    }.getOrNull()

    fun save(archive: SourceArchive) {
        writeAtomic(archiveFile(archive.id), json.encodeToString(SourceArchive.serializer(), archive).encodeToByteArray())
    }

    fun readIndex(sourceId: String, expected: CachedDocument): ByteArray = readDocument(indexFile(sourceId), expected)
    fun readCategory(sourceId: String, categoryId: String, expected: CachedDocument): ByteArray = readDocument(categoryFile(sourceId, categoryId), expected)

    fun writeIndex(sourceId: String, data: ByteArray) = writeAtomic(indexFile(sourceId), data)
    fun writeCategory(sourceId: String, categoryId: String, data: ByteArray) = writeAtomic(categoryFile(sourceId, categoryId), data)

    fun removeCategory(sourceId: String, categoryId: String) {
        categoryFile(sourceId, categoryId).delete()
    }

    fun removeSource(sourceId: String) {
        archiveFile(sourceId).delete()
        sourceDirectory(sourceId).deleteRecursively()
    }

    fun digest(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun readDocument(file: File, expected: CachedDocument): ByteArray {
        val bytes = readAtomic(file)
        check(bytes.size == expected.byteCount && digest(bytes) == expected.digest) { "快取文件已損毀" }
        return bytes
    }

    private fun archiveFile(sourceId: String) = File(root, "source-${verifiedSourceId(sourceId)}.json")
    private fun indexFile(sourceId: String) = File(sourceDirectory(sourceId), "index.json")
    private fun categoryFile(sourceId: String, categoryId: String): File {
        require(categoryId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"))) { "無效的分類 ID" }
        return File(sourceDirectory(sourceId), "category-$categoryId.json")
    }
    private fun sourceDirectory(sourceId: String) = File(root, verifiedSourceId(sourceId))
    private fun verifiedSourceId(value: String): String = UUID.fromString(value).toString()

    private fun readAtomic(file: File): ByteArray = AtomicFile(file).openRead().use { it.readBytes() }
    private fun writeAtomic(file: File, data: ByteArray) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(data)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
