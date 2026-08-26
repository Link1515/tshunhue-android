package tw.terry.tshunhue.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionReviewPolicyTest {
    @Test
    fun `correction must be nonblank and differ from published caption`() {
        assertFalse(CaptionReviewPolicy.canSubmitCorrection("Published caption", "  "))
        assertFalse(CaptionReviewPolicy.canSubmitCorrection("Published caption", " Published caption "))
        assertTrue(CaptionReviewPolicy.canSubmitCorrection("Published caption", "Corrected caption"))
    }
}
