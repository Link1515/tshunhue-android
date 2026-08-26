package tw.terry.tshunhue.ime

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import tw.terry.tshunhue.data.keyboard.KeyboardCatalogSnapshot
import tw.terry.tshunhue.data.keyboard.KeyboardCatalogStore
import tw.terry.tshunhue.data.model.CatalogFrame
import tw.terry.tshunhue.domain.CatalogSearchIndex
import tw.terry.tshunhue.domain.CatalogStore
import tw.terry.tshunhue.domain.CategoryKey

data class KeyboardCategoryOption(val key: CategoryKey, val name: String, val sourceName: String) {
    val label: String get() = if (name == sourceName) name else "$sourceName · $name"
}

data class KeyboardUiState(
    val query: String = "",
    val categories: List<KeyboardCategoryOption> = emptyList(),
    val selectedCategory: CategoryKey? = null,
    val results: List<CatalogFrame> = emptyList(),
    val isLoading: Boolean = true,
    val supportsImages: Boolean = false,
    val error: String? = null,
)

/** Lifecycle-safe coordinator for IME query updates; it never fetches network catalogs. */
class KeyboardController(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dataStore = KeyboardCatalogStore(context.applicationContext)
    private var catalog = CatalogStore()
    private var searchIndex = CatalogSearchIndex(emptyList())
    private var recentIds: List<String> = emptyList()
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private val _state = MutableStateFlow(KeyboardUiState(selectedCategory = dataStore.selectedCategory()))
    val state: StateFlow<KeyboardUiState> = _state.asStateFlow()

    fun activate(query: String, supportsImages: Boolean) {
        loadJob?.cancel()
        searchJob?.cancel()
        _state.value = _state.value.copy(query = query, supportsImages = supportsImages, isLoading = true, error = null)
        loadJob = scope.launch {
            val loaded = runCatching { withContext(Dispatchers.IO) { dataStore.load() } }
            loaded.onSuccess(::publishCatalog).onFailure { error ->
                _state.value = _state.value.copy(isLoading = false, results = emptyList(), error = "請先開啟 Tshunhue 並同步至少一個分類")
            }
        }
    }

    fun deactivate() {
        loadJob?.cancel()
        searchJob?.cancel()
        loadJob = null
        searchJob = null
        _state.value = _state.value.copy(results = emptyList(), isLoading = false)
    }

    fun updateQuery(query: String) {
        if (_state.value.query == query) return
        _state.value = _state.value.copy(query = query)
        scheduleSearch()
    }

    fun selectCategory(category: CategoryKey?) {
        if (_state.value.selectedCategory == category) return
        dataStore.saveSelectedCategory(category)
        _state.value = _state.value.copy(selectedCategory = category)
        scheduleSearch()
    }

    fun recordCommittedImage(frame: CatalogFrame) {
        scope.launch(Dispatchers.IO) { dataStore.recordRecent(frame.identity) }
    }

    fun close() = scope.cancel()

    private fun publishCatalog(snapshot: KeyboardCatalogSnapshot) {
        catalog = snapshot.catalog
        recentIds = snapshot.recentIds
        searchIndex = CatalogSearchIndex(catalog.entries)
        val categories = catalog.entries
            .map { it.ref to it.value }
            .distinctBy { (_, entry) -> CategoryKey(entry.sourceUrl, entry.categoryId) }
            .map { (_, entry) -> KeyboardCategoryOption(CategoryKey(entry.sourceUrl, entry.categoryId), entry.categoryName, entry.sourceName) }
        val selected = _state.value.selectedCategory?.takeIf { selectedKey -> categories.any { it.key == selectedKey } }
        if (selected != _state.value.selectedCategory) dataStore.saveSelectedCategory(null)
        _state.value = _state.value.copy(categories = categories, selectedCategory = selected, isLoading = false, error = null)
        publishResults()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(150)
            publishResults()
        }
    }

    private fun publishResults() {
        val state = _state.value
        val category = state.selectedCategory
        val allowed = catalog.entries.asSequence()
            .filter { (_, entry) -> category == null || (entry.sourceUrl == category.sourceUrl && entry.categoryId == category.categoryId) }
            .map { it.ref }
            .toSet()
        val refs = if (state.query.isBlank()) {
            recentIds.asSequence().mapNotNull(catalog::refForIdentity).filter(allowed::contains).take(RESULT_LIMIT).toList()
        } else {
            searchIndex.search(state.query, allowed, RESULT_LIMIT).refs
        }
        _state.value = state.copy(results = refs.mapNotNull(catalog::frame))
    }

    private companion object { const val RESULT_LIMIT = 4 }
}
