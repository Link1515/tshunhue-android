package tw.terry.tshunhue.data.shard

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tw.terry.tshunhue.data.model.CatalogFrame

/** A stable location for a frame in a persisted category shard. */
@Serializable
data class FrameRef(val sourceId: String, val categoryId: String, val ordinal: Int)

/** The compact fields needed for filtering, ordering, and rendering a grid item. */
@Serializable
data class FrameSearchEntry(
    val sourceUrl: String,
    val sourceName: String,
    val categoryId: String,
    val categoryName: String,
    val categoryOrder: Int,
    val caption: String,
    val tags: List<String>,
    val effectiveId: String,
    val imageUrl: String,
    val order: Int,
    val subsectionId: String? = null,
    val subsectionName: String? = null,
) {
    val identity: String get() = "$sourceUrl|$categoryId|$effectiveId"
}

/** Input accepted only after a remote category has passed CatalogValidator. */
@Serializable
data class CategoryFrameShard(
    val version: Int = FrameShardCodec.VERSION,
    val sourceId: String,
    val categoryId: String,
    val buildDigest: String,
    val coverUrl: String? = null,
    val frames: List<CatalogFrame>,
)

@Serializable
private data class FrameShardManifest(
    val version: Int,
    val sourceId: String,
    val categoryId: String,
    val buildDigest: String,
    val coverUrl: String? = null,
    val entries: List<FrameSearchEntry>,
)

/**
 * Immutable reader over a category shard. It retains only compact search/display fields in memory;
 * complete frame records are parsed on demand and memoized by ordinal.
 */
class FrameShardReader internal constructor(
    val sourceId: String,
    val categoryId: String,
    val buildDigest: String,
    val coverUrl: String?,
    val entries: List<FrameSearchEntry>,
    private val recordBytes: ByteArray,
    private val offsets: IntArray,
    private val lengths: IntArray,
    private val json: Json,
) {
    private val hydrated = ConcurrentHashMap<Int, CatalogFrame>()

    val frameCount: Int get() = entries.size
    val refs: List<FrameRef> get() = entries.indices.map { FrameRef(sourceId, categoryId, it) }

    fun frame(ref: FrameRef): CatalogFrame? = ref.takeIf {
        it.sourceId == sourceId && it.categoryId == categoryId
    }?.ordinal?.let(::frameAt)

    fun frameAt(ordinal: Int): CatalogFrame? {
        if (ordinal !in entries.indices) return null
        return hydrated.getOrPut(ordinal) {
            json.decodeFromString(CatalogFrame.serializer(), recordBytes.copyOfRange(offsets[ordinal], offsets[ordinal] + lengths[ordinal]).decodeToString())
        }
    }
}

/**
 * Versioned binary envelope. The manifest can drive searching without hydrating frame records;
 * fixed-size record offsets make complete records independently readable and verifiable.
 */
