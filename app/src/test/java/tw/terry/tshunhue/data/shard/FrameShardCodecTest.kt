package tw.terry.tshunhue.data.shard

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.terry.tshunhue.data.model.CatalogFrame

class FrameShardCodecTest {
    private val codec = FrameShardCodec(Json { ignoreUnknownKeys = true })
    private val sourceId = "bf4c7ca5-f9db-405a-9ea2-39c64e339b50"
    private val buildDigest = "f".repeat(64)

    @Test
    fun `round trip retains frames and resolves only matching refs`() {
        val bytes = codec.encode(
            CategoryFrameShard(
                sourceId = sourceId,
                categoryId = "demo",
                buildDigest = buildDigest,
                frames = listOf(frame("first"), frame("second")),
            ),
        )

        val reader = codec.decode(bytes, sourceId, "demo", buildDigest)

        assertEquals(listOf("first", "second"), reader.allFrames().map(CatalogFrame::effectiveId))
        assertEquals("second", reader.frame(reader.refs[1])?.effectiveId)
        assertNull(reader.frame(FrameRef(sourceId, "other", 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects shard built from a different document revision`() {
        val bytes = codec.encode(CategoryFrameShard(sourceId = sourceId, categoryId = "demo", buildDigest = buildDigest, frames = emptyList()))

        codec.decode(bytes, sourceId, "demo", "0".repeat(64))
    }

    private fun frame(id: String) = CatalogFrame(
        sourceUrl = "https://example.com/catalog.json",
        sourceName = "Example",
        categoryId = "demo",
        categoryName = "Demo",
        categoryOrder = 0,
        caption = id,
        tags = emptyList(),
        effectiveId = id,
        imageUrl = "https://example.com/$id.jpg",
        providers = emptyList(),
        order = 0,
    )
}
