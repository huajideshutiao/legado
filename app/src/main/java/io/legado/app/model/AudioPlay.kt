package io.legado.app.model

import android.content.Intent
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.service.AudioPlayService
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * 音频播放跨组件共享状态 + Service 派发包装。
 *
 * 这里只保留:
 * - 跨 Activity / Service 的可见状态 (book, chapter, durPlayUrl, lrc 等)
 * - 向 [AudioPlayService] 派发命令的薄封装
 * - 与 state 强耦合的写入操作 (saveRead, saveDurChapter, upDurChapter, playPositionChanged)
 *
 * 不在这里:
 * - 歌词解析:见 [LrcParser]
 * - 加载播放 URL / 封面 / 歌词:见 [AudioPlayService] (Service 拥有生命周期作用域,适合做异步加载)
 *
 * UI 更新一律通过 EventBus 推送(AUDIO_COVER / AUDIO_LRC / AUDIO_LOADING / ...),
 * 不持有 Activity 引用以避免内存泄漏。
 *
 * UI 命令面应通过 AudioPlayViewModel,而不是直接调本对象。
 */
@Suppress("unused")
object AudioPlay {

    enum class PlayMode(val iconRes: Int) {
        LIST_END_STOP(R.drawable.ic_play_mode_list_end_stop),
        SINGLE_LOOP(R.drawable.ic_play_mode_single_loop),
        RANDOM(R.drawable.ic_play_mode_random),
        LIST_LOOP(R.drawable.ic_play_mode_list_loop);

        fun next(): PlayMode = when (this) {
            LIST_END_STOP -> SINGLE_LOOP
            SINGLE_LOOP -> RANDOM
            RANDOM -> LIST_LOOP
            LIST_LOOP -> LIST_END_STOP
        }
    }

    var playMode = PlayMode.LIST_END_STOP
    var status = Status.STOP
    var book: Book? = null
    var chapterSize = 0
    var simulatedChapterSize = 0
    var chapterList: List<BookChapter>? = null
    var durChapterIndex = 0
    var durChapterPos = 0
    var durChapter: BookChapter? = null
    var durPlayUrl = ""
    var durCoverUrl: String? = null
    var durLrcData: List<Pair<Int, String>>? = null
    var durAudioSize = 0
    var inBookshelf = false
    var bookSource: BookSource? = null

    // ---------- Service 派发 ----------

    /**
     * 向 [AudioPlayService] 发送命令。
     *
     * @param requireRunning 仅在服务运行中才派发(默认 true)。
     *                       播放/加载类命令需要设置为 false 来启动服务。
     */
    private inline fun sendAction(
        action: String,
        requireRunning: Boolean = true,
        extras: Intent.() -> Unit = {}
    ) {
        if (requireRunning && !AudioPlayService.isRun) return
        appCtx.startService<AudioPlayService> {
            this.action = action
            extras()
        }
    }

    fun play() = sendAction(IntentAction.play, requireRunning = false)

    private fun playNew() = sendAction(IntentAction.playNew, requireRunning = false)

    fun stop() = sendAction(IntentAction.stop)

    fun stopPlay() = sendAction(IntentAction.stopPlay)

    fun pause() {
        saveRead()
        sendAction(IntentAction.pause)
    }

    fun resume() = sendAction(IntentAction.resume)

    fun adjustSpeed(adjust: Float) =
        sendAction(IntentAction.adjustSpeed) { putExtra("adjust", adjust) }

    fun adjustProgress(position: Int) {
        durChapterPos = position
        saveRead()
        sendAction(IntentAction.adjustProgress) { putExtra("position", position) }
    }

    fun setTimer(minute: Int) {
        if (AudioPlayService.isRun) {
            sendAction(IntentAction.setTimer) { putExtra("minute", minute) }
        } else {
            AudioPlayService.pendingTimerMinute = minute
            postEvent(EventBus.AUDIO_DS, minute)
        }
    }

    fun addTimer() = sendAction(IntentAction.addTimer, requireRunning = false)

    /**
     * 触发 Service 加载当前章节的播放 URL / 封面 / 歌词。
     * Service 未运行时此调用会启动 Service。
     */
    fun loadOrUpPlayUrl() {
        if (durPlayUrl.isEmpty()) {
            sendAction(IntentAction.loadPlayUrl, requireRunning = false)
        } else {
            play()
        }
    }

    // ---------- 状态变更 ----------

