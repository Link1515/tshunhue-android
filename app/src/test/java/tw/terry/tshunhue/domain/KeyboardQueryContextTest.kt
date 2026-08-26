package tw.terry.tshunhue.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardQueryContextTest {
    @Test fun `selected text takes precedence over surrounding line`() {
        assertEquals("selected", KeyboardQueryContext.query("  selected  ", "before", "after"))
    }

    @Test fun `query is limited to current line around cursor`() {
        assertEquals("current line", KeyboardQueryContext.query(null, "old\ncurrent ", "line\nnext"))
    }

    @Test fun `blank context remains blank`() {
        assertEquals("", KeyboardQueryContext.query(null, "\n", "\n"))
    }
}
