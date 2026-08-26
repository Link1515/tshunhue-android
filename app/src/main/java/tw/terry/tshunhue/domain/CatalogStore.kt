package tw.terry.tshunhue.domain

import kotlinx.serialization.Serializable
import tw.terry.tshunhue.data.model.CatalogFrame
import tw.terry.tshunhue.data.shard.FrameRef
import tw.terry.tshunhue.data.shard.FrameSearchEntry
import tw.terry.tshunhue.data.shard.FrameShardReader

@Serializable
data class CategoryKey(val sourceUrl: String, val categoryId: String)

data class CategorySummary(
    val key: CategoryKey,
    val categoryName: String,
    val sourceName: String,
    val categoryOrder: Int,
    val frameCount: Int,
    val coverUrl: String?,
)

data class CatalogSearchEntry(val ref: FrameRef, val value: FrameSearchEntry)

/**
 * Open catalog shards. Search and scope resolution operate on their compact manifests; a complete
 * [CatalogFrame] is decoded only when a visible cell or details screen asks for one.
 */
class CatalogStore(readers: List<FrameShardReader> = emptyList()) {
    private val readersByKey = readers.associateBy { ReaderKey(it.sourceId, it.categoryId) }
    val entries: List<CatalogSearchEntry> = readers.flatMap { reader ->
        reader.entries.mapIndexed { ordinal, entry -> CatalogSearchEntry(FrameRef(reader.sourceId, reader.categoryId, ordinal), entry) }
    }
    private val entriesByIdentity = entries.associateBy { it.value.identity }

    val isEmpty: Boolean get() = entries.isEmpty()
    val frameCount: Int get() = entries.size
    val allRefs: List<FrameRef> get() = entries.map(CatalogSearchEntry::ref)
    val categorySummaries: List<CategorySummary> = readers.map { reader ->
        val first = reader.entries.firstOrNull()
        CategorySummary(
            key = CategoryKey(first?.sourceUrl.orEmpty(), reader.categoryId),
            categoryName = first?.categoryName.orEmpty(),
            sourceName = first?.sourceName.orEmpty(),
            categoryOrder = first?.categoryOrder ?: Int.MAX_VALUE,
            frameCount = reader.frameCount,
            coverUrl = first?.imageUrl,
        )
    }.filter { it.frameCount > 0 }

    fun frame(ref: FrameRef): CatalogFrame? = readersByKey[ReaderKey(ref.sourceId, ref.categoryId)]?.frame(ref)
    fun entry(ref: FrameRef): FrameSearchEntry? = readersByKey[ReaderKey(ref.sourceId, ref.categoryId)]?.entries?.getOrNull(ref.ordinal)
    fun refForIdentity(identity: String): FrameRef? = entriesByIdentity[identity]?.ref

    private data class ReaderKey(val sourceId: String, val categoryId: String)
}