    fun changePlayMode() {
        playMode = playMode.next()
        postEvent(EventBus.PLAY_MODE_CHANGED, playMode)
    }

    suspend fun upData(book: Book) {
        if (durChapterIndex != book.durChapterIndex || this.book?.bookUrl != book.bookUrl) {
            resetData(book)
            return
        }
        durCoverUrl?.let { postEvent(EventBus.AUDIO_COVER, it) }
        durLrcData?.let { postEvent(EventBus.AUDIO_LRC, it) }
        postEvent(EventBus.AUDIO_PROGRESS, durChapterPos)
    }

    suspend fun resetData(book: Book) {
        stop()
        status = Status.STOP
        AudioPlay.book = book
        ReadTimeRecorder.setBook(ReadTimeRecorder.Source.AUDIO, book.name)
        if (chapterList?.firstOrNull()?.bookUrl != book.bookUrl) {
            chapterList = null
        }
        chapterSize = chapterList?.size ?: withContext(Dispatchers.IO) {
            appDb.bookChapterDao.getChapterCount(book.bookUrl)
        }
        simulatedChapterSize =
            if (book.readSimulating()) book.simulatedTotalChapterNum() else chapterSize
        bookSource = book.getBookSource()
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        durPlayUrl = ""
        durAudioSize = 0
        upDurChapter()
        postEvent(EventBus.AUDIO_BUFFER_PROGRESS, 0)
    }

    suspend fun upDurChapter() {
        val book = book ?: return
        durChapter = chapterList?.get(durChapterIndex) ?: withContext(Dispatchers.IO) {
            appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)
        }
        durAudioSize = durChapter?.end?.toInt() ?: 0
        durLrcData = null
        postEvent(EventBus.AUDIO_LRC, emptyList<Pair<Int, String>>())
        val title = durChapter?.title ?: appCtx.getString(R.string.data_loading)
        postEvent(EventBus.AUDIO_SUB_TITLE, title)
        postEvent(EventBus.AUDIO_SIZE, durAudioSize)
        postEvent(EventBus.AUDIO_PROGRESS, durChapterPos)
    }

    fun skipTo(index: Int) {
        if (index !in 0..<simulatedChapterSize) return
        ReadTimeRecorder.flushAll()
        stopPlay()
        durChapterIndex = index
        durChapterPos = 0
        durPlayUrl = ""
        saveRead()
        sendAction(IntentAction.loadPlayUrl, requireRunning = false)
    }

    fun prev() {
        if (durChapterIndex <= 0) return
        ReadTimeRecorder.flushAll()
        stopPlay()
        durChapterIndex -= 1
        durChapterPos = 0
        durPlayUrl = ""
        saveRead()
        sendAction(IntentAction.loadPlayUrl, requireRunning = false)
    }

    fun next() {
        val newIndex = when (playMode) {
            PlayMode.LIST_END_STOP ->
                if (durChapterIndex + 1 < simulatedChapterSize) durChapterIndex + 1 else return
            PlayMode.SINGLE_LOOP -> durChapterIndex
            PlayMode.RANDOM -> (0 until simulatedChapterSize).random()
            PlayMode.LIST_LOOP -> (durChapterIndex + 1) % simulatedChapterSize
        }
        ReadTimeRecorder.flushAll()
        stopPlay()
        durChapterIndex = newIndex
        durChapterPos = 0
        durPlayUrl = ""
        saveRead()
        sendAction(IntentAction.loadPlayUrl, requireRunning = false)
    }

    fun saveRead() {
        val book = book ?: return
        Coroutine.async {
            book.lastCheckCount = 0
            book.durChapterTime = System.currentTimeMillis()
            val chapterChanged = book.durChapterIndex != durChapterIndex
            book.durChapterIndex = durChapterIndex
            book.durChapterPos = durChapterPos
            if (chapterChanged) {
                durChapter?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                        book.getUseReplaceRule()
                    )
                }
            }
            book.update()
        }
    }

    /**
     * 保存章节长度
     */
    fun saveDurChapter(audioSize: Long) {
        val chapter = durChapter ?: return
        Coroutine.async {
            durAudioSize = audioSize.toInt()
            chapter.end = audioSize
            if (!book!!.isNotShelf) appDb.bookChapterDao.update(chapter)
        }
    }

    fun playPositionChanged(position: Int) {
        durChapterPos = position
        saveRead()
    }

}
