package io.legado.app.model

import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.getDisplayTitle
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.model.AudioPlayShared.resetData
import io.legado.app.utils.postEvent
import kotlinx.coroutines.withContext

/**
 * 音频播放跨组件共享状态 + Service 派发包装 (KMP shared/commonMain 版本)。
 *
 * 原 app 端 `io.legado.app.model.AudioPlay` object 整体下沉, app 端通过
 * `typealias AudioPlay = AudioPlayShared` 保持 100+ 调用点零改动。
 *
 * # 平台差异处理
 * - **Service 派发**: 原 `sendAction(action) { putExtra(...) }` 走
 *   `appCtx.startService<AudioPlayService>`, 依赖 Android Context/Intent/splitties,
 *   抽象为 [AudioPlayCommander] 接口, app 端注册 `AndroidAudioPlayCommander` 实现
 *   (内部仍走 startService<AudioPlayService> + IntentAction + extras, 行为完全等价)。
 * - **Book 平台操作**: `book.saveRead()` / `book.save()` / `book.getBookSource()` 扩展
 *   位于 app 端 BookExtensions.kt (依赖 runBlocking + appDb), 抽象为 [AudioPlayBookBridge]
 *   接口, app 端注册实现委托现有扩展。
 * - **PlayMode.iconRes**: R.drawable.* 是 Android 资源 ID, enum 构造参数无法跨平台。
 *   下沉后 PlayMode 不含 iconRes, app 端通过扩展属性 `PlayMode.iconRes` 提供
 *   (见 app 端 AudioPlay.kt), 调用方 `playMode.iconRes` 语法不变 (Kotlin 扩展属性与
 *   构造属性访问语法一致)。
 * - **appDb**: 改为 `AppDbProviders.get().bookChapterDao` (已下沉 provider)。
 * - **ContentProcessor**: 改为 `ContentProcessorProviders.get().getTitleReplaceRules(book)`
 *   (已下沉 provider)。
 *
 * # 保留不动的逻辑 (与 app 端完全一致)
 * - 跨 Activity / Service 的可见状态 (book, chapter, durPlayUrl, lrc 等)
 * - 与 state 强耦合的写入操作 (saveRead, saveDurChapter, upDurChapter, playPositionChanged)
 * - 章节切换核心逻辑 (prev/next/skipTo/setProgress, playMode 决定 newIndex)
 *
 * # 不在这里 (与 app 端一致)
 * - 歌词解析: 见 [LrcParser]
 * - 加载播放 URL / 封面 / 歌词: 见 app 端 `AudioPlayService` (Service 拥有生命周期作用域,
 *   适合做异步加载; ExoPlayer/MediaSession 等 Android 专属依赖保留 app 端)
 *
 * UI 更新一律通过 EventBus 推送 (AUDIO_COVER / AUDIO_LRC / AUDIO_LOADING / ...),
 * 不持有 Activity 引用以避免内存泄漏。
 *
 * UI 命令面应通过 AudioPlayViewModel, 而不是直接调本对象。
 */
@Suppress("unused")
object AudioPlayShared {

    /**
     * 播放模式 (跨平台版本, 不含 iconRes)。
     *
     * app 端通过扩展属性 `val PlayMode.iconRes: Int` 提供图标资源 ID
     * (R.drawable.ic_play_mode_*), 调用方 `playMode.iconRes` 语法不变。
     */
    enum class PlayMode {
        LIST_END_STOP,
        SINGLE_LOOP,
        RANDOM,
        LIST_LOOP;

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

    // ---------- Service 派发 (经 AudioPlayCommander 抽象, 平台差异见接口注释) ----------

    fun play() = AudioPlayCommanders.get().play()

    private fun playNew() = AudioPlayCommanders.get().playNew()

    fun stop() = AudioPlayCommanders.get().stop()

    fun stopPlay() = AudioPlayCommanders.get().stopPlay()

    fun pause() {
        saveRead()
        AudioPlayCommanders.get().pause()
    }

    fun resume() = AudioPlayCommanders.get().resume()

    fun adjustSpeed(adjust: Float) =
        AudioPlayCommanders.get().adjustSpeed(adjust)

    fun adjustProgress(position: Int) {
        durChapterPos = position
        AudioPlayCommanders.get().adjustProgress(position)
    }

    fun setTimer(minute: Int) {
        val commander = AudioPlayCommanders.get()
        if (commander.isServiceRunning) {
            commander.setTimer(minute)
        } else {
            commander.pendingTimerMinute = minute
            postEvent(EventBus.AUDIO_DS, minute)
        }
    }

    fun addTimer() = AudioPlayCommanders.get().addTimer()

