package tw.terry.tshunhue.domain

import tw.terry.tshunhue.data.shard.FrameRef

sealed interface CatalogScope {
    data object Browse : CatalogScope
    data object All : CatalogScope
    data object Favorites : CatalogScope
    data object Recents : CatalogScope
    data class Source(val sourceUrl: String) : CatalogScope
    data class Category(val sourceUrl: String, val categoryId: String) : CatalogScope
}

object CatalogBrowser {
    fun refs(scope: CatalogScope, catalog: CatalogStore, favoriteIds: Set<String>, recentIds: List<String>): List<FrameRef> = when (scope) {
        CatalogScope.Browse, CatalogScope.All -> catalog.allRefs
        CatalogScope.Favorites -> catalog.entries.filter { it.value.identity in favoriteIds }.map(CatalogSearchEntry::ref)
        CatalogScope.Recents -> recentIds.mapNotNull(catalog::refForIdentity)
        is CatalogScope.Source -> catalog.entries.filter { it.value.sourceUrl == scope.sourceUrl }.map(CatalogSearchEntry::ref)
        is CatalogScope.Category -> catalog.entries.filter { it.value.sourceUrl == scope.sourceUrl && it.value.categoryId == scope.categoryId }.map(CatalogSearchEntry::ref)
    }
}
