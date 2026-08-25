package tw.terry.tshunhue.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class CatalogIndex(
    val version: Int,
    val name: String,
    val homepage: String? = null,
    val contact: String? = null,
    val report: String? = null,
    val categories: List<CategoryDescriptor>,
)

@Serializable
data class CategoryDescriptor(
    val id: String,
    val name: String,
    val language: String? = null,
    val url: String,
    val frames: Int? = null,
)

@Serializable
data class CategoryDocument(
    val version: Int,
    val id: String,
    val name: String,
    val language: String,
    val cover: String? = null,
    val attribution: Attribution? = null,
    val providers: List<Provider> = emptyList(),
    val subsections: List<Subsection> = emptyList(),
    val frames: List<FrameDocument>,
)

@Serializable data class Attribution(val text: String, val url: String? = null)
@Serializable data class Provider(val name: String, val url: String)
@Serializable data class Subsection(val id: String, val name: String, val providers: List<Provider> = emptyList())

@Serializable
data class FrameDocument(
    val id: String? = null,
    val url: String,
    val caption: String,
    val tags: List<String> = emptyList(),
    val subsection: String? = null,
    @Serializable(with = TimecodeSerializer::class) val timecode: Long? = null,
)

/** Accepts schema-compatible numeric seconds or MM:SS / HH:MM:SS timecodes and stores milliseconds. */
@OptIn(ExperimentalSerializationApi::class)
object TimecodeSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Timecode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long? {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        if (element is JsonNull) return null
        val primitive = element.jsonPrimitive
        primitive.doubleOrNull?.let { seconds ->
            require(seconds.isFinite() && seconds >= 0) { "Invalid timecode" }
            return (seconds * 1_000).toLong()
        }
        return parse(primitive.content)
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(display(value))
    }

    private fun parse(value: String): Long {
        val parts = value.replace(',', '.').split(':')
        require(parts.size in 2..3) { "Invalid timecode" }
        val hours: Long
        val minutes: Long
        val secondValue: String
        if (parts.size == 3) {
            hours = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: error("Invalid timecode")
            minutes = parts[1].toLongOrNull()?.takeIf { it in 0..59 && parts[1].length == 2 } ?: error("Invalid timecode")
            secondValue = parts[2]
        } else {
            hours = 0
            minutes = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: error("Invalid timecode")
            secondValue = parts[1]
        }
        val secondsParts = secondValue.split('.')
        val seconds = secondsParts.firstOrNull()?.toLongOrNull()?.takeIf { it in 0..59 && secondsParts[0].length == 2 }
            ?: error("Invalid timecode")
        val fraction = secondsParts.getOrNull(1)?.let {
            require(it.length in 1..3 && it.all(Char::isDigit)) { "Invalid timecode" }
            it.padEnd(3, '0').toLong()
        } ?: 0L
        require(secondsParts.size <= 2) { "Invalid timecode" }
        val hourPart = Math.multiplyExact(hours, 3_600_000)
        val minutePart = Math.multiplyExact(minutes, 60_000)
        return Math.addExact(Math.addExact(hourPart, minutePart), seconds * 1_000 + fraction)
    }

    fun display(milliseconds: Long): String {
        val seconds = milliseconds / 1_000
        val fraction = milliseconds % 1_000
        val prefix = if (seconds >= 3_600) "%02d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
        return if (fraction == 0L) prefix else "$prefix.${fraction.toString().padStart(3, '0')}"
    }
}

@Serializable
data class SourceRecord(
    val id: String,
    val url: String,
    val enabled: Boolean = true,
    val hiddenCategoryIds: Set<String> = emptySet(),
)

/** HTTP validators stored beside downloaded catalog bytes for conditional refreshes. */
@Serializable
data class HttpMetadata(
    val etag: String? = null,
    val lastModified: String? = null,
    val expiresAtEpochMillis: Long? = null,
) {
    fun merged(fallback: HttpMetadata, revalidatedAtEpochMillis: Long): HttpMetadata = copy(
        etag = etag ?: fallback.etag,
        lastModified = lastModified ?: fallback.lastModified,
        expiresAtEpochMillis = expiresAtEpochMillis ?: fallback.expiresAtEpochMillis,
    )
}

/** Metadata for a raw catalog file held separately from its archive record. */
@Serializable
data class CachedDocument(
    val digest: String,
    val byteCount: Int,
    val metadata: HttpMetadata = HttpMetadata(),
    val validatedAtEpochMillis: Long? = null,
    val documentUrl: String? = null,
) {
    fun isFresh(nowEpochMillis: Long, refreshFrequency: RefreshFrequency): Boolean = when {
        expiresAtEpochMillis() != null -> nowEpochMillis < expiresAtEpochMillis()!!
        validatedAtEpochMillis == null -> false
        refreshFrequency.intervalMillis == null -> true
        else -> nowEpochMillis - validatedAtEpochMillis < refreshFrequency.intervalMillis
    }

    private fun expiresAtEpochMillis() = metadata.expiresAtEpochMillis
}

@Serializable
enum class RefreshFrequency(val intervalMillis: Long?) {
    MANUAL(null), DAILY(86_400_000), WEEKLY(604_800_000), MONTHLY(2_592_000_000);
}

/** The file-backed, last-known-good state of one remote catalog source. */
@Serializable
data class SourceArchive(
    val id: String,
    val sourceUrl: String,
    val index: CachedDocument? = null,
    val isEnabled: Boolean = true,
    val hiddenCategoryIds: Set<String> = emptySet(),
    val categories: Map<String, CachedDocument> = emptyMap(),
    val lastSuccessfulRefreshEpochMillis: Long? = null,
    val lastAttemptEpochMillis: Long? = null,
    val indexRefreshError: String? = null,
    val categoryRefreshErrors: Map<String, String> = emptyMap(),
)

data class SourceSummary(
    val record: SourceRecord,
    val name: String,
    val categories: List<CategoryDescriptor>,
    val error: String? = null,
    val availableCategoryIds: Set<String> = emptySet(),
    val lastSuccessfulRefreshEpochMillis: Long? = null,
    val categoryErrors: Map<String, String> = emptyMap(),
)

data class CatalogFrame(
    val sourceUrl: String,
    val sourceName: String,
    val categoryId: String,
    val categoryName: String,
    val categoryOrder: Int,
    val subsection: Subsection? = null,
    val caption: String,
    val tags: List<String>,
    val timecode: Long? = null,
    val effectiveId: String,
    val imageUrl: String,
    val providers: List<Provider>,
    val attribution: Attribution? = null,
    val reportUrl: String? = null,
    val order: Int,
) {
    val identity: String get() = "$sourceUrl|$categoryId|$effectiveId"
    val categoryLabel: String get() = if (sourceName == categoryName) categoryName else "$sourceName · $categoryName"
}

data class CatalogSnapshot(
    val sources: List<SourceSummary> = emptyList(),
    val frames: List<CatalogFrame> = emptyList(),
)
