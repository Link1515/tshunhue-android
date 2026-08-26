package tw.terry.tshunhue.data.validation

import tw.terry.tshunhue.data.model.*
import java.net.URI
import java.security.MessageDigest

object CatalogLimits {
    const val INDEX_BYTES = 2 * 1_024 * 1_024
    const val CATEGORY_BYTES = 20 * 1_024 * 1_024
    const val FRAMES_PER_CATEGORY = 65_535
    const val IMAGE_BYTES = 32 * 1_024 * 1_024
    const val MAX_REDIRECTS = 5
}

class CatalogValidationException(message: String) : IllegalArgumentException(message)

data class ValidatedIndex(
    val sourceUrl: String,
    val index: CatalogIndex,
    val categories: List<Pair<CategoryDescriptor, String>>,
)

/** Boundary validation for community-provided JSON and every remote URL it contains. */
class CatalogValidator {
    private val identifier = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val language = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")

    fun validateIndex(index: CatalogIndex, sourceUrl: String): ValidatedIndex {
        val safeSource = requireHttps(sourceUrl, "source URL")
        require(index.version == 1) { "不支援的目錄版本：${index.version}" }
        requireText(index.name, "目錄名稱")
        listOf(index.homepage to "homepage", index.contact to "contact", index.report to "report").forEach { (url, label) ->
            url?.let { requireHttps(it, label) }
        }
        val ids = mutableSetOf<String>()
        val categories = index.categories.map { descriptor ->
            require(identifier.matches(descriptor.id)) { "無效的分類 ID" }
            require(ids.add(descriptor.id)) { "重複的分類 ID：${descriptor.id}" }
            requireText(descriptor.name, "分類名稱")
            descriptor.language?.let { require(language.matches(it)) { "無效的語言代碼" } }
            require(descriptor.frames == null || descriptor.frames in 0..CatalogLimits.FRAMES_PER_CATEGORY) { "無效的影格數" }
            descriptor to resolveHttps(descriptor.url, safeSource, "分類 URL")
        }
        return ValidatedIndex(safeSource, index, categories)
    }

    fun validateCategory(
        document: CategoryDocument,
        documentUrl: String,
        descriptor: CategoryDescriptor,
        source: ValidatedIndex,
    ): List<CatalogFrame> {
        require(document.version == 1) { "不支援的分類版本：${document.version}" }
        require(document.id == descriptor.id && document.name == descriptor.name) { "分類文件與索引不一致" }
        require(language.matches(document.language)) { "無效的語言代碼" }
        require(document.frames.size <= CatalogLimits.FRAMES_PER_CATEGORY) { "分類影格數過多" }
        categoryCoverUrl(document, documentUrl)
        document.attribution?.let { requireText(it.text, "出處"); it.url?.let { url -> requireHttps(url, "出處 URL") } }
        validateProviders(document.providers)
        val subsectionById = document.subsections.associateBy {
            require(identifier.matches(it.id)) { "無效的章節 ID" }; requireText(it.name, "章節名稱"); validateProviders(it.providers); it.id
        }
        require(subsectionById.size == document.subsections.size) { "重複的章節 ID" }
        val frameIds = mutableSetOf<String>()
        val categoryOrder = source.index.categories.indexOfFirst { it.id == document.id }
        return document.frames.mapIndexed { order, frame ->
            requireText(frame.caption, "影格說明")
            frame.tags.forEach { requireText(it, "影格標籤") }
            frame.id?.let { require(identifier.matches(it)) { "無效的影格 ID" } }
            val subsection = frame.subsection?.let { subsectionById[it] ?: throw CatalogValidationException("影格指向不存在的章節") }
            val effectiveId = frame.id ?: derivedFrameId(document.id, frame)
            require(frameIds.add(effectiveId)) { "重複的影格 ID：$effectiveId" }
            CatalogFrame(
                sourceUrl = source.sourceUrl,
                sourceName = source.index.name,
                categoryId = document.id,
                categoryName = document.name,
                categoryOrder = categoryOrder,
                subsection = subsection,
                caption = frame.caption,
                tags = frame.tags,
                timecode = frame.timecode,
                effectiveId = effectiveId,
                imageUrl = resolveHttps(frame.url, documentUrl, "影像 URL"),
                providers = subsection?.providers?.ifEmpty { document.providers } ?: document.providers,
                attribution = document.attribution,
                reportUrl = source.index.report,
                order = order,
            )
        }
    }

    /** Resolves the optional category cover after applying the same URL policy as frame images. */
    fun categoryCoverUrl(document: CategoryDocument, documentUrl: String): String? =
        document.cover?.let { resolveHttps(it, documentUrl, "分類封面 URL") }

    fun requireHttps(value: String, field: String): String {
        val uri = try { URI(value) } catch (_: Exception) { throw CatalogValidationException("無效的 $field") }
        require(uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null) { "必須提供 HTTPS $field" }
        return uri.normalize().toString()
    }

    private fun resolveHttps(value: String, base: String, field: String): String = requireHttps(URI(base).resolve(value).toString(), field)
    private fun requireText(value: String, field: String) = require(value.trim().isNotEmpty()) { "$field 不可為空白" }

    private fun validateProviders(providers: List<Provider>) = providers.forEach { provider ->
        requireText(provider.name, "播放來源名稱")
        val resolved = provider.url.replace("{seconds}", "0").replace("{milliseconds}", "0")
        require(!resolved.contains('{') && !resolved.contains('}')) { "無效的播放來源參數" }
        requireHttps(resolved, "播放來源 URL")
    }

    private fun derivedFrameId(categoryId: String, frame: FrameDocument): String {
        val input = listOf(categoryId, frame.subsection ?: "<none>", frame.timecode?.toString() ?: "<none>", frame.caption).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
}
