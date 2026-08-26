package tw.terry.tshunhue.domain

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import tw.terry.tshunhue.data.model.CatalogFrame
import tw.terry.tshunhue.data.model.TimecodeSerializer

enum class ReportProblem(val wireValue: String, val label: String) {
    INCORRECT_CAPTION("incorrect-caption", "說明文字不正確"),
    FALSE_FRAME("false-frame", "影像不正確"),
    BLURRY_IMAGE("blurry", "影像模糊"),
    OTHER("other", "其他問題"),
}

data class FrameReportDraft(
    val problem: ReportProblem = ReportProblem.INCORRECT_CAPTION,
    val suggestedCaption: String = "",
    val remarks: String = "",
) {
    fun isComplete(frame: CatalogFrame): Boolean = when (problem) {
        ReportProblem.INCORRECT_CAPTION -> suggestedCaption.trim().isNotEmpty() && suggestedCaption.trim() != frame.caption.trim()
        ReportProblem.OTHER -> remarks.trim().isNotEmpty()
        ReportProblem.FALSE_FRAME, ReportProblem.BLURRY_IMAGE -> true
    }
}

data class ReportPayload(
    val sourceUrl: String,
    val categoryId: String,
    val frameId: String,
    val imageUrl: String,
    val timecode: String?,
    val publishedCaption: String,
    val problem: ReportProblem,
    val suggestedCaption: String?,
    val remarks: String?,
) {
    val issueTitle: String get() = "Report: $categoryId / $frameId"
    val issueBody: String get() = buildList {
        add("Source: $sourceUrl")
        add("Category: $categoryId")
        add("Frame: $frameId")
        add("Image: $imageUrl")
        timecode?.let { add("Timecode: $it") }
        add("Problem: ${problem.wireValue}")
        add("Caption: $publishedCaption")
        suggestedCaption?.let { add("Suggested caption: $it") }
        remarks?.let { add("Remarks: $it") }
    }.joinToString("\n")
}

sealed interface ReportDestination {
    data class PrefilledIssue(val url: String) : ReportDestination
    data class ReportPage(val url: String) : ReportDestination
}

/** Builds only user-confirmed, URL-encoded reports for a source's HTTPS destination. */
object FrameReportService {
    fun destination(frame: CatalogFrame): ReportDestination? = frame.reportUrl?.let { url ->
        if (acceptsIssuePrefill(url)) ReportDestination.PrefilledIssue(url) else ReportDestination.ReportPage(url)
    }

    fun payload(frame: CatalogFrame, draft: FrameReportDraft): ReportPayload {
        require(draft.isComplete(frame)) { "回報內容尚未完成" }
        return ReportPayload(
            sourceUrl = frame.sourceUrl,
            categoryId = frame.categoryId,
            frameId = frame.effectiveId,
            imageUrl = frame.imageUrl,
            timecode = frame.timecode?.let(TimecodeSerializer::display),
            publishedCaption = frame.caption,
            problem = draft.problem,
            suggestedCaption = draft.suggestedCaption.trim().takeIf { draft.problem == ReportProblem.INCORRECT_CAPTION && it.isNotEmpty() },
            remarks = draft.remarks.trim().takeIf(String::isNotEmpty),
        )
    }

    fun prefilledIssueUrl(base: String, payload: ReportPayload): String? {
        val uri = safeHttpsUri(base) ?: return null
        if (!acceptsIssuePrefill(uri)) return null
        val beforeFragment = base.substringBefore('#')
        val separator = if (uri.rawQuery == null) '?' else '&'
        val query = "title=${encode(payload.issueTitle)}&body=${encode(payload.issueBody)}"
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "$beforeFragment$separator$query$fragment"
    }

    private fun acceptsIssuePrefill(value: String): Boolean = safeHttpsUri(value)?.let(::acceptsIssuePrefill) == true
    private fun acceptsIssuePrefill(uri: URI): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return (host == "github.com" || host.endsWith(".github.com")) && uri.path.contains("/issues/new")
    }

    private fun safeHttpsUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme.equals("https", ignoreCase = true) && it.host != null && it.userInfo == null
    }
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
