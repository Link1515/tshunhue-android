package tw.terry.tshunhue.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import tw.terry.tshunhue.data.model.CatalogFrame
import tw.terry.tshunhue.data.review.CaptionReviewAnswer
import tw.terry.tshunhue.data.review.CaptionReviewStore
import tw.terry.tshunhue.data.review.CaptionVerdict
import tw.terry.tshunhue.domain.CaptionReviewPolicy
import tw.terry.tshunhue.domain.CatalogBrowser
import tw.terry.tshunhue.domain.CatalogScope
import tw.terry.tshunhue.domain.CatalogStore
import tw.terry.tshunhue.domain.ReportDestination
import tw.terry.tshunhue.domain.FrameReportService

/** Debug-only counterpart to iOS Capibara: review captions locally, one frame at a time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionReviewScreen(
    catalog: CatalogStore,
    sourceUrl: String,
    categoryId: String,
    onBack: () -> Unit,
    onReport: (CatalogFrame, String) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { CaptionReviewStore(context, Json { ignoreUnknownKeys = true; explicitNulls = false }) }
    val refs = remember(catalog, sourceUrl, categoryId) {
        CatalogBrowser.refs(CatalogScope.Category(sourceUrl, categoryId), catalog, emptySet(), emptyList())
    }
    var answers by remember(sourceUrl, categoryId) { mutableStateOf(store.answers(sourceUrl, categoryId).associateBy(CaptionReviewAnswer::identity)) }
    var undoable by remember(sourceUrl, categoryId) { mutableStateOf<CaptionReviewAnswer?>(null) }
    var correctionOpen by remember { mutableStateOf(false) }
    var correction by remember { mutableStateOf("") }
    val frame = refs.asSequence().mapNotNull(catalog::frame).firstOrNull { it.identity !in answers }

    fun record(frameToAnswer: CatalogFrame, verdict: CaptionVerdict, suggestedCaption: String? = null) {
        val answer = CaptionReviewAnswer(
            sourceUrl = sourceUrl,
            categoryId = categoryId,
            identity = frameToAnswer.identity,
            verdict = verdict,
            correction = suggestedCaption?.trim()?.takeIf(String::isNotEmpty),
            answeredAtEpochMillis = System.currentTimeMillis(),
        )
        store.record(answer)
        answers = answers + (answer.identity to answer)
        undoable = answer
        correctionOpen = false
        correction = ""
    }

    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text("字幕審閱") },
            navigationIcon = { TextButton(onClick = onBack) { Text("完成") } },
            actions = {
                if (undoable != null) {
                    TextButton(onClick = {
                        undoable?.let { answer ->
                            store.remove(answer.identity)
                            answers = answers - answer.identity
                        }
                        undoable = null
                    }) { Text("復原") }
                }
            },
        )
        val total = refs.size.coerceAtLeast(1)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("${answers.size.coerceAtMost(refs.size)} / ${refs.size} 已審閱", style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(progress = { answers.size.coerceAtMost(refs.size).toFloat() / total }, modifier = Modifier.fillMaxWidth())
        }
        if (frame == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(if (refs.isEmpty()) "沒有可審閱的影像" else "此分類已審閱完成", style = MaterialTheme.typography.titleLarge)
                if (refs.isNotEmpty()) TextButton(onClick = { store.clear(sourceUrl, categoryId); answers = emptyMap(); undoable = null }) { Text("重新審閱") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    FrameImage(frame.imageUrl, Modifier.fillMaxWidth().height(300.dp), ContentScale.Fit, maxPixelSize = 1_024)
                }
                item {
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(frame.caption, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(frame.categoryLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (correctionOpen) {
                            OutlinedTextField(
                                value = correction,
                                onValueChange = { correction = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("正確的說明文字") },
                                minLines = 1,
                                maxLines = 3,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { record(frame, CaptionVerdict.INCORRECT) }) { Text("略過") }
                                TextButton(
                                    enabled = CaptionReviewPolicy.canSubmitCorrection(frame.caption, correction),
                                    onClick = { record(frame, CaptionVerdict.INCORRECT, correction) },
                                ) { Text("儲存更正") }
                                if (FrameReportService.destination(frame) is ReportDestination.PrefilledIssue) {
                                    TextButton(
                                        enabled = CaptionReviewPolicy.canSubmitCorrection(frame.caption, correction),
                                        onClick = {
                                            val suggestedCaption = correction.trim()
                                            record(frame, CaptionVerdict.INCORRECT, suggestedCaption)
                                            onReport(frame, suggestedCaption)
                                        },
                                    ) { Text("填寫回報") }
                                }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { correctionOpen = true; correction = frame.caption }, modifier = Modifier.weight(1f)) { Text("說明有誤") }
                                TextButton(onClick = { record(frame, CaptionVerdict.CORRECT) }, modifier = Modifier.weight(1f)) { Text("正確") }
                            }
                        }
                    }
                }
            }
        }
    }
}
