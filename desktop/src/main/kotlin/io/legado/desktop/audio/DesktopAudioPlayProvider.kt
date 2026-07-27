package io.legado.desktop.audio

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.removeType
import io.legado.app.help.media.SleepTimer
import io.legado.app.model.AudioPlayBookBridge
import io.legado.app.model.AudioPlayBookBridges
import io.legado.app.model.AudioPlayCommander
import io.legado.app.model.AudioPlayCommanders
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.LrcParser
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 桌面端 AudioPlay 平台 provider (对应 app 端 [io.legado.app.model.AudioPlayProvidersImpl])。
 *
 * 实现 [AudioPlayCommander] + [AudioPlayBookBridge] 两个接口, 编排 [DesktopAudioPlayer]
 * (jlayer + javax.sound.sampled) + [SleepTimer] + 状态推送, 等价 app 端
 * [io.legado.app.service.AudioPlayService] 的核心播放逻辑。
 *
 * # 与 app 端 AudioPlayService 行为对照
 * - play/pause/resume/stop: 直接调 [DesktopAudioPlayer] 对应方法 + postEvent(AUDIO_STATE)
 * - adjustProgress: player.seekTo (MP3 重新拉流跳帧, 比 ExoPlayer SimpleCache 慢)
 * - adjustSpeed: player.setSpeed (jlayer 改采样率变速, 音调会变)
 * - loadPlayUrl: 用 [WebBook.getContentAwait] 拿直链 URL (与 app 端一致),
 *   跳过 AnalyzeUrl 二次解析 (app 端用 AnalyzeUrl.getMediaItem 主要为 setCookie + headers,
 *   桌面端用直链 + 默认 UA 即可; 需 cookie/header 的书源可能播放失败, 属已知限制)
 * - setTimer/addTimer: 复用 shared commonMain 的 [SleepTimer] (行为与 app 端完全一致)
 * - saveRead/save/getBookSource: 直接调 AppDbProviders 暴露的 DAO (等价 app 端 BookExtensions)
 *
 * # 不实现 (与 app 端 AudioPlayService 差异)
 * - 封面加载 (loadCover): 依赖 Glide + AnalyzeRule.evalJS (musicCover 规则), 桌面端 UI 暂未消费
 * - 歌词加载 (loadLrcData): 已实现, 用 AnalyzeRuleCore.evalJS(subContent) + LrcParser 解析 (见 [loadLrcData])
 * - MediaSession/Notification/WakeLock/AudioFocus: Android 专属, 桌面端无对应概念
 *
 * 注册时机: desktop Main.kt, 在所有依赖 provider (AppDbProviders/OkHttpClientProviders/JsEngines 等)
 * 注册之后。模式参考 [io.legado.app.model.AudioPlayProvidersImpl] / registerAndroidAudioPlayProviders。
 */
