package tw.terry.tshunhue.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.terry.tshunhue.data.model.CatalogFrame
import tw.terry.tshunhue.data.model.Subsection
import tw.terry.tshunhue.data.shard.CategoryFrameShard
import tw.terry.tshunhue.data.shard.FrameRef
import tw.terry.tshunhue.data.shard.FrameShardCodec

class FrameGroupingTest {
    @Test
    fun `category scope groups frames by subsection while retaining result order`() {
        val first = FrameRef("source", "reaction", 0)
        val second = FrameRef("source", "reaction", 1)
        val third = FrameRef("source", "reaction", 2)
        val catalog = CatalogStore(readers(first, second, third))

        val sections = FrameGrouping.sections(
            CatalogScope.Category("https://example.com/index.json", "reaction"),
            catalog,
            listOf(second, first, third),
        )

        assertEquals(listOf("Greeting", "未分類"), sections.map(FrameSection::title))
        assertEquals(listOf(second, third), sections.first().refs)
        assertEquals(listOf(first), sections.last().refs)
    }

    private fun readers(vararg refs: FrameRef): List<tw.terry.tshunhue.data.shard.FrameShardReader> {
        val codec = FrameShardCodec(Json { explicitNulls = false })
        val frames = refs.mapIndexed { index, _ ->
            CatalogFrame(
                sourceUrl = "https://example.com/index.json",
                sourceName = "Example",
                categoryId = "reaction",
                categoryName = "Reaction",
                categoryOrder = 0,
                subsection = if (index == 0) null else Subsection("greeting", "Greeting"),
                caption = "frame $index",
                tags = emptyList(),
                effectiveId = "$index",
                imageUrl = "https://example.com/$index.jpg",
                providers = emptyList(),
                order = index,
            )
        }
        val digest = "a".repeat(64)
        val bytes = codec.encode(
            CategoryFrameShard(
                sourceId = "source",
                categoryId = "reaction",
                buildDigest = digest,
                frames = frames,
            ),
        )
        return listOf(codec.decode(bytes, "source", "reaction", digest))
    }
}
