package tw.terry.tshunhue.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.terry.tshunhue.data.model.CatalogFrame

class FrameReportServiceTest {
    @Test
    fun `prefilled GitHub issue is encoded and retains existing query`() {
        val frame = frame("https://github.com/example/catalog/issues/new?template=report.md#form")
        val payload = FrameReportService.payload(frame, FrameReportDraft(problem = ReportProblem.OTHER, remarks = "line one\nline two"))

        val url = requireNotNull(FrameReportService.prefilledIssueUrl(frame.reportUrl!!, payload))

        assertTrue(url.contains("template=report.md&title=Report%3A%20demo%20%2F%20one"))
        assertTrue(url.contains("body=Source%3A%20https%3A%2F%2Fexample.com%2Findex.json"))
        assertTrue(url.endsWith("#form"))
    }

    @Test
    fun `only a changed caption completes caption correction`() {
        val frame = frame("https://example.com/report")
        assertFalse(FrameReportDraft(suggestedCaption = " original ").isComplete(frame))
        assertTrue(FrameReportDraft(suggestedCaption = "replacement").isComplete(frame))
        assertEquals(ReportDestination.ReportPage(frame.reportUrl!!), FrameReportService.destination(frame))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `incomplete draft cannot create a report payload`() {
        val frame = frame("https://github.com/example/catalog/issues/new")

        FrameReportService.payload(frame, FrameReportDraft(suggestedCaption = frame.caption))
    }

    private fun frame(reportUrl: String) = CatalogFrame(
        sourceUrl = "https://example.com/index.json", sourceName = "Example", categoryId = "demo", categoryName = "Demo", categoryOrder = 0,
        caption = "original", tags = emptyList(), effectiveId = "one", imageUrl = "https://example.com/one.jpg", providers = emptyList(), reportUrl = reportUrl, order = 0,
    )
}
