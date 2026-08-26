package tw.terry.tshunhue.ime

import android.content.ClipDescription
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tw.terry.tshunhue.data.image.ImageRepository
import tw.terry.tshunhue.data.remote.HttpCatalogClient
import tw.terry.tshunhue.data.transfer.ImageTransferService
import tw.terry.tshunhue.data.validation.CatalogValidator
import tw.terry.tshunhue.domain.KeyboardQueryContext
import tw.terry.tshunhue.ui.LocalImageRepository
import tw.terry.tshunhue.ui.theme.TshunhueTheme

/** Android system IME entry point. It reads only the active selection/current line into memory. */
class TshunhueInputMethodService : InputMethodService() {
    private lateinit var controller: KeyboardController
    private lateinit var images: ImageRepository
    private lateinit var transfer: ImageTransferService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val validator = CatalogValidator()
        controller = KeyboardController(applicationContext)
        images = ImageRepository(applicationContext, HttpCatalogClient(validator), kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false })
        transfer = ImageTransferService(applicationContext, images)
    }

    override fun onCreateInputView(): View = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            TshunhueTheme {
                CompositionLocalProvider(LocalImageRepository provides images) {
                    KeyboardPanel(
                        controller = controller,
                        onInsertCaption = ::insertCaption,
                        onCommitImage = ::commitImage,
                        onInsertSpace = { commitText(" ") },
                        onDeleteBackward = { currentInputConnection?.deleteSurroundingText(1, 0); refreshQuery() },
                        onChooseInputMethod = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
                    )
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        controller.activate(currentQuery(), supportsImages(info))
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshQuery()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        controller.deactivate()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }

    private fun insertCaption(frame: tw.terry.tshunhue.data.model.CatalogFrame) = commitText(frame.caption)

    private fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
        refreshQuery()
    }

    private fun commitImage(frame: tw.terry.tshunhue.data.model.CatalogFrame) {
        val targetPackage = currentInputEditorInfo?.packageName ?: return
        scope.launch {
            val uri = runCatching { transfer.export(frame) }.getOrElse {
                toast("無法準備影像")
                return@launch
            }
            if (currentInputEditorInfo?.packageName != targetPackage) return@launch
            val content = InputContentInfo(
                uri,
                ClipDescription(frame.caption, arrayOf("image/jpeg")),
                Uri.parse(frame.imageUrl),
            )
            currentInputConnection?.finishComposingText()
            val accepted = currentInputConnection?.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) == true
            if (accepted) controller.recordCommittedImage(frame) else toast("目前的 app 不支援插入圖片")
        }
    }

    private fun refreshQuery() = controller.updateQuery(currentQuery())
    private fun currentQuery(): String = KeyboardQueryContext.query(
        currentInputConnection?.getSelectedText(0),
        currentInputConnection?.getTextBeforeCursor(CONTEXT_CHARS, 0),
        currentInputConnection?.getTextAfterCursor(CONTEXT_CHARS, 0),
    )
    private fun supportsImages(info: EditorInfo): Boolean = info.contentMimeTypes.orEmpty().any { ClipDescription.compareMimeTypes("image/jpeg", it) }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object { const val CONTEXT_CHARS = 2_048 }
}
