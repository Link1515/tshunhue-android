package tw.terry.tshunhue.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.terry.tshunhue.data.model.CatalogFrame

class CatalogSearchIndexTest {
    @Test fun `matches every term across caption and tags and ranks caption matches first`() {
        val exact = frame(id = "exact", caption = "spring flowers", tags = emptyList())
        val tagMatch = frame(id = "tag", caption = "spring", tags = listOf("flowers"))
        val partial = frame(id = "partial", caption = "bright spring flowers", tags = emptyList())

        val results = CatalogSearchIndex(listOf(tagMatch, partial, exact)).search("spring flowers")

        assertEquals(listOf("exact", "partial", "tag"), results.frames.map(CatalogFrame::effectiveId))
        assertTrue(!results.truncated)
    }

    @Test fun `caps broad matches and reports truncation`() {
        val frames = (0..MAX_SEARCH_RESULTS).map { number -> frame(id = number.toString(), caption = "same caption") }

        val results = CatalogSearchIndex(frames).search("same")

        assertEquals(MAX_SEARCH_RESULTS, results.frames.size)
        assertTrue(results.truncated)
    }

    private fun frame(id: String, caption: String, tags: List<String> = emptyList()) = CatalogFrame(
        sourceUrl = "https://example.com/index.json", sourceName = "Example", categoryId = "category",
        categoryName = "Category", categoryOrder = 0, caption = caption, tags = tags, effectiveId = id,
        imageUrl = "https://example.com/$id.jpg", providers = emptyList(), order = id.toIntOrNull() ?: 0,
    )
}
