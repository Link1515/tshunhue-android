package tw.terry.tshunhue.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tw.terry.tshunhue.data.model.CatalogFrame
import tw.terry.tshunhue.domain.FrameReportDraft
import tw.terry.tshunhue.domain.FrameReportService
import tw.terry.tshunhue.domain.ReportDestination
import tw.terry.tshunhue.domain.ReportProblem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportFrameScreen(frame: CatalogFrame?, initialDraft: FrameReportDraft? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    if (frame == null || FrameReportService.destination(frame) !is ReportDestination.PrefilledIssue) {
        Column(Modifier.fillMaxSize()) {
            CenterAlignedTopAppBar(title = { Text("回報問題") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } })
            Text("這個影像沒有可填寫的回報目的地。", Modifier.padding(24.dp))
        }
        return
    }
    val destination = FrameReportService.destination(frame) as ReportDestination.PrefilledIssue
    var draft by remember(frame.identity, initialDraft) { mutableStateOf(initialDraft ?: FrameReportDraft(suggestedCaption = frame.caption)) }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            CenterAlignedTopAppBar(
                title = { Text("回報問題") },
                navigationIcon = { TextButton(onClick = onBack) { Text("取消") } },
                actions = {
                    TextButton(
                        enabled = draft.isComplete(frame),
                        onClick = {
                            val url = FrameReportService.prefilledIssueUrl(destination.url, FrameReportService.payload(frame, draft))
                            if (url != null && openExternal(context, url)) onBack() else error = "無法開啟回報頁面"
                        },
                    ) { Text("繼續") }
                },
            )
            FrameImage(frame.imageUrl, Modifier.fillMaxWidth().height(220.dp), ContentScale.Fit, maxPixelSize = 1_024)
        }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(frame.caption, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text("問題類型", style = MaterialTheme.typography.titleSmall)
                ReportProblem.entries.forEach { problem ->
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = draft.problem == problem, onClick = { draft = draft.copy(problem = problem) })
                        Text(problem.label, Modifier.padding(start = 8.dp))
                    }
                }
                if (draft.problem == ReportProblem.INCORRECT_CAPTION) {
                    OutlinedTextField(
                        value = draft.suggestedCaption,
                        onValueChange = { draft = draft.copy(suggestedCaption = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("建議的說明文字") },
                        minLines = 1,
                        maxLines = 3,
                    )
                }
                OutlinedTextField(
                    value = draft.remarks,
                    onValueChange = { draft = draft.copy(remarks = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("備註（選填）") },
                    minLines = 3,
                    maxLines = 6,
                )
                Text("繼續後會開啟來源的 GitHub issue 頁面，並以本頁內容預填報告；送出前仍可在 GitHub 修改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun openExternal(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    if (intent.resolveActivity(context.packageManager) == null) return false
    context.startActivity(intent)
    return true
}
