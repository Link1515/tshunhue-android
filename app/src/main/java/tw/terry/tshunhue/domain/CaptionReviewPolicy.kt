package tw.terry.tshunhue.domain

/** Keeps caption-review corrections meaningful before they are persisted or reported. */
object CaptionReviewPolicy {
    fun canSubmitCorrection(publishedCaption: String, correction: String): Boolean =
        correction.trim().isNotEmpty() && correction.trim() != publishedCaption.trim()
}
