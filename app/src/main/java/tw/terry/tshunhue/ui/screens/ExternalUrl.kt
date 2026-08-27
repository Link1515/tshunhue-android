package tw.terry.tshunhue.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens an HTTP(S) URL with an app chosen by Android. */
internal fun openExternalUrl(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    val isHttpUrl = uri.scheme.equals("http", ignoreCase = true) ||
        uri.scheme.equals("https", ignoreCase = true)
    if (!isHttpUrl || uri.host.isNullOrBlank()) return false

    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
