package tw.terry.tshunhue.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import tw.terry.tshunhue.data.image.ImageRepository
import tw.terry.tshunhue.data.model.CatalogFrame
import tw.terry.tshunhue.data.model.CatalogSnapshot
import tw.terry.tshunhue.data.model.SourceRecord
import tw.terry.tshunhue.data.model.SourceSummary
import tw.terry.tshunhue.data.model.RefreshFrequency
import tw.terry.tshunhue.data.remote.HttpCatalogClient
import tw.terry.tshunhue.data.repository.CatalogRepository
import tw.terry.tshunhue.data.repository.SourceStore
import tw.terry.tshunhue.data.sync.CatalogArchiveStore
import tw.terry.tshunhue.data.validation.CatalogValidator
import tw.terry.tshunhue.domain.CatalogScope
import tw.terry.tshunhue.domain.CatalogStore
import tw.terry.tshunhue.domain.FrameReportDraft
import tw.terry.tshunhue.domain.ReportProblem
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
    val catalog: CatalogStore = CatalogStore(),
    val favoriteIds: Set<String> = emptySet(),
    val recentIds: List<String> = emptyList(),
    val imageCacheBytes: Long = 0,
    val selectedFrame: CatalogFrame? = null,
    val selectedScope: CatalogScope = CatalogScope.All,
    val preparedReport: FrameReportDraft? = null,
    val groupFrames: Boolean = false,
    val refreshFrequency: RefreshFrequency = RefreshFrequency.WEEKLY,
    val message: String? = null,
)

