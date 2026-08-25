package tw.terry.tshunhue.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import tw.terry.tshunhue.data.validation.CatalogLimits
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun FrameImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, url) { value = loadBitmap(url) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap == null) CircularProgressIndicator(modifier = Modifier)
        else Image(bitmap = bitmap!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
    }
}

private suspend fun loadBitmap(url: String) = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= CatalogLimits.IMAGE_BYTES) { "影像檔案過大" }
                output.write(buffer, 0, count)
            }
            val data = output.toByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "無效的影像" }
            require(bounds.outWidth.toLong() * bounds.outHeight <= 3_840L * 2_160L) { "影像解析度過大" }
            var sample = 1
            while (bounds.outWidth / sample > 1_920 || bounds.outHeight / sample > 1_920) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }.getOrNull()
}
