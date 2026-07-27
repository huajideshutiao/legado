package io.legado.app.ui.book.audio

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.IntentData
import io.legado.app.help.book.delete
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.migrateTo
import io.legado.app.help.book.removeType
import io.legado.app.help.book.save
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AudioPlay
import io.legado.app.model.AudioPlayShared
import io.legado.app.service.AudioPlayService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.login.showLoginDialog
import io.legado.app.ui.widget.dialog.showBookVariableDialog
import io.legado.app.ui.widget.dialog.showSourceVariableDialog
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.utils.FlowBus
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.getRepresentativeColor
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toDurationTime
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 音频播放
 */
class AudioPlayActivity : BaseComposeActivity(toolBarTheme = Theme.Dark),
    ChangeBookSourceDialog.CallBack {

    val viewModel by viewModels<AudioPlayViewModel>()

    // ---- 事件桥回显状态(observeLiveBus 写入, AudioPlayScreen 读取) ----
    var titleText by mutableStateOf("")
    var subTitle by mutableStateOf("")
    var isPlaying by mutableStateOf(false)
    var durationMs by mutableIntStateOf(0)
    var progressMs by mutableIntStateOf(0)
    var bufferMs by mutableIntStateOf(0)
    var speedText by mutableStateOf<String?>(null)
    var timerMinute by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var lrcData by mutableStateOf<List<Pair<Int, String>>?>(null)
    var lrcProgress by mutableIntStateOf(-1)

    /** 模糊背景代表色推导出的 主色 to 次色，驱动歌词与进度条着色 */
    var lrcColors by mutableStateOf<Pair<Int, Int>?>(null)
    var coverUrl by mutableStateOf<String?>(null)
    var coverVisible by mutableStateOf(true)
    var playMode by mutableStateOf(AudioPlayShared.PlayMode.LIST_END_STOP)
    var prevEnabled by mutableStateOf(true)
    var nextEnabled by mutableStateOf(true)

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            val targetIndex = it.first
            val targetPos = it.second
            when {
                targetIndex != AudioPlay.durChapterIndex -> viewModel.skipTo(targetIndex, targetPos)
                targetPos > 0 && targetPos != AudioPlay.durChapterPos ->
                    viewModel.adjustProgress(targetPos)

                targetPos == 0 -> viewModel.skipTo(targetIndex)
            }
        }
    }
    private val sourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource()
            }
        }

    @Composable
    override fun Content() {
        AudioPlayScreen(this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.titleData.observe(this) {
            titleText = it
        }
        viewModel.initData(intent) { applyBookmarkPosition(intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 切书场景下 Activity 已在栈中 (singleTask) 会走 onNewIntent,
        // 必须调用 initData 才能加载新书数据, 否则首次切书 UI 仍显示旧书状态,
        // 用户需退出后从详情页二次进入才能正常 (对齐 ReadBookActivity.onNewIntent)
        viewModel.initData(intent) { applyBookmarkPosition(intent) }
    }

    private fun applyBookmarkPosition(intent: Intent) {
        val targetIndex = intent.getIntExtra("chapterIndex", -1)
        if (targetIndex < 0) return
        val targetPos = intent.getIntExtra("chapterPos", 0)
        intent.removeExtra("chapterIndex")
        intent.removeExtra("chapterPos")
        when {
            targetIndex != AudioPlay.durChapterIndex -> viewModel.skipTo(targetIndex, targetPos)
            targetPos != AudioPlay.durChapterPos -> viewModel.adjustProgress(targetPos)
        }
    }

    // ---- 菜单/控制动作(原 onCompatOptionsItemSelected 与 initView 各点击项) ----

    fun showChangeSource() {
        AudioPlay.book?.let {
            showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
        }
    }

    fun showLogin() {
        AudioPlay.bookSource?.let {
            IntentData.book = AudioPlay.book
            IntentData.chapter = AudioPlay.durChapter
            it.showLoginDialog(this)
        }
    }

    fun toggleWakeLock() {
        AppConfig.audioPlayUseWakeLock = !AppConfig.audioPlayUseWakeLock
    }

    fun copyAudioUrl() = sendToClip(AudioPlayService.url)

    fun showSourceVariable() {
        AudioPlay.bookSource?.showSourceVariableDialog(this)
    }

    fun showBookVariable() {
        AudioPlay.book?.showBookVariableDialog(this, AudioPlay.bookSource)
    }

    fun editSource() {
        AudioPlay.bookSource?.let {
            IntentData.source = it
            sourceEditResult.launch {}
        }
    }

    fun openReview() = viewModel.openCommentDialog(this)

    fun showAppLog() = showDialogFragment<AppLogDialog>()

    fun addBookmark() {
        val book = AudioPlay.book ?: return
        val chapter = AudioPlay.durChapter
        val pos = AudioPlay.durChapterPos
        val total = durationMs
        val bookmark = Bookmark(bookName = book.name, bookAuthor = book.author).apply {
            chapterIndex = AudioPlay.durChapterIndex
            chapterPos = pos
            chapterName = chapter?.title ?: book.durChapterTitle ?: ""
            bookText =
                "${pos.toDurationTime()} / ${if (total > 0) total.toDurationTime() else "未知"}"
        }
        showDialogFragment(BookmarkDialog(bookmark))
    }

    fun openChapterList() {
        IntentData.book = AudioPlay.book
        IntentData.chapterList = AudioPlay.chapterList
        tocActivityResult.launch("")
    }

    fun playButton() = viewModel.togglePlay()

    /** 点击歌词行跳播(原 ivLrc.setOnPlayClickListener) */
    fun onLrcClick(time: Int) {
        viewModel.adjustProgress(time)
        if (AudioPlay.status == Status.PAUSE) viewModel.resume()
    }

    override val oldBook: Book?
        get() = AudioPlay.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (book.isAudio) {
            viewModel.changeTo(source, book, toc)
        } else {
            viewModel.stop()
            lifecycleScope.launch {
                withContext(IO) {
                    AudioPlay.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    AudioPlay.book?.delete()
                    appDb.bookDao.insert(book)
                }
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun finish() {
        val book = AudioPlay.book ?: return super.finish()

        if (AudioPlay.inBookshelf) {
            return super.finish()
        }

        if (!AppConfig.showAddToShelfAlert) {
            viewModel.removeFromBookshelf {
                viewModel.stop()
                super.finish()
            }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    AudioPlay.book?.save()
                    AudioPlay.inBookshelf = true
                    runBlocking { appDb.bookChapterDao.insert(*AudioPlay.chapterList!!.toTypedArray()) }
                    setResult(RESULT_OK)
                }
                noButton {
                    viewModel.removeFromBookshelf {
                        viewModel.stop()
                        super.finish()
                    }

                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AudioPlay.status != Status.PLAY) {
            viewModel.stop()
        }
        if (viewModel.inBookshelf) {
            AudioPlay.book?.let { viewModel.uploadProgress(it) }
        }
    }

    // 刻意不调 super(与原 View 版一致): RECREATE 不触发本页重建
    override fun observeLiveBus() {
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                playButton()
            }
        }
        observeEventSticky<AudioPlayShared.PlayMode>(EventBus.PLAY_MODE_CHANGED) {
            playMode = it
        }
        observeEventSticky<Int>(EventBus.AUDIO_STATE) {
            AudioPlay.status = it
            isPlaying = it == Status.PLAY
        }
        observeEventSticky<String>(EventBus.AUDIO_SUB_TITLE) {
            subTitle = it
            prevEnabled = AudioPlay.durChapterIndex > 0
            nextEnabled = AudioPlay.durChapterIndex < AudioPlay.simulatedChapterSize - 1
        }
        observeEventSticky<Int>(EventBus.AUDIO_SIZE) {
            durationMs = it
        }
        observeEventSticky<Int>(EventBus.AUDIO_PROGRESS) {
            progressMs = it
        }
        // AUDIO_LRCPROGRESS 单独走 STARTED 级订阅: Activity 不可见时取消订阅,
        // Service 端通过 subscriptionCount 感知到无人收看后挂起 lrc 推进协程,
        // 避免后台空转(详见 AudioPlayService.upPlayProgressForLrc 注释)。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FlowBus.withSticky(EventBus.AUDIO_LRCPROGRESS).collect {
                    if (it is Int) lrcProgress = it
                }
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_BUFFER_PROGRESS) {
            bufferMs = it
        }
        observeEventSticky<Float>(EventBus.AUDIO_SPEED) {
            speedText = String.format(Locale.ROOT, "%.1fX", it)
        }
        observeEventSticky<Int>(EventBus.AUDIO_DS) {
            timerMinute = it
        }
        observeEventSticky<Boolean>(EventBus.AUDIO_LOADING) {
            loading = it
        }
        observeEventSticky<List<Pair<Int, String>>>(EventBus.AUDIO_LRC) {
            lrcData = it
        }
        observeEventSticky<String>(EventBus.AUDIO_COVER) {
            coverUrl = it
        }
    }

    /** 模糊背景就绪后取代表色，衍生歌词/进度条颜色(原 updateLrcColor) */
    fun onBlurCoverLoaded(bitmap: Bitmap) {
        val meanColor = try {
            bitmap.getRepresentativeColor()
        } catch (_: Exception) {
            return
        }
        val secondaryHsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(meanColor, secondaryHsl)
        val isLight = secondaryHsl[2] > 0.6f

        if (isLight) {
            secondaryHsl[2] = (secondaryHsl[2] - 0.45f).coerceAtLeast(0.3f)
        } else {
            secondaryHsl[2] = (secondaryHsl[2] + 0.45f).coerceAtMost(0.7f)
        }
        val secondaryColor = androidx.core.graphics.ColorUtils.HSLToColor(secondaryHsl)

        val primaryHsl = secondaryHsl.copyOf()
        if (isLight) {
            primaryHsl[2] = (primaryHsl[2] - 0.35f).coerceAtLeast(0.2f)
        } else {
            primaryHsl[2] = (primaryHsl[2] + 0.35f).coerceAtMost(0.8f)
        }
        val primaryColor = androidx.core.graphics.ColorUtils.HSLToColor(primaryHsl)

        lrcColors = primaryColor to secondaryColor
    }
}
