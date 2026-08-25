package tw.terry.tshunhue.domain

import java.util.Locale
import tw.terry.tshunhue.data.model.CatalogFrame

const val MAX_SEARCH_RESULTS = 500

data class CatalogSearchResults(val frames: List<CatalogFrame>, val truncated: Boolean)

/**
 * Immutable scalar posting index. It narrows candidates first, then verifies the exact same
 * caption/tag substring rules used by the catalog UI so indexing never changes an answer.
 */
class CatalogSearchIndex(private val frames: List<CatalogFrame>) {
    private val captions = frames.map { fold(it.caption) }
    private val tags = frames.map { frame -> frame.tags.map(::fold) }
    private val postings: Map<Int, IntArray> = buildPostings()

    fun search(query: String, allowedIdentities: Set<String>? = null, limit: Int = MAX_SEARCH_RESULTS): CatalogSearchResults {
        val terms = query.split(Regex("\\s+")).filter(String::isNotBlank).map(::fold)
        if (terms.isEmpty()) return CatalogSearchResults(emptyList(), false)
        val whole = fold(query)
        val postingLists = terms.map(::candidatesForTerm).sortedBy(IntArray::size)
        val candidates = if (postingLists.isEmpty()) IntArray(frames.size) { it } else {
            postingLists.drop(1).fold(postingLists.first(), ::intersect)
        }

        val matches = candidates.asSequence()
            .filter { index -> allowedIdentities == null || frames[index].identity in allowedIdentities }
            .mapNotNull { index ->
                val caption = captions[index]
                val captionHasAllTerms = terms.all(caption::contains)
                val matched = captionHasAllTerms || terms.all { term -> caption.contains(term) || tags[index].any { it.contains(term) } }
                if (!matched) null else Ranked(index, when {
                    caption == whole -> 400
                    caption.startsWith(whole) -> 300
                    captionHasAllTerms -> 200
                    else -> 100
                })
            }
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { frames[it.index].sourceName }.thenBy { frames[it.index].categoryOrder }.thenBy { frames[it.index].order })
            .toList()
        val bounded = limit.coerceAtLeast(0)
        return CatalogSearchResults(matches.take(bounded).map { frames[it.index] }, matches.size > bounded)
    }

    private fun candidatesForTerm(term: String): IntArray {
        val scalars = term.codePoints().toArray().distinct().mapNotNull(postings::get).sortedBy(IntArray::size)
        if (scalars.isEmpty()) return intArrayOf()
        return scalars.drop(1).fold(scalars.first(), ::intersect)
    }

    private fun buildPostings(): Map<Int, IntArray> {
        val values = mutableMapOf<Int, MutableList<Int>>()
        frames.indices.forEach { index ->
            (captions[index] + " " + tags[index].joinToString(" ")).codePoints().distinct().forEach { scalar ->
                values.getOrPut(scalar) { mutableListOf() }.add(index)
            }
        }
        return values.mapValues { (_, indexes) -> indexes.toIntArray() }
    }

    private fun intersect(left: IntArray, right: IntArray): IntArray {
        val result = IntArray(minOf(left.size, right.size))
        var leftIndex = 0; var rightIndex = 0; var count = 0
        while (leftIndex < left.size && rightIndex < right.size) {
            when {
                left[leftIndex] == right[rightIndex] -> { result[count++] = left[leftIndex]; leftIndex++; rightIndex++ }
                left[leftIndex] < right[rightIndex] -> leftIndex++
                else -> rightIndex++
            }
        }
        return result.copyOf(count)
    }

    private fun fold(value: String): String = value.lowercase(Locale.ROOT)
    private data class Ranked(val index: Int, val score: Int)
}