class FrameShardCodec(private val json: Json) {
    fun encode(shard: CategoryFrameShard): ByteArray {
        require(shard.version == VERSION) { "不支援的 shard 版本" }
        requireDigest(shard.buildDigest)
        val records = ByteArrayOutputStream()
        val entries = shard.frames.map { frame ->
            val encoded = json.encodeToString(CatalogFrame.serializer(), frame).encodeToByteArray()
            require(encoded.size <= MAX_FRAME_BYTES) { "影格資料過大" }
            records.writeInt(encoded.size)
            records.write(encoded)
            frame.toSearchEntry()
        }
        val recordBytes = records.toByteArray()
        require(recordBytes.size <= MAX_RECORD_BYTES) { "shard 過大" }
        val manifest = FrameShardManifest(VERSION, shard.sourceId, shard.categoryId, shard.buildDigest, shard.coverUrl, entries)
        val manifestBytes = json.encodeToString(FrameShardManifest.serializer(), manifest).encodeToByteArray()
        require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "shard 索引過大" }
        return ByteBuffer.allocate(HEADER_BYTES + manifestBytes.size + recordBytes.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC)
            putInt(VERSION)
            put(hexToBytes(shard.buildDigest))
            putInt(manifestBytes.size)
            putInt(recordBytes.size)
            put(shardDigest(manifestBytes, recordBytes))
            put(manifestBytes)
            put(recordBytes)
        }.array()
    }

    fun decode(bytes: ByteArray, expectedSourceId: String, expectedCategoryId: String, expectedBuildDigest: String): FrameShardReader {
        requireDigest(expectedBuildDigest)
        require(bytes.size >= HEADER_BYTES) { "shard 檔案不完整" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "無效的 shard 格式" }
        require(buffer.int == VERSION) { "不支援的 shard 版本" }
        val buildDigest = ByteArray(DIGEST_BYTES).also(buffer::get).toHex()
        require(buildDigest == expectedBuildDigest) { "shard 與快取文件不一致" }
        val manifestSize = buffer.int
        val recordsSize = buffer.int
        require(manifestSize in 0..MAX_MANIFEST_BYTES && recordsSize in 0..MAX_RECORD_BYTES && bytes.size == HEADER_BYTES + manifestSize + recordsSize) {
            "無效的 shard 長度"
        }
        val expectedPayloadDigest = ByteArray(DIGEST_BYTES).also(buffer::get)
        val manifestBytes = ByteArray(manifestSize).also(buffer::get)
        val recordBytes = ByteArray(recordsSize).also(buffer::get)
        require(shardDigest(manifestBytes, recordBytes).contentEquals(expectedPayloadDigest)) { "shard 已損毀" }
        val manifest = json.decodeFromString(FrameShardManifest.serializer(), manifestBytes.decodeToString())
        require(manifest.version == VERSION && manifest.sourceId == expectedSourceId && manifest.categoryId == expectedCategoryId && manifest.buildDigest == expectedBuildDigest) {
            "shard 識別資料不一致"
        }
        val offsets = IntArray(manifest.entries.size)
        val lengths = IntArray(manifest.entries.size)
        val records = ByteBuffer.wrap(recordBytes).order(ByteOrder.BIG_ENDIAN)
        manifest.entries.indices.forEach { ordinal ->
            require(records.remaining() >= Int.SIZE_BYTES) { "shard 影格資料不完整" }
            val length = records.int
            require(length in 0..MAX_FRAME_BYTES && records.remaining() >= length) { "無效的影格資料長度" }
            offsets[ordinal] = records.position()
            lengths[ordinal] = length
            records.position(records.position() + length)
        }
        require(!records.hasRemaining()) { "shard 影格數與索引不一致" }
        return FrameShardReader(manifest.sourceId, manifest.categoryId, manifest.buildDigest, manifest.coverUrl, manifest.entries, recordBytes, offsets, lengths, json)
    }

    companion object {
        const val VERSION = 5
        private const val DIGEST_BYTES = 32
        private const val MAX_MANIFEST_BYTES = 8 * 1_024 * 1_024
        private const val MAX_RECORD_BYTES = 32 * 1_024 * 1_024
        private const val MAX_FRAME_BYTES = 2 * 1_024 * 1_024
        private const val HEADER_BYTES = 4 + Int.SIZE_BYTES + DIGEST_BYTES + Int.SIZE_BYTES + Int.SIZE_BYTES + DIGEST_BYTES
        private val MAGIC = byteArrayOf('T'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte())

        private fun shardDigest(manifest: ByteArray, records: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").apply {
            update(manifest)
            update(records)
        }.digest()
        private fun requireDigest(value: String) = require(value.matches(Regex("[0-9a-f]{64}"))) { "無效的 shard 雜湊" }
        private fun hexToBytes(value: String): ByteArray = ByteArray(DIGEST_BYTES) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
        private fun ByteArrayOutputStream.writeInt(value: Int) = write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
        private fun CatalogFrame.toSearchEntry() = FrameSearchEntry(
            sourceUrl, sourceName, categoryId, categoryName, categoryOrder, caption, tags, effectiveId,
            imageUrl, order, subsection?.id, subsection?.name,
        )
    }
}
