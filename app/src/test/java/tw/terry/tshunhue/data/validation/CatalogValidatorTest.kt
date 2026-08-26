package tw.terry.tshunhue.data.validation

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.terry.tshunhue.data.model.CategoryDocument

class CatalogValidatorTest {
    private val validator = CatalogValidator()

    @Test
    fun `category cover resolves relative to its document`() {
        val coverUrl = validator.categoryCoverUrl(
            CategoryDocument(
                version = 1,
                id = "demo",
                name = "Demo",
                language = "zh-TW",
                cover = "images/cover.jpg",
                frames = emptyList(),
            ),
            "https://example.com/catalogs/demo.json",
        )

        assertEquals("https://example.com/catalogs/images/cover.jpg", coverUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `category cover rejects non HTTPS URL`() {
        validator.categoryCoverUrl(
            CategoryDocument(
                version = 1,
                id = "demo",
                name = "Demo",
                language = "zh-TW",
                cover = "http://example.com/cover.jpg",
                frames = emptyList(),
            ),
            "https://example.com/catalogs/demo.json",
        )
    }
}