    /**
     * 触发 Service 加载当前章节的播放 URL / 封面 / 歌词。
     * Service 未运行时此调用会启动 Service。
     */
    fun loadOrUpPlayUrl() {
        if (durPlayUrl.isEmpty()) {
            AudioPlayCommanders.get().loadPlayUrl()
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
        this.book = book
        ReadTimeRecorder.setBook(ReadTimeRecorder.Source.AUDIO, book.name)
        if (chapterList?.firstOrNull()?.bookUrl != book.bookUrl) {
            chapterList = null
        }
        chapterSize = chapterList?.size ?: withContext(IoDispatcher) {
            AppDbProviders.get().bookChapterDao.getChapterCount(book.bookUrl)
        }
        simulatedChapterSize =
            if (book.readSimulating()) book.simulatedTotalChapterNum() else chapterSize
        bookSource = AudioPlayBookBridges.get().getBookSource(book)
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        durPlayUrl = ""
        durAudioSize = 0
        upDurChapter()
        postEvent(EventBus.AUDIO_BUFFER_PROGRESS, 0)
    }

    /**
     * 目录到货后同步章节计数 (对齐 [resetData] 语义, 参照 ReadBook.updateChapterList)。
     *
     * 进入播放页时若 DB 无目录, [resetData] 只能把 simulatedChapterSize 算到 0,
     * 切章按钮可用性与 skipTo 边界会永久失效; 异步回源成功后必须调用本函数重算。
     */
    fun updateChapterList(list: List<BookChapter>) {
        val cur = book ?: return
        // 换书竞态防护: 目录与当前书不匹配时忽略 (对齐 resetData 的 bookUrl 校验),
        // 防旧书回源协程晚到污染新书的计数/目录
        if (list.firstOrNull()?.bookUrl != cur.bookUrl) return
        chapterList = list
        chapterSize = list.size
        // 模拟追读按日解锁章节数 (与 resetData 一致)
        simulatedChapterSize = if (cur.readSimulating()) {
            cur.simulatedTotalChapterNum()
        } else {
            list.size
        }
    }

    suspend fun upDurChapter() {
        val book = book ?: return
        durChapter = chapterList?.get(durChapterIndex) ?: withContext(IoDispatcher) {
            AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, durChapterIndex)
        }
        durAudioSize = durChapter?.end?.toInt() ?: 0
        durLrcData = null
        postEvent(EventBus.AUDIO_LRC, emptyList<Pair<Int, String>>())
        postEvent(EventBus.AUDIO_LRCPROGRESS, -1)
        val title = durChapter?.title ?: appString(AppStringKey.data_loading)
        postEvent(EventBus.AUDIO_SUB_TITLE, title)
        postEvent(EventBus.AUDIO_SIZE, durAudioSize)
        postEvent(EventBus.AUDIO_PROGRESS, durChapterPos)
    }

    /**
     * 应用云端进度: 章节变化则 skipTo 并保留新的 pos, 同章节仅调 adjustProgress
     */
    fun setProgress(progress: BookProgress) {
        if (progress.durChapterIndex >= simulatedChapterSize) return
        if (durChapterIndex == progress.durChapterIndex
            && durChapterPos == progress.durChapterPos
        ) return
        if (durChapterIndex != progress.durChapterIndex) {
            skipTo(progress.durChapterIndex)
            durChapterPos = progress.durChapterPos
        } else {
            adjustProgress(progress.durChapterPos)
        }
    }

    fun skipTo(index: Int, startPos: Int = 0) {
        if (index !in 0..<simulatedChapterSize) return
        ReadTimeRecorder.flushAll()
        stopPlay()
        durChapterIndex = index
        durChapterPos = startPos
        // 同步落到 book, 否则 Service 读 book.durChapterPos 会拿到旧值(saveRead 是 async 落库)
        book?.durChapterPos = startPos
        book?.durChapterIndex = index
        durPlayUrl = ""
        saveRead()
        AudioPlayCommanders.get().loadPlayUrl()
    }

    fun prev() {
        if (durChapterIndex <= 0) return
        ReadTimeRecorder.flushAll()
        stopPlay()
        durChapterIndex -= 1
        durChapterPos = 0
        durPlayUrl = ""
        // 同步落到 book (saveRead 是 async 落库): doLoadPlayUrl 的章节作废校验
        // (chapter.index != book.durChapterIndex) 读 book 即时值, 不同步会误判新章
        // 作废 → LOADING 残留无限转圈 (对齐 skipTo 的同步写法)
        book?.durChapterIndex = durChapterIndex
        book?.durChapterPos = durChapterPos
        saveRead()
        AudioPlayCommanders.get().loadPlayUrl()
    }

    fun next() {
        // 目录未就绪/为空时 RANDOM 分支 `(0 until 0).random()` 会抛异常, 防御
        if (simulatedChapterSize <= 0) return
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
        // 同步落到 book (saveRead 是 async 落库): 同 prev(), 防 doLoadPlayUrl 的
        // 章节作废校验误判 → LOADING 残留无限转圈 (对齐 skipTo 的同步写法)
        book?.durChapterIndex = durChapterIndex
        book?.durChapterPos = durChapterPos
        saveRead()
        AudioPlayCommanders.get().loadPlayUrl()
    }

    fun saveRead() {
        val book = book ?: return
        Coroutine.async {
            val chapterChanged = book.durChapterIndex != durChapterIndex
            book.durChapterIndex = durChapterIndex
            book.durChapterPos = durChapterPos
            if (chapterChanged) {
                durChapter?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessorProviders.get().getTitleReplaceRules(book),
                        book.getUseReplaceRule()
                    )
                }
            }
            AudioPlayBookBridges.get().saveRead(book)
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
            if (!book!!.isNotShelf) AppDbProviders.get().bookChapterDao.update(chapter)
        }
    }

    fun playPositionChanged(position: Int) {
        durChapterPos = position
    }

}
