package tw.terry.tshunhue.data.repository

import tw.terry.tshunhue.data.model.*
import tw.terry.tshunhue.data.remote.HttpCatalogClient
import tw.terry.tshunhue.data.validation.CatalogLimits
import tw.terry.tshunhue.data.validation.CatalogValidator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json

/** Coordinates document downloads, validation, and the immutable catalog published to the UI layer. */
class CatalogRepository(
    private val client: HttpCatalogClient,
    private val validator: CatalogValidator,
    private val json: Json,
) {
    suspend fun refresh(records: List<SourceRecord>): CatalogSnapshot = coroutineScope {
        records.map { record -> async { loadSource(record) } }.awaitAll().fold(CatalogSnapshot()) { snapshot, source ->
            snapshot.copy(sources = snapshot.sources + source.summary, frames = snapshot.frames + source.frames)
        }
    }

    private suspend fun loadSource(record: SourceRecord): SourceLoad {
        if (!record.enabled) return SourceLoad(SourceSummary(record, record.url, emptyList()), emptyList())
        return try {
            val rawIndex = client.get(record.url, CatalogLimits.INDEX_BYTES)
            val validatedIndex = validator.validateIndex(json.decodeFromString<CatalogIndex>(rawIndex.decodeToString()), record.url)
            val frames = validatedIndex.categories
                .filterNot { (descriptor, _) -> descriptor.id in record.hiddenCategoryIds }
                .map { (descriptor, url) ->
                    val rawCategory = client.get(url, CatalogLimits.CATEGORY_BYTES)
                    validator.validateCategory(
                        json.decodeFromString<CategoryDocument>(rawCategory.decodeToString()),
                        url,
                        descriptor,
                        validatedIndex,
                    )
                }
                .flatten()
            SourceLoad(SourceSummary(record, validatedIndex.index.name, validatedIndex.index.categories), frames)
        } catch (error: Exception) {
            SourceLoad(SourceSummary(record, record.url, emptyList(), error.message ?: "無法同步來源"), emptyList())
        }
    }

    private data class SourceLoad(val summary: SourceSummary, val frames: List<CatalogFrame>)
}
