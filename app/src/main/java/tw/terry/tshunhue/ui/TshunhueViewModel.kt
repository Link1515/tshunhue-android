package tw.terry.tshunhue.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import tw.terry.tshunhue.data.model.CatalogFrame
import tw.terry.tshunhue.data.model.CatalogSnapshot
import tw.terry.tshunhue.data.model.SourceRecord
import tw.terry.tshunhue.data.model.SourceSummary
import tw.terry.tshunhue.data.remote.HttpCatalogClient
import tw.terry.tshunhue.data.repository.CatalogRepository
import tw.terry.tshunhue.data.repository.SourceStore
import tw.terry.tshunhue.data.validation.CatalogValidator
import tw.terry.tshunhue.domain.CatalogScope
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class AppUiState(
    val isRefreshing: Boolean = true,
    val sources: List<SourceSummary> = emptyList(),
    val frames: List<CatalogFrame> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val recentIds: List<String> = emptyList(),
    val selectedFrame: CatalogFrame? = null,
    val selectedScope: CatalogScope = CatalogScope.All,
    val message: String? = null,
)

/** Android counterpart of the Swift AppModel: one observable command surface for screens. */
class TshunhueViewModel(application: Application) : AndroidViewModel(application) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val validator = CatalogValidator()
    private val sourceStore = SourceStore(application, json)
    private val repository = CatalogRepository(HttpCatalogClient(validator), validator, json)
    private val preferences = application.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(AppUiState(favoriteIds = loadSet("favorites"), recentIds = loadList("recents")))
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(isRefreshing = true, message = null)
        publish(repository.refresh(sourceStore.all()))
    }

    fun addSource(input: String) = viewModelScope.launch {
        val safeUrl = runCatching { validator.requireHttps(input.trim(), "來源 URL") }.getOrElse {
            _state.value = _state.value.copy(message = it.message ?: "請輸入有效 HTTPS URL")
            return@launch
        }
        if (sourceStore.all().any { it.url == safeUrl }) {
            _state.value = _state.value.copy(message = "此來源已加入")
            return@launch
        }
        _state.value = _state.value.copy(isRefreshing = true, message = null)
        val candidate = SourceRecord(UUID.randomUUID().toString(), safeUrl)
        val snapshot = repository.refresh(sourceStore.all() + candidate)
        val candidateSummary = snapshot.sources.lastOrNull()
        if (candidateSummary?.error != null) {
            _state.value = _state.value.copy(isRefreshing = false, message = candidateSummary.error)
        } else {
            sourceStore.save(sourceStore.all() + candidate)
            publish(snapshot)
        }
    }

    fun setSourceEnabled(id: String, enabled: Boolean) = updateSource(id) { it.copy(enabled = enabled) }
    fun setCategoryHidden(id: String, categoryId: String, hidden: Boolean) = updateSource(id) {
        it.copy(hiddenCategoryIds = if (hidden) it.hiddenCategoryIds + categoryId else it.hiddenCategoryIds - categoryId)
    }

    fun removeSource(id: String) = viewModelScope.launch {
        sourceStore.remove(id)
        refresh()
    }

    fun toggleFavorite(frame: CatalogFrame) {
        val next = _state.value.favoriteIds.let { if (frame.identity in it) it - frame.identity else it + frame.identity }
        saveSet("favorites", next)
        _state.value = _state.value.copy(favoriteIds = next)
    }

    fun select(frame: CatalogFrame) {
        val recents = (listOf(frame.identity) + _state.value.recentIds.filterNot { it == frame.identity }).take(100)
        saveList("recents", recents)
        _state.value = _state.value.copy(selectedFrame = frame, recentIds = recents)
    }

    fun openCategory(sourceUrl: String, categoryId: String) {
        _state.value = _state.value.copy(selectedScope = CatalogScope.Category(sourceUrl, categoryId))
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    private fun updateSource(id: String, transform: (SourceRecord) -> SourceRecord) = viewModelScope.launch {
        sourceStore.all().firstOrNull { it.id == id }?.let { sourceStore.update(transform(it)) }
        refresh()
    }

    private fun publish(snapshot: CatalogSnapshot) {
        _state.value = _state.value.copy(
            isRefreshing = false,
            sources = snapshot.sources,
            frames = snapshot.frames.sortedWith(compareBy<CatalogFrame> { it.sourceName }.thenBy { it.categoryOrder }.thenBy { it.order }),
        )
    }

    private fun loadList(key: String): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), preferences.getString(key, "[]") ?: "[]")
    }.getOrDefault(emptyList())
    private fun loadSet(key: String): Set<String> = loadList(key).toSet()
    private fun saveList(key: String, value: List<String>) { preferences.edit().putString(key, json.encodeToString(ListSerializer(String.serializer()), value)).apply() }
    private fun saveSet(key: String, value: Set<String>) = saveList(key, value.toList())
}