class DesktopAudioPlayProvider : AudioPlayCommander, AudioPlayBookBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val player = DesktopAudioPlayer()

    /** 对应 app 端 AudioPlayService.isRun */
    @Volatile private var running = false

    /** 对应 app 端 AudioPlayService.pause */
    @Volatile private var paused = true

    /** 对应 app 端 AudioPlayService.playSpeed */
    @Volatile private var playSpeed: Float = 1f

    /** 当前正在播放的 URL (对应 app 端 AudioPlayService.url) */
    @Volatile private var currentUrl: String = ""

    private var sleepTimer: SleepTimer? = null
    private var progressJob: Job? = null

    /** prepare 完成后要 seek 到的起始位置 (对应 app 端 play() 里的 exoPlayer.seekTo(position)) */
    @Volatile private var pendingStartPos: Int = 0

    /** 正在加载的章节 index 集合, 防并发 (对应 app 端 loadingChapters) */
    private val loadingChapters = arrayListOf<Int>()

    init {
        // 注册 player 回调, 桥接到 AudioPlayShared 状态 + EventBus
        player.listener = object : DesktopAudioPlayer.Listener {
            override fun onReady(durationMs: Long) {
                // 对应 app 端 STATE_READY
                postEvent(EventBus.AUDIO_SIZE, durationMs.toInt())
                postEvent(EventBus.AUDIO_LOADING, false)
                AudioPlayShared.saveDurChapter(durationMs)
                // 对应 app 端 play() 的 exoPlayer.seekTo(position): 换章/恢复要跳到记录位置
                val startPos = pendingStartPos
                pendingStartPos = 0
                if (startPos > 0) {
                    player.seekTo(startPos.toLong())
                }
                // 真正开始播放
                player.play()
                player.setSpeed(playSpeed)
                AudioPlayShared.status = Status.PLAY
                postEvent(EventBus.AUDIO_STATE, Status.PLAY)
                startProgressReport()
            }

            override fun onEndOfMedia() {
                // 对应 app 端 STATE_ENDED
                progressJob?.cancel()
                AudioPlayShared.playPositionChanged(player.duration.toInt())
                AudioPlayShared.next()
            }

            override fun onError(message: String?) {
                AppLog.put(jvmGetString("desktop_audio_play_error", message ?: ""), null, true)
                progressJob?.cancel()
                paused = true
                AudioPlayShared.status = Status.STOP
                postEvent(EventBus.AUDIO_STATE, Status.STOP)
                postEvent(EventBus.AUDIO_LOADING, false)
            }
        }
    }

    // ===== AudioPlayCommander =====

    override val isServiceRunning: Boolean
        get() = running

    override var pendingTimerMinute: Int = 0

    override fun play() {
        ensureRunning()
        scope.launch { triggerPlay(playNew = false) }
    }

    override fun playNew() {
        ensureRunning()
        scope.launch { triggerPlay(playNew = true) }
    }

    override fun stop() {
        if (!running) return
        progressJob?.cancel()
        player.stop()
        paused = true
        ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.AUDIO)
        AudioPlayShared.status = Status.STOP
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
        // 对应 app 端 onDestroy 的 saveRead
        AudioPlayShared.book?.let { saveRead(it) }
    }

    override fun stopPlay() {
        if (!running) return
        progressJob?.cancel()
        player.stop()
        paused = true
        ReadTimeRecorder.end(ReadTimeRecorder.Source.AUDIO)
        AudioPlayShared.status = Status.STOP
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
    }

    override fun pause() {
        if (!running) return
        player.pause()
        paused = true
        ReadTimeRecorder.end(ReadTimeRecorder.Source.AUDIO)
        progressJob?.cancel()
        AudioPlayShared.status = Status.PAUSE
        postEvent(EventBus.AUDIO_STATE, Status.PAUSE)
    }

    override fun resume() {
        if (!running) return
        // 对应 app 端 resume: url 空则触发加载
        if (currentUrl.isEmpty()) {
            AudioPlayShared.loadOrUpPlayUrl()
            return
        }
        paused = false
        player.play()
        ReadTimeRecorder.start(ReadTimeRecorder.Source.AUDIO, AudioPlayShared.book?.name ?: "")
        AudioPlayShared.status = Status.PLAY
        postEvent(EventBus.AUDIO_STATE, Status.PLAY)
        startProgressReport()
    }

    override fun adjustSpeed(adjust: Float) {
        if (!running) return
        playSpeed = adjust
        player.setSpeed(adjust)
        postEvent(EventBus.AUDIO_SPEED, playSpeed)
    }

    override fun adjustProgress(position: Int) {
        if (!running) return
        player.seekTo(position.toLong())
        AudioPlayShared.durChapterPos = position
        postEvent(EventBus.AUDIO_PROGRESS, position)
    }

    override fun setTimer(minute: Int) {
        ensureRunning()
        sleepTimer?.set(minute)
    }

    override fun addTimer() {
        ensureRunning()
        sleepTimer?.add()
    }

    override fun loadPlayUrl() {
        ensureRunning()
        scope.launch { doLoadPlayUrl() }
    }

    // ===== 编排逻辑 =====

    /**
     * 首次调用时初始化 SleepTimer + ReadTimeRecorder (对应 app 端 AudioPlayService.onCreate)。
     * 后续命令复用已建立的作用域与定时器。
     */
    private fun ensureRunning() {
        if (running) return
        running = true
        paused = true
        sleepTimer = SleepTimer(
            scope = scope,
            postMinute = { postEvent(EventBus.AUDIO_DS, it) },
            isPaused = { paused },
            onTimeout = { AudioPlayShared.stop() },
            onTick = {} // desktop 无通知, 无需刷新
        )
        ReadTimeRecorder.setBook(ReadTimeRecorder.Source.AUDIO, AudioPlayShared.book?.name ?: "")
        if (pendingTimerMinute > 0) {
            sleepTimer?.set(pendingTimerMinute)
            pendingTimerMinute = 0
        } else {
            postEvent(EventBus.AUDIO_DS, 0)
        }
    }

    /**
     * 已有 durPlayUrl 时启动播放 (对应 app 端 AudioPlayService.triggerPlay)。
     */
    private suspend fun triggerPlay(playNew: Boolean) {
        val playUrl = AudioPlayShared.durPlayUrl
        if (playUrl.isEmpty()) {
            doLoadPlayUrl()
            return
        }
        if (currentUrl == playUrl && !playNew && running && !paused) return
        player.stop()
        paused = false
        val startPos = if (playNew) 0 else AudioPlayShared.book?.durChapterPos ?: 0
        currentUrl = playUrl
        // 对应 app 端 play(): 先置 LOADING 再准备, prepare 完成 (onReady) 后 seekTo 起始位置
        postEvent(EventBus.AUDIO_LOADING, true)
        AudioPlayShared.status = Status.LOADING
        postEvent(EventBus.AUDIO_STATE, Status.LOADING)
        pendingStartPos = startPos
        player.setUrl(playUrl, emptyMap())
        player.prepare()
    }

    /**
     * 加载当前章节播放 URL (对应 app 端 AudioPlayService.loadPlayUrl)。
     *
     * 流程: 取 chapter.resourceUrl, 没有则 [WebBook.getContentAwait] fetch → 写回 → triggerPlay。
     * 同章节防并发 (loadingChapters)。
     */
    private suspend fun doLoadPlayUrl() {
        val index = AudioPlayShared.durChapterIndex
        if (!addLoading(index)) return
        try {
            val book = AudioPlayShared.book
            val source = AudioPlayShared.bookSource
            if (book == null || source == null) {
                AppLog.put("book or source is null", null, true)
                return
            }
            AudioPlayShared.upDurChapter()
            val chapter = AudioPlayShared.durChapter ?: return
            if (chapter.isVolume) {
                AudioPlayShared.skipTo(index + 1)
                return
            }
            postEvent(EventBus.AUDIO_LOADING, true)
            // 拉链接窗口置 LOADING, 与 shared AudioPlayManager.loadPlayUrl 对齐
            AudioPlayShared.status = Status.LOADING
            postEvent(EventBus.AUDIO_STATE, Status.LOADING)
            AudioPlayShared.durCoverUrl = null
            AudioPlayShared.durLrcData = null
            // 并行加载歌词 (对照 app 端 AudioPlayService.loadLrcData, 不阻塞播放 URL 加载)
            scope.launch { loadLrcData(source, book, chapter) }
            // 取直链 URL (getContentAwait 已用 AnalyzeUrl 解析过书源规则, 返回直链)
            val content = chapter.resourceUrl
                ?: WebBook.getContentAwait(source, book, chapter, needSave = false)
            if (content.isEmpty()) {
                AppLog.put(jvmGetString("desktop_audio_no_resource_url"), null, true)
                postEvent(EventBus.AUDIO_LOADING, false)
                AudioPlayShared.status = Status.STOP
                postEvent(EventBus.AUDIO_STATE, Status.STOP)
                return
            }
            if (chapter.resourceUrl != content) {
                chapter.resourceUrl = content
                if (AudioPlayShared.inBookshelf) {
                    AppDbProviders.get().bookChapterDao.update(chapter)
                }
            }
            AudioPlayShared.durPlayUrl = content
            triggerPlay(playNew = false)
        } catch (e: Exception) {
            AppLog.put(jvmGetString("desktop_audio_load_failed", e.message ?: ""), e, true)
            postEvent(EventBus.AUDIO_LOADING, false)
            AudioPlayShared.status = Status.STOP
            postEvent(EventBus.AUDIO_STATE, Status.STOP)
        } finally {
            removeLoading(index)
        }
    }

    /**
     * 用书源的 subContent 规则计算歌词数据 (对照 app 端 AudioPlayService.loadLrcData)。
     *
     * subContent 经 [AnalyzeRuleCore.evalJS] 求值, 期望返回 List<String> (每元素为多行 LRC 文本);
     * 用 [LrcParser.parse] 解析为 (timeMs, text) 列表, 写入 [AudioPlayShared.durLrcData]
     * 并 postEvent(AUDIO_LRC) / (AUDIO_LRCPROGRESS=0) 触发 UI 刷新。
     *
     * 经 [AnalyzeRuleFactories] 创建规则实例 (desktop 端拿到 DesktopAnalyzeRule, JS 扩展面完整);
     * JS 引擎 / 网络 (ajax) 经 desktop Main.kt 已注册的 JsEngines / SourceNetworkProviders 走通。
     */
    private suspend fun loadLrcData(
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter,
    ) {
        try {
            val subContent = bookSource.contentRule.subContent
            if (subContent.isNullOrBlank()) return
            val rule = AnalyzeRuleFactories.create(book, bookSource)
            rule.coroutineContext = currentCoroutineContext()
            rule.setBaseUrl(chapter.url)
            rule.chapter = chapter
            val raw = rule.evalJS(subContent) as? List<*> ?: return
            val parsed = LrcParser.parse(raw)
            if (parsed.isEmpty()) return
            AudioPlayShared.durLrcData = parsed
            postEvent(EventBus.AUDIO_LRC, parsed)
            postEvent(EventBus.AUDIO_LRCPROGRESS, 0)
        } catch (e: Exception) {
            AppLog.put(jvmGetString("desktop_lrc_load_failed", e.message ?: ""), e, true)
        }
    }

    /**
     * 每秒发送播放进度 (对应 app 端 AudioPlayService.upPlayProgress)。
     */
    private fun startProgressReport() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val pos = player.currentPosition.toInt()
                AudioPlayShared.durChapterPos = pos
                postEvent(EventBus.AUDIO_PROGRESS, pos)
                // 同步推进歌词高亮 (AUDIO_LRCPROGRESS 约定发行下标, 不是毫秒)
                val lrc = AudioPlayShared.durLrcData
                if (!lrc.isNullOrEmpty()) {
                    val curMs = pos + LRC_OFFSET_MS
                    var line = 0
                    while (line + 1 < lrc.size && lrc[line + 1].first <= curMs) {
                        line++
                    }
                    postEvent(EventBus.AUDIO_LRCPROGRESS, line)
                }
                delay(1000)
            }
        }
    }

    // 同章节防并发 (对应 app 端 loadingChapters)
    private fun addLoading(index: Int): Boolean = synchronized(loadingChapters) {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        true
    }

    private fun removeLoading(index: Int) = synchronized(loadingChapters) {
        loadingChapters.remove(index)
    }

    // ===== AudioPlayBookBridge =====
    // 对应 app 端 BookExtensions.saveRead/save/getBookSource (desktop 无 BookExtensions, 直接调 DAO)

    override fun saveRead(book: Book) {
        // 对应 app 端 Book.saveRead(): PATCH 进度字段 + flush ReadTimeRecorder
        scope.launch(Dispatchers.IO) {
            try {
                book.durChapterTime = System.currentTimeMillis()
                AppDbProviders.get().bookDao.updateProgress(
                    bookUrl = book.bookUrl,
                    durChapterIndex = book.durChapterIndex,
                    durChapterPos = book.durChapterPos,
                    durChapterTime = book.durChapterTime,
                    durChapterTitle = book.durChapterTitle
                )
                ReadTimeRecorder.flushAll()
            } catch (e: Exception) {
                AppLog.put(jvmGetString("desktop_audio_save_read_failed", e.message ?: ""), e)
            }
        }
    }

    override fun save(book: Book) {
        // 对应 app 端 Book.save(): removeType(notShelf) + has/insert/update
        scope.launch(Dispatchers.IO) {
            try {
                book.removeType(BookType.notShelf)
                val dao = AppDbProviders.get().bookDao
                if (dao.has(book.bookUrl)) {
                    dao.update(book)
                } else {
                    dao.insert(book)
                }
            } catch (e: Exception) {
                AppLog.put(jvmGetString("desktop_audio_save_failed", e.message ?: ""), e)
            }
        }
    }

    override fun getBookSource(book: Book): BookSource? {
        // 对应 app 端 Book.getBookSource(): runBlocking 查 bookSourceDao
        return runBlocking {
            try {
                AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
            } catch (e: Exception) {
                AppLog.put(jvmGetString("desktop_audio_get_book_source_failed", e.message ?: ""), e)
                null
            }
        }
    }

    private companion object {
        /** 歌词同步补偿偏移量 (毫秒), 与 shared AudioPlayManager.lrcOffsetMs 一致 */
        private const val LRC_OFFSET_MS = 60L
    }
}

/**
 * 桌面端注册 AudioPlay 平台 provider (对应 app 端 registerAndroidAudioPlayProviders)。
 *
 * 必须在所有依赖 provider (AppDbProviders / OkHttpClientProviders / JsEngines /
 * SourceHelpAccessors / registerDesktopWebBookProviders) 注册之后调用,
 * 因 doLoadPlayUrl 经 [WebBook.getContentAwait] 间接访问 appDb + webBook 编排层 + JS 引擎。
 *
 * 调用时机: desktop Main.kt, 在 registerDesktopWebBookProviders() / registerDesktopJsEngines() 之后。
 */
fun registerDesktopAudioPlayProviders() {
    val impl = DesktopAudioPlayProvider()
    AudioPlayCommanders.register(impl)
    AudioPlayBookBridges.register(impl)
}
