package tw.terry.tshunhue.domain

import tw.terry.tshunhue.data.model.CatalogFrame

sealed interface CatalogScope {
    data object Browse : CatalogScope
    data object All : CatalogScope
    data object Favorites : CatalogScope
    data object Recents : CatalogScope
    data class Source(val sourceUrl: String) : CatalogScope
    data class Category(val sourceUrl: String, val categoryId: String) : CatalogScope
}

object CatalogBrowser {
    fun frames(scope: CatalogScope, all: List<CatalogFrame>, favoriteIds: Set<String>, recentIds: List<String>): List<CatalogFrame> = when (scope) {
        CatalogScope.Browse, CatalogScope.All -> all
        CatalogScope.Favorites -> all.filter { it.identity in favoriteIds }
        CatalogScope.Recents -> recentIds.mapNotNull { id -> all.firstOrNull { it.identity == id } }
        is CatalogScope.Source -> all.filter { it.sourceUrl == scope.sourceUrl }
        is CatalogScope.Category -> all.filter { it.sourceUrl == scope.sourceUrl && it.categoryId == scope.categoryId }
    }

    fun search(query: String, frames: List<CatalogFrame>): List<CatalogFrame> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) return frames
        return frames.filter { frame ->
            val haystack = listOf(frame.caption, frame.categoryName, frame.sourceName, *frame.tags.toTypedArray()).joinToString(" ").lowercase()
            terms.all(haystack::contains)
        }
    }
}
