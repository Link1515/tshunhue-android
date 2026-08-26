package tw.terry.tshunhue.domain

import tw.terry.tshunhue.data.shard.FrameRef

/** A display section derived from shard manifest fields, without hydrating image records. */
data class FrameSection(
    val id: String,
    val title: String,
    val subtitle: String?,
    val refs: List<FrameRef>,
)

/** Preserves result order while grouping a catalog screen by its relevant hierarchy level. */
object FrameGrouping {
    fun sections(scope: CatalogScope, catalog: CatalogStore, refs: List<FrameRef>): List<FrameSection> {
        val grouped = linkedMapOf<String, MutableFrameSection>()
        refs.forEach { ref ->
            val entry = catalog.entry(ref) ?: return@forEach
            val key = if (scope is CatalogScope.Category) {
                val subsectionId = entry.subsectionId ?: "unsectioned"
                "subsection:${entry.sourceUrl}:${entry.categoryId}:$subsectionId"
            } else {
                "category:${entry.sourceUrl}:${entry.categoryId}"
            }
            val section = grouped.getOrPut(key) {
                if (scope is CatalogScope.Category) {
                    MutableFrameSection(entry.subsectionName ?: "未分類", entry.categoryName)
                } else {
                    MutableFrameSection(entry.categoryName, entry.sourceName.takeUnless { it == entry.categoryName })
                }
            }
            section.refs += ref
        }
        return grouped.map { (id, section) -> FrameSection(id, section.title, section.subtitle, section.refs) }
    }

    private data class MutableFrameSection(
        val title: String,
        val subtitle: String?,
        val refs: MutableList<FrameRef> = mutableListOf(),
    )
}
