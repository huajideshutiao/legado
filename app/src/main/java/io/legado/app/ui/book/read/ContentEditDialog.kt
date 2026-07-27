package io.legado.app.ui.book.read

import android.app.Application
import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatEditText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getDisplayTitle
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.applyThemeTree
import io.legado.app.lib.theme.space
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.sendToClip
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内容编辑。正文编辑框保留 View EditText（AndroidView 包裹，照 source/edit CodeView 先例）：
 * 需要 layout.getLineForOffset 按阅读进度滚动定位，且承载整章大文本。
 */
class ContentEditDialog : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    val viewModel by viewModels<ContentEditViewModel>()

    private var title by mutableStateOf("")
    private var loading by mutableStateOf(false)
    private var contentView: AppCompatEditText? = null

    /** 初次加载的正文：Compose 组树晚于 onCreate，先暂存，factory 建好 View 后回填 */
    private var pendingContent: String? = null

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxSize()) {
            DialogTitleBar(
                title = title,
                onBack = { dismissAllowingStateLoss() },
                // 对齐原 toolbar 点击编辑章节名
                modifier = Modifier.clickable { onTitleClick() },
                actions = {
                    IconButton(onClick = {
                        save()
                        dismiss()
                    }) {
                        Icon(
                            painter = rememberPainter("ic_save"),
                            contentDescription = stringResource(R.string.action_save),
                            tint = colors.primaryText,
                        )
                    }
                    OverflowMenu { dismissMenu ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reset), color = colors.primaryText) },
                            onClick = {
                                dismissMenu()
                                viewModel.initContent(reset = true) { content ->
                                    contentView?.setText(content)
                                    ReadBook.loadContent(
                                        ReadBook.durChapterIndex,
                                        resetPageOffset = false
                                    )
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_all), color = colors.primaryText) },
                            onClick = {
                                dismissMenu()
                                requireContext()
                                    .sendToClip("$title\n${contentView?.text}")
                            },
                        )
                    }
                },
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AndroidView(
                    factory = { ctx ->
                        AppCompatEditText(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                            gravity = android.view.Gravity.TOP or android.view.Gravity.START
                            background = null
                            val pad = ctx.space.md
                            setPadding(pad, pad, pad, pad)
                            applyThemeTree()
                            contentView = this
                            pendingContent?.let {
                                pendingContent = null
                                applyContent(this, it)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (loading) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(60.dp),
                    )
                }
            }
        }
    }

    private fun onTitleClick() {
        lifecycleScope.launch {
            val book = ReadBook.book ?: return@launch
            val durChapterIndex =
                ReadBook.curTextChapter?.chapter?.index ?: ReadBook.durChapterIndex
            val chapter = withContext(IO) {
                appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)
            } ?: return@launch
            editTitle(chapter)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chapter = ReadBook.curTextChapter?.chapter
        title = chapter?.title.orEmpty()
        viewModel.loadStateLiveData.observe(this) {
            loading = it
        }
        viewModel.initContent(chapter) {
            val cv = contentView
            if (cv == null) {
                pendingContent = it
            } else {
                applyContent(cv, it)
            }
        }
    }

    /** 设入正文并按阅读进度滚动定位（对齐原 layout.getLineForOffset 逻辑） */
    private fun applyContent(cv: AppCompatEditText, content: String) {
        cv.setText(content)
        cv.post {
            cv.apply {
                val lineIndex = try {
                    layout.getLineForOffset(
                        ReadBook.durChapterPos.coerceIn(
                            0,
                            text?.length ?: 0
                        )
                    )
                } catch (e: Exception) {
                    0
                }
                val lineHeight = layout.getLineTop(lineIndex)
                scrollTo(0, lineHeight)
            }
        }
    }

    private fun editTitle(chapter: BookChapter) {
        alert {
            setTitle(R.string.edit)
            val getTitle = editTextView(text = chapter.title)
            okButton {
                chapter.title = getTitle()
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookChapterDao.update(chapter)
                    }
                    title = chapter.getDisplayTitle()
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        save()
    }

    private fun save() {
        val content = contentView?.text?.toString() ?: return
        Coroutine.async {
            val book = ReadBook.book ?: return@async
            val durChapterIndex = viewModel.chapter?.index ?: ReadBook.durChapterIndex
            val chapter = appDb.bookChapterDao
                .getChapter(book.bookUrl, durChapterIndex)
                ?: return@async
            BookHelp.saveText(book, chapter, content)
            ReadBook.loadContent(durChapterIndex, resetPageOffset = false)
        }
    }

    class ContentEditViewModel(application: Application) : BaseViewModel(application) {
        val loadStateLiveData = MutableLiveData<Boolean>()
        var content: String? = null
        var chapter: BookChapter? = null

        fun initContent(
            chapter: BookChapter? = null,
            reset: Boolean = false,
            success: (String) -> Unit
        ) {
            this.chapter = chapter ?: this.chapter
            execute {
                val book = ReadBook.book ?: return@execute null
                val durChapterIndex =
                    this@ContentEditViewModel.chapter?.index ?: ReadBook.durChapterIndex
                val chapter1 = this@ContentEditViewModel.chapter ?: appDb.bookChapterDao
                    .getChapter(book.bookUrl, durChapterIndex)
                    ?: return@execute null
                this@ContentEditViewModel.chapter = chapter1
                if (reset) {
                    content = null
                    BookHelp.delContent(book, chapter1)
                    if (!book.isLocal) ReadBook.bookSource?.let { bookSource ->
                        WebBook.getContentAwait(bookSource, book, chapter1)
                    }
                }
                return@execute content ?: let {
                    val contentProcessor = ContentProcessor.get(book.name, book.origin)
                    val content = BookHelp.getContent(book, chapter1) ?: return@let null
                    contentProcessor.getContent(book, chapter1, content, includeTitle = false)
                        .toString()
                }
            }.onStart {
                loadStateLiveData.postValue(true)
            }.onSuccess {
                content = it
                success.invoke(it ?: "")
            }.onFinally {
                loadStateLiveData.postValue(false)
            }
        }

    }

}
