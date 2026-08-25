package tw.terry.tshunhue.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TshunhueColors = lightColorScheme(
    primary = Color(0xFF4E5CB7), onPrimary = Color.White, primaryContainer = Color(0xFFE2E5FF),
    secondary = Color(0xFF745A2F), secondaryContainer = Color(0xFFFFE7BB), background = Color(0xFFFFFBF7),
    surface = Color(0xFFFFFBF7), surfaceVariant = Color(0xFFF0EEE9),
)

@Composable fun TshunhueTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = TshunhueColors, content = content)
