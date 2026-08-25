package tw.terry.tshunhue.data.repository

import android.content.Context
import tw.terry.tshunhue.data.model.SourceRecord
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persisted configuration only. Untrusted remote catalogs are fetched and validated fresh for each refresh. */
class SourceStore(context: Context, private val json: Json) {
    private val preferences = context.getSharedPreferences("sources", Context.MODE_PRIVATE)
    private val key = "records"

    fun all(): List<SourceRecord> = runCatching {
        json.decodeFromString<List<SourceRecord>>(preferences.getString(key, "[]") ?: "[]")
    }.getOrDefault(emptyList())

    fun save(records: List<SourceRecord>) {
        check(preferences.edit().putString(key, json.encodeToString(records)).commit()) { "無法儲存來源設定" }
    }

    fun add(url: String): SourceRecord {
        val next = SourceRecord(id = UUID.randomUUID().toString(), url = url)
        save(all() + next)
        return next
    }

    fun update(record: SourceRecord) = save(all().map { if (it.id == record.id) record else it })
    fun remove(id: String) = save(all().filterNot { it.id == id })
}