/** Android counterpart of the Swift AppModel: one observable command surface for screens. */
class TshunhueViewModel(application: Application) : AndroidViewModel(application) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val validator = CatalogValidator()
    private val sourceStore = SourceStore(application, json)
    private val archiveStore = CatalogArchiveStore(application, json)
    private val repository = CatalogRepository(HttpCatalogClient(validator), validator, json, archiveStore)
    val imageRepository = ImageRepository(application, HttpCatalogClient(validator), json)
    private val preferences = application.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        AppUiState(
            favoriteIds = loadSet("favorites"),
            recentIds = loadList("recents"),
            groupFrames = preferences.getBoolean("groupFrames", false),
            selectedScope = loadSelectedScope(),
            refreshFrequency = loadRefreshFrequency(),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            publishImageCacheSize()
            publish(repository.loadCached(sourceStore.all()))
            refresh(force = false)
        }
    }

    fun refresh(force: Boolean = true) = viewModelScope.launch {
        _state.value = _state.value.copy(isRefreshing = true, message = null)
        publish(repository.refresh(sourceStore.all(), _state.value.refreshFrequency, force))
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
        val snapshot = repository.refresh(sourceStore.all() + candidate, _state.value.refreshFrequency, force = true)
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
        val sourceUrl = sourceStore.all().firstOrNull { it.id == id }?.url
        sourceStore.remove(id)
        archiveStore.removeSource(id)
        sourceUrl?.let(::removeLibraryItemsForSource)
        refresh()
    }

    fun toggleFavorite(frame: CatalogFrame) {
        val next = _state.value.favoriteIds.let { if (frame.identity in it) it - frame.identity else it + frame.identity }
        saveSet("favorites", next)
        _state.value = _state.value.copy(favoriteIds = next)
    }

    fun select(frame: CatalogFrame) {
        preferences.edit().putString("selectedFrame", frame.identity).apply()
        _state.value = _state.value.copy(selectedFrame = frame)
    }

    fun prepareCaptionReport(frame: CatalogFrame, correction: String) {
        preferences.edit().putString("selectedFrame", frame.identity).apply()
        _state.value = _state.value.copy(
            selectedFrame = frame,
            preparedReport = FrameReportDraft(
                problem = ReportProblem.INCORRECT_CAPTION,
                suggestedCaption = correction.trim(),
                remarks = "Submitted from caption review.",
            ),
        )
    }

    fun clearPreparedReport() {
        _state.value = _state.value.copy(preparedReport = null)
    }

    fun recordRecent(frame: CatalogFrame) {
        val recents = (listOf(frame.identity) + _state.value.recentIds.filterNot { it == frame.identity }).take(100)
        saveList("recents", recents)
        _state.value = _state.value.copy(recentIds = recents)
    }

    fun removeRecent(identity: String) {
        val recents = _state.value.recentIds - identity
        saveList("recents", recents)
        _state.value = _state.value.copy(recentIds = recents)
    }

    fun clearRecents() {
        saveList("recents", emptyList())
        _state.value = _state.value.copy(recentIds = emptyList())
    }

    fun clearImageCache() = viewModelScope.launch {
        imageRepository.clear()
        publishImageCacheSize()
        _state.value = _state.value.copy(message = "已清除影像快取")
    }

    fun refreshImageCacheSize() = viewModelScope.launch { publishImageCacheSize() }

    fun openCategory(sourceUrl: String, categoryId: String) {
        val scope = CatalogScope.Category(sourceUrl, categoryId)
        saveSelectedScope(scope)
        _state.value = _state.value.copy(selectedScope = scope)
    }

    fun openSource(sourceUrl: String) {
        val scope = CatalogScope.Source(sourceUrl)
        saveSelectedScope(scope)
        _state.value = _state.value.copy(selectedScope = scope)
    }

    /** Mirrors the iOS active-scene refresh without bypassing the user's refresh policy. */
    fun refreshWhenActive() {
        if (!_state.value.isRefreshing) refresh(force = false)
    }

    fun toggleFrameGrouping() {
        val groupFrames = !_state.value.groupFrames
        preferences.edit().putBoolean("groupFrames", groupFrames).apply()
        _state.value = _state.value.copy(groupFrames = groupFrames)
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    fun setRefreshFrequency(frequency: RefreshFrequency) {
        preferences.edit().putString("refreshFrequency", frequency.name).apply()
        _state.value = _state.value.copy(refreshFrequency = frequency)
    }

    fun moveSource(id: String, delta: Int) = viewModelScope.launch {
        val records = sourceStore.all().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        val destination = index + delta
        if (index !in records.indices || destination !in records.indices) return@launch
        val moved = records.removeAt(index)
        records.add(destination, moved)
        sourceStore.save(records)
        publish(repository.loadCached(records))
    }

    private fun updateSource(id: String, transform: (SourceRecord) -> SourceRecord) = viewModelScope.launch {
        sourceStore.all().firstOrNull { it.id == id }?.let { sourceStore.update(transform(it)) }
        publish(repository.loadCached(sourceStore.all()))
    }

    private fun publish(snapshot: CatalogSnapshot) {
        val catalog = CatalogStore(snapshot.readers)
        val selected = preferences.getString("selectedFrame", null)
            ?.let(catalog::refForIdentity)
            ?.let(catalog::frame)
        _state.value = _state.value.copy(
            isRefreshing = false,
            sources = snapshot.sources,
            catalog = catalog,
            selectedFrame = selected,
        )
    }

    private suspend fun publishImageCacheSize() {
        _state.value = _state.value.copy(imageCacheBytes = imageRepository.cacheSize())
    }

    private fun removeLibraryItemsForSource(sourceUrl: String) {
        val favorites = _state.value.favoriteIds.filterNot { it.substringBefore('|') == sourceUrl }.toSet()
        val recents = _state.value.recentIds.filterNot { it.substringBefore('|') == sourceUrl }
        saveSet("favorites", favorites)
        saveList("recents", recents)
        _state.value = _state.value.copy(favoriteIds = favorites, recentIds = recents)
    }

    private fun loadList(key: String): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), preferences.getString(key, "[]") ?: "[]")
    }.getOrDefault(emptyList())
    private fun loadSet(key: String): Set<String> = loadList(key).toSet()
    private fun loadRefreshFrequency(): RefreshFrequency = preferences.getString("refreshFrequency", null)
        ?.let { value -> RefreshFrequency.entries.firstOrNull { it.name == value } }
        ?: RefreshFrequency.WEEKLY
    private fun loadSelectedScope(): CatalogScope = when (preferences.getString("selectedScopeType", null)) {
        "source" -> preferences.getString("selectedScopeUrl", null)?.let(CatalogScope::Source)
        "category" -> {
            val sourceUrl = preferences.getString("selectedScopeUrl", null)
            val categoryId = preferences.getString("selectedScopeCategory", null)
            if (sourceUrl != null && categoryId != null) CatalogScope.Category(sourceUrl, categoryId) else null
        }
        else -> null
    } ?: CatalogScope.All
    private fun saveSelectedScope(scope: CatalogScope) {
        preferences.edit().apply {
            when (scope) {
                is CatalogScope.Source -> putString("selectedScopeType", "source").putString("selectedScopeUrl", scope.sourceUrl).remove("selectedScopeCategory")
                is CatalogScope.Category -> putString("selectedScopeType", "category").putString("selectedScopeUrl", scope.sourceUrl).putString("selectedScopeCategory", scope.categoryId)
                else -> remove("selectedScopeType").remove("selectedScopeUrl").remove("selectedScopeCategory")
            }
        }.apply()
    }
    private fun saveList(key: String, value: List<String>) { preferences.edit().putString(key, json.encodeToString(ListSerializer(String.serializer()), value)).apply() }
    private fun saveSet(key: String, value: Set<String>) = saveList(key, value.toList())
}
