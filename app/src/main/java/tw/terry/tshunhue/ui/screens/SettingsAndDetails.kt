package tw.terry.tshunhue.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tw.terry.tshunhue.data.model.CatalogFrame
import tw.terry.tshunhue.data.model.Provider
import tw.terry.tshunhue.data.model.RefreshFrequency
import tw.terry.tshunhue.ui.AppUiState
import tw.terry.tshunhue.ui.TshunhueViewModel
import tw.terry.tshunhue.data.model.TimecodeSerializer
import tw.terry.tshunhue.data.transfer.ImageTransferService
import tw.terry.tshunhue.domain.FrameReportService
import tw.terry.tshunhue.domain.ReportDestination
import tw.terry.tshunhue.ui.LocalImageRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: AppUiState,
    viewModel: TshunhueViewModel,
    onBack: () -> Unit,
    onPrivacy: () -> Unit,
    onAbout: () -> Unit,
) {
    var showAddSource by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(title = { Text("設定") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } })
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("來源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (state.sources.isEmpty()) item { Text("尚未加入來源。來源必須是可信任的 HTTPS catalog index。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(state.sources, key = { _, source -> source.record.id }) { index, source ->
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(source.name, fontWeight = FontWeight.Medium)
                            Text(source.record.url, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            source.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                        }
                        Switch(source.record.enabled, { viewModel.setSourceEnabled(source.record.id, it) })
                        Column {
                            IconButton(onClick = { viewModel.moveSource(source.record.id, -1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移來源") }
                            IconButton(onClick = { viewModel.moveSource(source.record.id, 1) }, enabled = index < state.sources.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "下移來源") }
                        }
                        IconButton({ pendingDelete = source.record.id }) { Icon(Icons.Outlined.Delete, "移除來源") }
                    }
                    if (source.record.enabled && source.categories.isNotEmpty()) {
                        source.categories.forEach { category ->
                            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(category.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(category.id !in source.record.hiddenCategoryIds, { visible -> viewModel.setCategoryHidden(source.record.id, category.id, !visible) })
                            }
                        }
                    }
                    Divider(Modifier.padding(top = 8.dp))
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("自動同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RefreshFrequency.entries.forEach { frequency ->
                            AssistChip(
                                onClick = { viewModel.setRefreshFrequency(frequency) },
                                label = { Text(frequency.label) },
                                leadingIcon = if (state.refreshFrequency == frequency) ({ Text("✓") }) else null,
                            )
                        }
                    }
                    Text("手動模式只會在你點選重新整理時下載；加入新來源仍會立即驗證。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { StorageSettingsSection(state, viewModel) }
            item { KeyboardSettingsSection() }
            item { AboutSettingsSection(onPrivacy, onAbout) }
            item { Text("同步時只接受 HTTPS URL，並對下載大小、重新導向與目錄欄位進行驗證。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        ExtendedFloatingActionButton(onClick = { showAddSource = true }, modifier = Modifier.align(Alignment.End).padding(16.dp), text = { Text("加入來源") }, icon = { Text("+") })
    }
    if (showAddSource) AddSourceDialog(onDismiss = { showAddSource = false }, onAdd = { viewModel.addSource(it); showAddSource = false })
    pendingDelete?.let { sourceId ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("移除來源？") }, text = { Text("這會移除來源設定；收藏與最近項目將保留到對應影格再次出現。") },
            confirmButton = { TextButton({ viewModel.removeSource(sourceId); pendingDelete = null }) { Text("移除") } }, dismissButton = { TextButton({ pendingDelete = null }) { Text("取消") } })
    }
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("加入來源") }, text = {
        Column { OutlinedTextField(value, { value = it }, label = { Text("HTTPS index URL") }, singleLine = true); Text("只加入你信任的來源；瀏覽時會下載其影像。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
    }, confirmButton = { TextButton({ onAdd(value) }, enabled = value.isNotBlank()) { Text("加入") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameDetailsScreen(
    frame: CatalogFrame?,
    favoriteIds: Set<String>,
    onBack: () -> Unit,
    onFavorite: (CatalogFrame) -> Unit,
    onTransfer: (CatalogFrame) -> Unit,
    onReportForm: (CatalogFrame) -> Unit,
) {
    val context = LocalContext.current
    val images = LocalImageRepository.current
    val transfer = remember(context, images) { ImageTransferService(context, images) }
    val coroutineScope = rememberCoroutineScope()
    var zoomedImage by remember { mutableStateOf<String?>(null) }
    if (frame == null) {
        Column(Modifier.fillMaxSize()) { CenterAlignedTopAppBar(title = { Text("影格") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } }); Text("找不到已選取的影格。", Modifier.padding(24.dp)) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            CenterAlignedTopAppBar(title = { Text("詳細資料") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } }, actions = {
                IconButton({ onFavorite(frame) }) { Icon(if (frame.identity in favoriteIds) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, "收藏") }
                IconButton({ coroutineScope.launch { transfer.copy(frame); onTransfer(frame) } }) { Icon(Icons.Outlined.ContentCopy, "複製影像") }
                IconButton({ coroutineScope.launch { transfer.share(frame); onTransfer(frame) } }) { Icon(Icons.Outlined.Share, "分享影像") }
                FrameReportService.destination(frame)?.let { destination ->
                    IconButton(onClick = {
                        when (destination) {
                            is ReportDestination.PrefilledIssue -> onReportForm(frame)
                            is ReportDestination.ReportPage -> open(context, destination.url)
                        }
                    }) { Icon(Icons.Outlined.OpenInNew, "回報問題") }
                }
            })
            FrameImage(
                frame.imageUrl,
                Modifier.fillMaxWidth().height(280.dp).clickable { zoomedImage = frame.imageUrl },
                ContentScale.Fit,
                maxPixelSize = 1_920,
            )
        }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(frame.categoryLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(frame.caption, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                frame.subsection?.let { Text(it.name, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                frame.timecode?.let { Metadata("時間碼", TimecodeSerializer.display(it)) }
                Metadata("ID", frame.effectiveId)
                if (frame.tags.isNotEmpty()) { Text("標籤", fontWeight = FontWeight.Medium); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { frame.tags.take(8).forEach { AssistChip({}, { Text(it) }) } } }
                if (frame.providers.isNotEmpty()) { Text("播放來源", fontWeight = FontWeight.Medium); frame.providers.forEach { provider -> TextButton({ open(context, destination(provider, frame.timecode)) }) { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(provider.name) } } }
                frame.attribution?.let { Text("出處：${it.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    zoomedImage?.let { ZoomableFrameImage(it) { zoomedImage = null } }
}

@Composable private fun Metadata(label: String, value: String) = Row { Text("$label　", fontWeight = FontWeight.Medium); Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant) }
private fun destination(provider: Provider, timecode: Long?): String = provider.url.replace("{seconds}", ((timecode ?: 0) / 1_000).toString()).replace("{milliseconds}", (timecode ?: 0).toString())
private fun open(context: Context, url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

private val RefreshFrequency.label: String
    get() = when (this) {
        RefreshFrequency.MANUAL -> "手動"
        RefreshFrequency.DAILY -> "每日"
        RefreshFrequency.WEEKLY -> "每週"
        RefreshFrequency.MONTHLY -> "每月"
    }
