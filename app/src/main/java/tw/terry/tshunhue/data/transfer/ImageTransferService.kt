package tw.terry.tshunhue.data.transfer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.FileProvider
import tw.terry.tshunhue.data.image.ImageRepository
import tw.terry.tshunhue.data.model.CatalogFrame
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Converts a catalog image to a stable JPEG file before Android clipboard or share hand-off. */
class ImageTransferService(
    private val context: Context,
    private val images: ImageRepository,
) {
    suspend fun copy(frame: CatalogFrame) {
        val uri = export(frame)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, frame.caption, uri))
    }

    suspend fun share(frame: CatalogFrame) {
        val uri = export(frame)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "分享影格"))
    }

    suspend fun export(frame: CatalogFrame) = withContext(Dispatchers.IO) {
        val asset = images.asset(frame.imageUrl)
        val output = File(transferDirectory(), "${UUID.randomUUID()}-${sanitize(frame.caption)}.jpg")
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { stream ->
            val bitmap = flattenAndScale(asset.bitmap)
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)) { "無法建立 JPEG" }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
    }

    fun clearExports() {
        transferDirectory().deleteRecursively()
    }

    private fun transferDirectory() = File(context.cacheDir, "tshunhue/transfers")

    private fun flattenAndScale(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        val scaled = if (largest > 1_920) {
            val ratio = 1_920f / largest
            Bitmap.createScaledBitmap(source, (source.width * ratio).toInt(), (source.height * ratio).toInt(), true)
        } else source
        val opaque = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
        return opaque
    }

    private fun sanitize(caption: String): String = caption
        .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]+"), " ")
        .trim().replace(Regex("\\s+"), " ").take(80).ifBlank { "Tshunhue" }
}
