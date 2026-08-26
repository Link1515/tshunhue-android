package tw.terry.tshunhue.data.keyboard

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tw.terry.tshunhue.data.remote.HttpCatalogClient
import tw.terry.tshunhue.data.repository.CatalogRepository
import tw.terry.tshunhue.data.repository.SourceStore
import tw.terry.tshunhue.data.sync.CatalogArchiveStore
import tw.terry.tshunhue.data.validation.CatalogValidator
import tw.terry.tshunhue.domain.CatalogStore
import tw.terry.tshunhue.domain.CategoryKey

data class KeyboardCatalogSnapshot(val catalog: CatalogStore, val recentIds: List<String>)

/** Read-only view of the app's validated, on-device catalog for the IME process. */
class KeyboardCatalogStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val validator = CatalogValidator()
    private val sourceStore = SourceStore(context, json)
    private val archiveStore = CatalogArchiveStore(context, json)
    private val repository = CatalogRepository(HttpCatalogClient(validator), validator, json, archiveStore)
    private val library = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val keyboard = context.getSharedPreferences("keyboard", Context.MODE_PRIVATE)

    suspend fun load(): KeyboardCatalogSnapshot {
        val snapshot = repository.loadCached(sourceStore.all())
        return KeyboardCatalogSnapshot(CatalogStore(snapshot.readers), recentIds())
    }

    fun selectedCategory(): CategoryKey? = runCatching {
        json.decodeFromString(CategoryKey.serializer(), keyboard.getString(SELECTED_CATEGORY_KEY, null) ?: return null)
    }.getOrNull()

    fun saveSelectedCategory(category: CategoryKey?) {
        keyboard.edit().apply {
            if (category == null) remove(SELECTED_CATEGORY_KEY)
            else putString(SELECTED_CATEGORY_KEY, json.encodeToString(CategoryKey.serializer(), category))
        }.apply()
    }

    fun recordRecent(identity: String) {
        val next = (listOf(identity) + recentIds().filterNot { it == identity }).take(100)
        library.edit().putString(RECENTS_KEY, json.encodeToString(ListSerializer(String.serializer()), next)).apply()
    }

    private fun recentIds(): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), library.getString(RECENTS_KEY, "[]") ?: "[]")
    }.getOrDefault(emptyList())

    private companion object {
        const val RECENTS_KEY = "recents"
        const val SELECTED_CATEGORY_KEY = "selectedCategory"
    }
}
