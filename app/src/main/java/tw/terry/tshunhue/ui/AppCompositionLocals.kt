package tw.terry.tshunhue.ui

import androidx.compose.runtime.staticCompositionLocalOf
import tw.terry.tshunhue.data.image.ImageRepository

val LocalImageRepository = staticCompositionLocalOf<ImageRepository> {
    error("ImageRepository has not been provided")
}
