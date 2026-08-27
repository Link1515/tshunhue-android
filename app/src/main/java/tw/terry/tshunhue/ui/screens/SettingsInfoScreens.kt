package tw.terry.tshunhue.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tw.terry.tshunhue.BuildConfig
import tw.terry.tshunhue.ui.AppUiState
import tw.terry.tshunhue.ui.TshunhueViewModel

@Composable
fun StorageSettingsSection(state: AppUiState, viewModel: TshunhueViewModel) {
    var pendingClear by remember { mutableStateOf<StorageAction?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.refreshImageCacheSize() }
    Column {
        Text("儲存空間", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ListItem(
            headlineContent = { Text("影像快取") },
            supportingContent = { Text(Formatter.formatFileSize(context, state.imageCacheBytes)) },
            leadingContent = { Icon(Icons.Outlined.Storage, null) },
            trailingContent = { TextButton(onClick = { pendingClear = StorageAction.IMAGE_CACHE }, enabled = state.imageCacheBytes > 0) { Text("清除") } },
        )
        ListItem(
            headlineContent = { Text("最近使用") },
            supportingContent = { Text("${state.recentIds.size} 個項目") },
            leadingContent = { Icon(Icons.Outlined.DeleteSweep, null) },
            trailingContent = { TextButton(onClick = { pendingClear = StorageAction.RECENTS }, enabled = state.recentIds.isNotEmpty()) { Text("清除") } },
        )
    }
    pendingClear?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text(if (action == StorageAction.IMAGE_CACHE) "清除影像快取？" else "清除最近使用？") },
            text = { Text(if (action == StorageAction.IMAGE_CACHE) "已下載的影像會被移除，需要時會重新下載。" else "這不會影響你的收藏項目。") },
            confirmButton = { TextButton(onClick = { if (action == StorageAction.IMAGE_CACHE) viewModel.clearImageCache() else viewModel.clearRecents(); pendingClear = null }) { Text("清除") } },
            dismissButton = { TextButton(onClick = { pendingClear = null }) { Text("取消") } },
        )
    }
}

@Composable
fun AboutSettingsSection(onPrivacy: () -> Unit, onAbout: () -> Unit) {
    Column {
        Text("資訊與隱私", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        ListItem(
            headlineContent = { Text("隱私政策") },
            supportingContent = { Text("本機資料與網路連線說明") },
            leadingContent = { Icon(Icons.Outlined.PrivacyTip, null) },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onPrivacy),
        )
        ListItem(
            headlineContent = { Text("關於 Tshunhue") },
            supportingContent = { Text("版本、專案與授權資訊") },
            leadingContent = { Icon(Icons.Outlined.Info, null) },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onAbout),
        )
    }
}

@Composable
fun KeyboardSettingsSection() {
    val context = LocalContext.current
    Column {
        Text("鍵盤", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        ListItem(
            headlineContent = { Text("Tshunhue Keyboard") },
            supportingContent = { Text("啟用後可用目前選取文字搜尋本機 catalog") },
            trailingContent = { TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text("開啟設定") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(title = { Text("隱私政策") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } })
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { PrivacyItem("不追蹤使用行為", "Tshunhue 不收集分析資料、廣告識別碼、遙測資料、聯絡人或你輸入的文字。") }
            item { HorizontalDivider() }
            item { PrivacyItem("資料保留在裝置上", "來源設定、catalog、影像快取、收藏與最近使用項目都儲存在這台裝置。") }
            item { HorizontalDivider() }
            item { PrivacyItem("直接連線至來源", "catalog 與影像直接向你設定的來源 URL 下載；這些主機可能收到一般網路資訊，例如 IP 位址與 User-Agent。") }
            item { HorizontalDivider() }
            item { PrivacyItem("Tshunhue Keyboard", "鍵盤只會用目前選取文字或輸入行搜尋本機 catalog；查詢不會儲存或傳送給 Tshunhue。你點選影像時，才可能向來源下載影像並請目標 app 插入它。") }
            item {
                TextButton(onClick = { openExternalWithFeedback(context, PRIVACY_URL) }) {
                    Text("閱讀完整政策")
                    Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(title = { Text("關於 Tshunhue") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } })
        LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tshunhue", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("用於瀏覽社群維護的反應影像 catalog。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { HorizontalDivider() }
            item { Text("本 Android 版本遵循原始 Tshunhue 專案的資料架構與 GPL-3.0 授權條款。", style = MaterialTheme.typography.bodyLarge) }
            item {
                TextButton(onClick = { openExternalWithFeedback(context, PROJECT_URL) }) {
                    Text("開啟原始專案")
                    Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.padding(start = 6.dp))
                }
            }
            item {
                TextButton(onClick = { openExternalWithFeedback(context, LICENSE_URL) }) {
                    Text("閱讀 GPL-3.0 授權")
                    Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun PrivacyItem(title: String, body: String) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun openExternalWithFeedback(context: Context, url: String) {
    if (!openExternalUrl(context, url)) {
        Toast.makeText(context, "找不到可開啟此連結的應用程式", Toast.LENGTH_SHORT).show()
    }
}

private enum class StorageAction { IMAGE_CACHE, RECENTS }
private const val PROJECT_URL = "https://github.com/Link1515/tshunhue-android"
private const val PRIVACY_URL = "$PROJECT_URL/blob/main/PRIVACY.md"
private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
