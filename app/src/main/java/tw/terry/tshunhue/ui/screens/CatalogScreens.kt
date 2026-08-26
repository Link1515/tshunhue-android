package tw.terry.tshunhue.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tw.terry.tshunhue.data.model.SourceSummary
import tw.terry.tshunhue.data.model.CatalogFrame
import tw.terry.tshunhue.data.shard.FrameRef
import tw.terry.tshunhue.data.transfer.ImageTransferService
import tw.terry.tshunhue.domain.CatalogBrowser
import tw.terry.tshunhue.domain.CatalogSearchIndex
import tw.terry.tshunhue.domain.CatalogScope
import tw.terry.tshunhue.domain.CatalogStore
import tw.terry.tshunhue.domain.FrameGrouping
import tw.terry.tshunhue.ui.AppUiState
import tw.terry.tshunhue.ui.TshunhueViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    state: AppUiState,
    onOpenSource: (String) -> Unit,
    onOpenCategory: (String, String) -> Unit,
    onSettings: () -> Unit,
) {
    val sources = state.sources.filter { it.record.enabled }
    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text("Tshunhue", fontWeight = FontWeight.SemiBold) },
            actions = { IconButton(onSettings) { Icon(Icons.Outlined.Settings, "設定") } },
        )
        if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (sources.none { it.categories.any { category -> category.id !in it.record.hiddenCategoryIds } }) EmptyCatalog("尚未有可瀏覽的影像", "請在設定中加入可信任的目錄來源。")
        else LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            sources.forEach { source ->
                val categories = source.categories.filterNot { it.id in source.record.hiddenCategoryIds }
                if (categories.isEmpty()) return@forEach
                item(key = "source:${source.record.id}", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { onOpenSource(source.record.url) }, contentPadding = PaddingValues(0.dp)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                items(categories, key = { category -> "${source.record.id}:${category.id}" }) { category ->
                    val cover = state.catalog.categoryCoverUrl(source.record.id, category.id)
                        ?: state.catalog.entries.firstOrNull { it.value.sourceUrl == source.record.url && it.value.categoryId == category.id }?.value?.imageUrl
                    CategoryCard(category.name, source.name, cover) { onOpenCategory(source.record.url, category.id) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    title: String,
    scope: CatalogScope,
    state: AppUiState,
    viewModel: TshunhueViewModel,
    onDetails: () -> Unit,
    onSettings: () -> Unit,
    onReviewCaptions: (() -> Unit)? = null,
    initiallyFocused: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.isBlank()) submittedQuery = "" else {
            delay(150)
            submittedQuery = query
        }
    }
    val scopedRefs = CatalogBrowser.refs(scope, state.catalog, state.favoriteIds, state.recentIds)
    val searchIndex = remember(state.catalog) { CatalogSearchIndex(state.catalog.entries) }
    val searchResults = if (submittedQuery.isBlank()) null else searchIndex.search(submittedQuery, scopedRefs.toSet())
    val refs = searchResults?.refs ?: scopedRefs
    val sections = remember(scope, state.catalog, refs) { FrameGrouping.sections(scope, state.catalog, refs) }
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    var sectionMenuExpanded by remember { mutableStateOf(false) }
    var quickActionFrame by remember { mutableStateOf<CatalogFrame?>(null) }
    val context = LocalContext.current
    val transfer = remember(context, viewModel.imageRepository) { ImageTransferService(context, viewModel.imageRepository) }
    val sectionItemIndices = remember(sections) {
        buildMap {
            var index = 0
            sections.forEach { section ->
                put(section.id, index)
                index += section.refs.size + 1
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text(title, fontWeight = FontWeight.SemiBold) },
            actions = {
                TextButton(viewModel::toggleFrameGrouping) { Text(if (state.groupFrames) "網格" else "分組") }
                if (state.groupFrames && sections.size > 1) {
                    Box {
                        TextButton(onClick = { sectionMenuExpanded = true }) { Text("跳至") }
                        DropdownMenu(expanded = sectionMenuExpanded, onDismissRequest = { sectionMenuExpanded = false }) {
                            sections.forEach { section ->
                                DropdownMenuItem(
                                    text = { Text(section.title) },
                                    onClick = {
                                        sectionMenuExpanded = false
                                        sectionItemIndices[section.id]?.let { index -> coroutineScope.launch { gridState.animateScrollToItem(index) } }
                                    },
                                )
                            }
                        }
                    }
                }
                if (scope is CatalogScope.Recents && state.recentIds.isNotEmpty()) {
                    IconButton(viewModel::clearRecents) { Icon(Icons.Outlined.DeleteSweep, "清除最近項目") }
                }
                onReviewCaptions?.let { review -> TextButton(onClick = review) { Text("審閱") } }
                IconButton(viewModel::refresh) { Icon(Icons.Outlined.Refresh, "重新整理") }
                IconButton(onSettings) { Icon(Icons.Outlined.Settings, "設定") }
            },
        )
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text(if (initiallyFocused) "搜尋說明與標籤" else "篩選說明與標籤") },
        )
        if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (searchResults?.truncated == true) Text("僅顯示前 500 筆結果", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (refs.isEmpty()) EmptyCatalog(if (query.isBlank()) "沒有影像" else "找不到符合的影像", "調整搜尋字詞或在設定中新增來源。")
        else LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.groupFrames) {
                sections.forEach { section ->
                    item(key = section.id, span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(top = 8.dp)) {
                            Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            section.subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    items(section.refs, key = ::frameItemKey) { ref ->
                        FrameCard(ref, state.catalog, onLongClick = { quickActionFrame = it }) { frame -> viewModel.select(frame); onDetails() }
                    }
                }
            } else {
                items(refs, key = ::frameItemKey) { ref ->
                    FrameCard(ref, state.catalog, onLongClick = { quickActionFrame = it }) { frame -> viewModel.select(frame); onDetails() }
                }
            }
        }
        quickActionFrame?.let { frame ->
            AlertDialog(
                onDismissRequest = { quickActionFrame = null },
                title = { Text(frame.caption) },
                text = {
                    Column {
                        TextButton(onClick = {
                            coroutineScope.launch { transfer.copy(frame); viewModel.recordRecent(frame) }
                            quickActionFrame = null
                        }) { Text("複製影像") }
                        TextButton(onClick = {
                            coroutineScope.launch { transfer.share(frame); viewModel.recordRecent(frame) }
                            quickActionFrame = null
                        }) { Text("分享影像") }
                        TextButton(onClick = { viewModel.toggleFavorite(frame); quickActionFrame = null }) {
                            Text(if (frame.identity in state.favoriteIds) "取消收藏" else "加入收藏")
                        }
                        if (scope is CatalogScope.Recents) {
                            TextButton(onClick = { viewModel.removeRecent(frame.identity); quickActionFrame = null }) { Text("從最近使用移除") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { quickActionFrame = null }) { Text("關閉") } },
            )
        }
    }
}

@Composable
private fun CategoryCard(name: String, source: String, coverUrl: String?, onClick: () -> Unit) = Column(
    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
) {
    if (coverUrl != null) FrameImage(coverUrl, Modifier.fillMaxWidth().height(112.dp))
    else androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(112.dp).clip(MaterialTheme.shapes.medium))
    Text(name, Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
    Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FrameCard(
    ref: FrameRef,
    catalog: CatalogStore,
    onLongClick: (CatalogFrame) -> Unit,
    onClick: (CatalogFrame) -> Unit,
) {
    val frame = catalog.frame(ref) ?: return
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).combinedClickable(
            onClick = { onClick(frame) },
            onLongClick = { onLongClick(frame) },
        ),
    ) {
        FrameImage(frame.imageUrl, Modifier.fillMaxWidth().height(130.dp))
        Text(frame.caption, Modifier.padding(top = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(frame.categoryLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Lazy layouts persist item keys in Android state, so the key must be Bundle-compatible. */
private fun frameItemKey(ref: FrameRef): String = "${ref.sourceId}:${ref.categoryId}:${ref.ordinal}"

@Composable
private fun EmptyCatalog(title: String, body: String) = Column(
    Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
) { Text(title, style = MaterialTheme.typography.headlineSmall); Text(body, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
