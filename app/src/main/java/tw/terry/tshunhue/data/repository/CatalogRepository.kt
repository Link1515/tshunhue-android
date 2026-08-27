package tw.terry.tshunhue.data.repository

import tw.terry.tshunhue.data.model.*
import tw.terry.tshunhue.data.remote.HttpCatalogClient
import tw.terry.tshunhue.data.shard.CategoryFrameShard
import tw.terry.tshunhue.data.shard.FrameShardCodec
import tw.terry.tshunhue.data.shard.FrameShardReader
import tw.terry.tshunhue.data.sync.CatalogArchiveStore
import tw.terry.tshunhue.data.validation.CatalogLimits
import tw.terry.tshunhue.data.validation.CatalogValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Coordinates catalog synchronization. Every remote document is validated before its bytes
 * replace the on-disk last-known-good copy, so one bad response cannot empty a source.
 */
class CatalogRepository(
    private val client: HttpCatalogClient,
    private val validator: CatalogValidator,
    private val json: Json,
    private val archives: CatalogArchiveStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val shardCodec = FrameShardCodec(json)

    suspend fun loadCached(records: List<SourceRecord>): CatalogSnapshot = coroutineScope {
        records.map { record ->
            async {
                val archive = archives.archive(record.id)?.takeIf { it.sourceUrl == record.url }
                loadFromArchive(record, archive)
            }
        }.awaitAll().toSnapshot()
    }

    suspend fun refresh(
        records: List<SourceRecord>,
        refreshFrequency: RefreshFrequency = RefreshFrequency.WEEKLY,
        force: Boolean = false,
    ): CatalogSnapshot = coroutineScope {
        records.map { record -> async { refreshSource(record, refreshFrequency, force) } }.awaitAll().toSnapshot()
    }

    private suspend fun refreshSource(record: SourceRecord, refreshFrequency: RefreshFrequency, force: Boolean): SourceLoad = withContext(Dispatchers.IO) {
        val oldArchive = archives.archive(record.id)?.takeIf { it.sourceUrl == record.url }
        if (!record.enabled) return@withContext loadFromArchive(record, oldArchive)
        val attemptedAt = nowEpochMillis()
        if (!force && oldArchive?.index?.isFresh(attemptedAt, refreshFrequency) == true && oldArchive.indexRefreshError == null) {
            return@withContext loadFromArchive(record, oldArchive)
        }
        try {
            val oldIndex = oldArchive?.index?.let { document -> runCatching { archives.readIndex(record.id, document) }.getOrNull() }
            val indexResponse = client.getDocument(record.url, oldArchive?.index?.metadata?.takeIf { oldIndex != null }, CatalogLimits.INDEX_BYTES)
            val indexBytes = indexResponse.body ?: oldIndex ?: error("伺服器回應沒有 index 文件")
            val validatedIndex = validator.validateIndex(json.decodeFromString<CatalogIndex>(indexBytes.decodeToString()), record.url)
            val oldCategories = oldArchive?.categories.orEmpty()
            val nextDocuments = mutableMapOf<String, CachedDocument>()
            val categoryErrors = mutableMapOf<String, String>()
            val stagedWrites = mutableListOf<Pair<String, ByteArray>>()
            val stagedShardWrites = mutableListOf<Pair<String, ByteArray>>()
            val visibleReaders = mutableListOf<FrameShardReader>()

            validatedIndex.categories.forEach { (descriptor, categoryUrl) ->
                val previous = oldCategories[descriptor.id]?.takeIf { it.documentUrl == categoryUrl }
                val oldBytes = previous?.let { runCatching { archives.readCategory(record.id, descriptor.id, it) }.getOrNull() }
                try {
                    val response = if (!force && previous?.isFresh(attemptedAt, refreshFrequency) == true && oldBytes != null) {
                        null
                    } else {
                        client.getDocument(categoryUrl, previous?.metadata?.takeIf { oldBytes != null }, CatalogLimits.CATEGORY_BYTES)
                    }
                    val bytes = response?.body ?: oldBytes ?: error("伺服器回應沒有分類文件")
                    val category = json.decodeFromString<CategoryDocument>(bytes.decodeToString())
                    val frames = validator.validateCategory(category, categoryUrl, descriptor, validatedIndex)
                    val coverUrl = validator.categoryCoverUrl(category, categoryUrl)
                    val metadata = when {
                        response == null && previous != null -> previous.metadata
                        response?.body == null && previous != null -> requireNotNull(response).metadata.merged(previous.metadata, attemptedAt)
                        else -> requireNotNull(response).metadata
                    }
                    val document = CachedDocument(
                        digest = archives.digest(bytes), byteCount = bytes.size, metadata = metadata,
                        validatedAtEpochMillis = attemptedAt, documentUrl = categoryUrl,
                    )
                    nextDocuments[descriptor.id] = document
                    if (response?.body != null) stagedWrites += descriptor.id to bytes
                    val shardBytes = shardCodec.encode(
                        CategoryFrameShard(
                            sourceId = record.id,
                            categoryId = descriptor.id,
                            buildDigest = shardBuildDigest(archives.digest(indexBytes), document.digest),
                            coverUrl = coverUrl,
                            frames = frames,
                        ),
                    )
                    stagedShardWrites += descriptor.id to shardBytes
                    if (descriptor.id !in record.hiddenCategoryIds) {
                        visibleReaders += shardCodec.decode(shardBytes, record.id, descriptor.id, shardBuildDigest(archives.digest(indexBytes), document.digest))
                    }
                } catch (error: Exception) {
                    categoryErrors[descriptor.id] = error.message ?: "無法同步分類"
                    val fallback = fallbackCategory(record, descriptor, categoryUrl, validatedIndex, previous)
                    if (fallback != null) {
                        nextDocuments[descriptor.id] = requireNotNull(previous)
                        val fallbackDigest = shardBuildDigest(archives.digest(indexBytes), previous.digest)
                        val shardBytes = shardCodec.encode(
                            CategoryFrameShard(
                                sourceId = record.id,
                                categoryId = descriptor.id,
                                buildDigest = fallbackDigest,
                                coverUrl = fallback.coverUrl,
                                frames = fallback.frames,
                            ),
                        )
                        stagedShardWrites += descriptor.id to shardBytes
                        if (descriptor.id !in record.hiddenCategoryIds) {
                            visibleReaders += shardCodec.decode(shardBytes, record.id, descriptor.id, fallbackDigest)
                        }
                    }
                }
            }

            // Write immutable document files before publishing their archive record.
            if (indexResponse.body != null) archives.writeIndex(record.id, indexBytes)
            stagedWrites.forEach { (id, bytes) -> archives.writeCategory(record.id, id, bytes) }
            stagedShardWrites.forEach { (id, bytes) -> archives.writeShard(record.id, id, bytes) }
            val mergedIndexMetadata = if (indexResponse.body == null && oldArchive?.index != null) {
                indexResponse.metadata.merged(oldArchive.index.metadata, attemptedAt)
            } else indexResponse.metadata
            val archive = SourceArchive(
                id = record.id, sourceUrl = record.url,
                index = CachedDocument(archives.digest(indexBytes), indexBytes.size, mergedIndexMetadata, attemptedAt, record.url),
                isEnabled = record.enabled, hiddenCategoryIds = record.hiddenCategoryIds, categories = nextDocuments,
                lastSuccessfulRefreshEpochMillis = if (categoryErrors.isEmpty()) attemptedAt else oldArchive?.lastSuccessfulRefreshEpochMillis,
                lastAttemptEpochMillis = attemptedAt, indexRefreshError = null, categoryRefreshErrors = categoryErrors,
            )
            archives.save(archive)
            oldCategories.keys.filterNot(nextDocuments::containsKey).forEach { archives.removeCategory(record.id, it) }
            SourceLoad(
                SourceSummary(record, validatedIndex.index.name, validatedIndex.index.categories, availableCategoryIds = nextDocuments.keys, lastSuccessfulRefreshEpochMillis = archive.lastSuccessfulRefreshEpochMillis, categoryErrors = categoryErrors),
                visibleReaders,
            )
        } catch (error: Exception) {
            val failure = error.message ?: "無法同步來源"
            val retained = oldArchive?.copy(
                isEnabled = record.enabled, hiddenCategoryIds = record.hiddenCategoryIds,
                lastAttemptEpochMillis = attemptedAt, indexRefreshError = failure,
            )
            retained?.let(archives::save)
            loadFromArchive(record, retained, failure)
        }
    }

    private fun fallbackCategory(
        record: SourceRecord,
        descriptor: CategoryDescriptor,
        categoryUrl: String,
        index: tw.terry.tshunhue.data.validation.ValidatedIndex,
        document: CachedDocument?,
    ): ValidatedCategory? = document?.let {
        runCatching {
            val bytes = archives.readCategory(record.id, descriptor.id, it)
            val category = json.decodeFromString<CategoryDocument>(bytes.decodeToString())
            ValidatedCategory(
                frames = validator.validateCategory(category, categoryUrl, descriptor, index),
                coverUrl = validator.categoryCoverUrl(category, categoryUrl),
            )
        }.getOrNull()
    }

    private suspend fun loadFromArchive(record: SourceRecord, archive: SourceArchive?, error: String? = archive?.indexRefreshError): SourceLoad = withContext(Dispatchers.IO) {
        if (archive?.index == null) return@withContext SourceLoad(SourceSummary(record, record.url, emptyList(), error ?: "尚未下載來源"), emptyList())
        return@withContext runCatching {
            val indexBytes = archives.readIndex(record.id, archive.index)
            val validatedIndex = validator.validateIndex(json.decodeFromString<CatalogIndex>(indexBytes.decodeToString()), record.url)
            val readers = if (!record.enabled) emptyList() else validatedIndex.categories.mapNotNull { (descriptor, url) ->
                if (descriptor.id in record.hiddenCategoryIds) null else archive.categories[descriptor.id]?.let { document ->
                    runCatching {
                        shardCodec.decode(
                            archives.readShard(record.id, descriptor.id), record.id, descriptor.id,
                            shardBuildDigest(archive.index.digest, document.digest),
                        )
                    }.recoverCatching {
                        val bytes = archives.readCategory(record.id, descriptor.id, document)
                        val category = json.decodeFromString<CategoryDocument>(bytes.decodeToString())
                        val frames = validator.validateCategory(category, url, descriptor, validatedIndex)
                        val buildDigest = shardBuildDigest(archive.index.digest, document.digest)
                        val shardBytes = shardCodec.encode(
                            CategoryFrameShard(
                                sourceId = record.id,
                                categoryId = descriptor.id,
                                buildDigest = buildDigest,
                                coverUrl = validator.categoryCoverUrl(category, url),
                                frames = frames,
                            ),
                        )
                        archives.writeShard(record.id, descriptor.id, shardBytes)
                        shardCodec.decode(shardBytes, record.id, descriptor.id, buildDigest)
                    }.getOrNull()
                }
            }
            SourceLoad(
                SourceSummary(record, validatedIndex.index.name, validatedIndex.index.categories, error, archive.categories.keys, archive.lastSuccessfulRefreshEpochMillis, archive.categoryRefreshErrors),
                readers,
            )
        }.getOrElse { throwable -> SourceLoad(SourceSummary(record, record.url, emptyList(), throwable.message ?: error ?: "無法讀取快取來源"), emptyList()) }
    }

    private fun List<SourceLoad>.toSnapshot(): CatalogSnapshot = fold(CatalogSnapshot()) { snapshot, source ->
        snapshot.copy(sources = snapshot.sources + source.summary, readers = snapshot.readers + source.readers)
    }

    private data class SourceLoad(val summary: SourceSummary, val readers: List<FrameShardReader>)
    private data class ValidatedCategory(val frames: List<CatalogFrame>, val coverUrl: String?)

    private fun shardBuildDigest(indexDigest: String, categoryDigest: String): String =
        archives.digest("$indexDigest\u0000$categoryDigest".encodeToByteArray())
}
