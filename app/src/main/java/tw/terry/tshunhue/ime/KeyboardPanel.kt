package tw.terry.tshunhue.ime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tw.terry.tshunhue.data.model.CatalogFrame
import tw.terry.tshunhue.ui.screens.FrameImage
import tw.terry.tshunhue.ui.screens.ZoomableFrameImage

/** Compact, task-oriented IME surface with no independent text retention. */
@Composable
fun KeyboardPanel(
    controller: KeyboardController,
    onInsertCaption: (CatalogFrame) -> Unit,
    onCommitImage: (CatalogFrame) -> Unit,
    onInsertSpace: () -> Unit,
    onDeleteBackward: () -> Unit,
    onChooseInputMethod: () -> Unit,
) {
    val state by controller.state.collectAsState()
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyboardHeader(state, controller)
            HorizontalDivider()
            KeyboardResults(state, onInsertCaption, onCommitImage)
            KeyboardControls(onInsertSpace, onDeleteBackward, onChooseInputMethod)
        }
    }
}

@Composable
private fun KeyboardHeader(state: KeyboardUiState, controller: KeyboardController) {
    var categoriesOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            state.query.ifBlank { "選取或輸入文字後搜尋" },
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.query.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Box {
            TextButton(onClick = { categoriesOpen = true }, enabled = state.categories.isNotEmpty()) {
                val label = state.categories.firstOrNull { it.key == state.selectedCategory }?.label ?: "全部"
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = categoriesOpen, onDismissRequest = { categoriesOpen = false }) {
                DropdownMenuItem(text = { Text("全部分類") }, onClick = { controller.selectCategory(null); categoriesOpen = false })
                state.categories.forEach { category ->
                    DropdownMenuItem(text = { Text(category.label) }, onClick = { controller.selectCategory(category.key); categoriesOpen = false })
                }
            }
        }
    }
}

@Composable
private fun KeyboardResults(
    state: KeyboardUiState,
    onInsertCaption: (CatalogFrame) -> Unit,
    onCommitImage: (CatalogFrame) -> Unit,
) {
    var previewFrame by remember { mutableStateOf<CatalogFrame?>(null) }
    when {
        state.isLoading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.error != null -> KeyboardStatus(state.error)
        state.results.isEmpty() -> KeyboardStatus(if (state.query.isBlank()) "沒有最近使用的項目" else "找不到符合的影像")
        else -> LazyRow(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.results, key = CatalogFrame::identity) { frame ->
                val feedback = state.feedback?.takeIf { it.identity == frame.identity }?.message
                if (state.supportsImages) KeyboardImageResult(frame, feedback, onCommitImage, onPreview = { previewFrame = frame })
                else KeyboardTextResult(frame, feedback, onInsertCaption)
            }
        }
    }
    previewFrame?.let { frame -> ZoomableFrameImage(frame.imageUrl) { previewFrame = null } }
}

@Composable
private fun KeyboardTextResult(frame: CatalogFrame, feedback: String?, onInsertCaption: (CatalogFrame) -> Unit) {
    Surface(
        modifier = Modifier.width(180.dp).fillMaxSize().clickable { onInsertCaption(frame) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(frame.caption, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(frame.categoryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            feedback?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun KeyboardImageResult(
    frame: CatalogFrame,
    feedback: String?,
    onCommitImage: (CatalogFrame) -> Unit,
    onPreview: () -> Unit,
) {
    Column(
        Modifier.width(140.dp).fillMaxSize().combinedClickable(onClick = { onCommitImage(frame) }, onLongClick = onPreview),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FrameImage(frame.imageUrl, Modifier.fillMaxWidth().height(92.dp), ContentScale.Crop, maxPixelSize = 320)
        Text(frame.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        feedback?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun KeyboardStatus(message: String) = Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun KeyboardControls(onInsertSpace: () -> Unit, onDeleteBackward: () -> Unit, onChooseInputMethod: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onChooseInputMethod) { Text("切換") }
        TextButton(onClick = onInsertSpace, modifier = Modifier.weight(1f)) { Text("空白") }
        TextButton(onClick = onDeleteBackward) { Text("⌫") }
    }
}
