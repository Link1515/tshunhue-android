package tw.terry.tshunhue.data.shard

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tw.terry.tshunhue.data.model.CatalogFrame

/** A stable location for a frame in a persisted category shard. */
@Serializable
data class FrameRef(
    val sourceId: String,
    val categoryId: String,
    val ordinal: Int,
)

/**
 * Validated, app-owned representation of a category. The wire format is deliberately separate
 * from catalog JSON so the reader can reject stale data before it reaches browse or search UI.
 */
@Serializable
data class CategoryFrameShard(
    val version: Int = FrameShardCodec.VERSION,
    val sourceId: String,
    val categoryId: String,
    val buildDigest: String,
    val frames: List<CatalogFrame>,
)

class FrameShardReader(private val shard: CategoryFrameShard) {
    val refs: List<FrameRef> = shard.frames.indices.map { ordinal ->
        FrameRef(shard.sourceId, shard.categoryId, ordinal)
    }

    fun frame(ref: FrameRef): CatalogFrame? = ref.takeIf {
        it.sourceId == shard.sourceId && it.categoryId == shard.categoryId
    }?.ordinal?.let(shard.frames::getOrNull)

    fun allFrames(): List<CatalogFrame> = shard.frames
}

/**
 * Small versioned envelope around serialized shard data. The header prevents a stale or partial
 * file from being mistaken for catalog JSON, while the payload digest detects torn/corrupt files.
 */
class FrameShardCodec(private val json: Json) {
    fun encode(shard: CategoryFrameShard): ByteArray {
        require(shard.version == VERSION) { "不支援的 shard 版本" }
        requireDigest(shard.buildDigest)
        val payload = json.encodeToString(CategoryFrameShard.serializer(), shard).encodeToByteArray()
        require(payload.size <= MAX_PAYLOAD_BYTES) { "shard 過大" }
        val payloadDigest = sha256(payload)
        return ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC)
            putInt(VERSION)
            put(hexToBytes(shard.buildDigest))
            putInt(payload.size)
            put(payloadDigest)
            put(payload)
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
        val payloadSize = buffer.int
        require(payloadSize in 0..MAX_PAYLOAD_BYTES && bytes.size == HEADER_BYTES + payloadSize) { "無效的 shard 長度" }
        val expectedPayloadDigest = ByteArray(DIGEST_BYTES).also(buffer::get)
        val payload = ByteArray(payloadSize).also(buffer::get)
        require(sha256(payload).contentEquals(expectedPayloadDigest)) { "shard 已損毀" }
        val shard = json.decodeFromString(CategoryFrameShard.serializer(), payload.decodeToString())
        require(shard.version == VERSION && shard.sourceId == expectedSourceId && shard.categoryId == expectedCategoryId) {
            "shard 識別資料不一致"
        }
        require(shard.buildDigest == expectedBuildDigest) { "shard 雜湊不一致" }
        return FrameShardReader(shard)
    }

    companion object {
        const val VERSION = 1
        private const val DIGEST_BYTES = 32
        private const val MAX_PAYLOAD_BYTES = 32 * 1_024 * 1_024
        private const val HEADER_BYTES = 4 + Int.SIZE_BYTES + DIGEST_BYTES + Int.SIZE_BYTES + DIGEST_BYTES
        private val MAGIC = byteArrayOf('T'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte())

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun requireDigest(value: String) = require(value.matches(Regex("[0-9a-f]{64}"))) { "無效的 shard 雜湊" }
        private fun hexToBytes(value: String): ByteArray = ByteArray(DIGEST_BYTES) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
