package tw.terry.tshunhue.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import tw.terry.tshunhue.ui.LocalImageRepository

@Composable
internal fun FrameImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxPixelSize: Int = 640,
) {
    val repository = LocalImageRepository.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, url, maxPixelSize) {
        value = repository.image(url, maxPixelSize)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap == null) CircularProgressIndicator()
        else Image(bitmap = bitmap!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
    }
}
