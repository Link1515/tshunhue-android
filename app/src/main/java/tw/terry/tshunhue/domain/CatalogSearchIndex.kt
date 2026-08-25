package tw.terry.tshunhue.domain

import java.util.Locale
import tw.terry.tshunhue.data.shard.FrameRef

const val MAX_SEARCH_RESULTS = 500

data class CatalogSearchResults(val refs: List<FrameRef>, val truncated: Boolean)

/** Immutable scalar posting index over shard manifests, never over hydrated frame records. */
class CatalogSearchIndex(private val entries: List<CatalogSearchEntry>) {
    private val captions = entries.map { fold(it.value.caption) }
    private val tags = entries.map { entry -> entry.value.tags.map(::fold) }
    private val postings: Map<Int, IntArray> = buildPostings()

    fun search(query: String, allowedRefs: Set<FrameRef>? = null, limit: Int = MAX_SEARCH_RESULTS): CatalogSearchResults {
        val terms = query.split(Regex("\\s+")).filter(String::isNotBlank).map(::fold)
        if (terms.isEmpty()) return CatalogSearchResults(emptyList(), false)
        val whole = fold(query)
        val postingLists = terms.map(::candidatesForTerm).sortedBy(IntArray::size)
        val candidates = if (postingLists.isEmpty()) IntArray(entries.size) { it } else postingLists.drop(1).fold(postingLists.first(), ::intersect)
        val matches = candidates.asSequence()
            .filter { index -> allowedRefs == null || entries[index].ref in allowedRefs }
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
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { entries[it.index].value.sourceName }.thenBy { entries[it.index].value.categoryOrder }.thenBy { entries[it.index].value.order })
            .toList()
        val bounded = limit.coerceAtLeast(0)
        return CatalogSearchResults(matches.take(bounded).map { entries[it.index].ref }, matches.size > bounded)
    }

    private fun candidatesForTerm(term: String): IntArray {
        val scalars = term.codePoints().toArray().distinct().mapNotNull(postings::get).sortedBy(IntArray::size)
        if (scalars.isEmpty()) return intArrayOf()
        return scalars.drop(1).fold(scalars.first(), ::intersect)
    }

    private fun buildPostings(): Map<Int, IntArray> {
        val values = mutableMapOf<Int, MutableList<Int>>()
        entries.indices.forEach { index ->
            (captions[index] + " " + tags[index].joinToString(" ")).codePoints().distinct().forEach { scalar -> values.getOrPut(scalar) { mutableListOf() }.add(index) }
        }
        return values.mapValues { (_, indexes) -> indexes.toIntArray() }
    }

    private fun intersect(left: IntArray, right: IntArray): IntArray {
        val result = IntArray(minOf(left.size, right.size))
        var leftIndex = 0; var rightIndex = 0; var count = 0
        while (leftIndex < left.size && rightIndex < right.size) when {
            left[leftIndex] == right[rightIndex] -> { result[count++] = left[leftIndex]; leftIndex++; rightIndex++ }
            left[leftIndex] < right[rightIndex] -> leftIndex++
            else -> rightIndex++
        }
        return result.copyOf(count)
    }

    private fun fold(value: String): String = value.lowercase(Locale.ROOT)
    private data class Ranked(val index: Int, val score: Int)
}
