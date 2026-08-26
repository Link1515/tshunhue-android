package tw.terry.tshunhue.data.review

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
enum class CaptionVerdict { CORRECT, INCORRECT }

@Serializable
data class CaptionReviewAnswer(
    val sourceUrl: String,
    val categoryId: String,
    val identity: String,
    val verdict: CaptionVerdict,
    val correction: String? = null,
    val answeredAtEpochMillis: Long,
)

/** Small internal-only answer ledger. It never stores image bytes or host-app keyboard text. */
class CaptionReviewStore(context: Context, private val json: Json) {
    private val preferences = context.getSharedPreferences("caption-review", Context.MODE_PRIVATE)

    fun answers(sourceUrl: String, categoryId: String): List<CaptionReviewAnswer> = load()
        .filter { it.sourceUrl == sourceUrl && it.categoryId == categoryId }

    fun record(answer: CaptionReviewAnswer) = replace { answers ->
        answers.filterNot { it.identity == answer.identity } + answer
    }

    fun remove(identity: String) = replace { answers -> answers.filterNot { it.identity == identity } }

    fun clear(sourceUrl: String, categoryId: String) = replace { answers ->
        answers.filterNot { it.sourceUrl == sourceUrl && it.categoryId == categoryId }
    }

    private fun load(): List<CaptionReviewAnswer> = runCatching {
        json.decodeFromString(ListSerializer(CaptionReviewAnswer.serializer()), preferences.getString("answers", "[]") ?: "[]")
    }.getOrDefault(emptyList())

    private fun replace(transform: (List<CaptionReviewAnswer>) -> List<CaptionReviewAnswer>) {
        preferences.edit().putString(
            "answers",
            json.encodeToString(ListSerializer(CaptionReviewAnswer.serializer()), transform(load()).sortedBy(CaptionReviewAnswer::answeredAtEpochMillis)),
        ).apply()
    }
}
