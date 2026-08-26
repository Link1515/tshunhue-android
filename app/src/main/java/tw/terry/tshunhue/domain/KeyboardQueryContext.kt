package tw.terry.tshunhue.domain

/** Extracts a local search phrase from a host editor without retaining typed text. */
object KeyboardQueryContext {
    fun query(selectedText: CharSequence?, beforeCursor: CharSequence?, afterCursor: CharSequence?): String {
        val selected = selectedText?.toString()?.trim()
        if (!selected.isNullOrEmpty()) return selected
        val beforeLine = beforeCursor?.toString()?.substringAfterLast('\n').orEmpty()
        val afterLine = afterCursor?.toString()?.substringBefore('\n').orEmpty()
        return (beforeLine + afterLine).trim()
    }
}
