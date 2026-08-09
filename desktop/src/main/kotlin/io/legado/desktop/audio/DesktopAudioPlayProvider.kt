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
import io.legado.app.help.toast.Toasters
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
import io.legado.app.utils.FlowBus
import io.legado.app.utils.postEvent
import io.legado.desktop.help.tts.DesktopReadAloudHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 桌面端 AudioPlay 平台 provider (对应 app 端 [io.legado.app.model.AudioPlayProvidersImpl])。
 *
 * 实现 [AudioPlayCommander] + [AudioPlayBookBridge] 两个接口, 编排 [DesktopAudioPlayer]
 * (mediamp-mpv 引擎, FFmpeg 全格式) + [SleepTimer] + 状态推送, 等价 app 端
 * [io.legado.app.service.AudioPlayService] 的核心播放逻辑。
 *
 * # 与 app 端 AudioPlayService 行为对照
 * - play/pause/resume/stop: 直接调 [DesktopAudioPlayer] 对应方法 + postEvent(AUDIO_STATE)
 * - adjustProgress: player.seekTo (mpv 原生 seek, 不再重新拉流跳帧)
 * - adjustSpeed: player.setSpeed (mpv speed 属性, 保音高)
 * - loadPlayUrl: 用 [WebBook.getContentAwait] 拿直链 URL (与 app 端一致),
 *   跳过 AnalyzeUrl 二次解析 (app 端用 AnalyzeUrl.getMediaItem 主要为 setCookie + headers;
 *   桌面端目前传空 headers 播直链, 需 cookie/header 的书源可能播放失败, 属已知限制,
 *   mediamp UriMediaData 已支持 headers, 待 provider 接入 AnalyzeUrl 解析即可生效)
 * - setTimer/addTimer: 复用 shared commonMain 的 [SleepTimer] (行为与 app 端完全一致)
 * - saveRead/save/getBookSource: 直接调 AppDbProviders 暴露的 DAO (等价 app 端 BookExtensions)
 *
 * # 不实现 (与 app 端 AudioPlayService 差异)
 * - MediaSession/Notification/WakeLock/AudioFocus: Android 专属, 桌面端无对应概念 (托盘/任务栏/通知由 DesktopMediaTray/DesktopTaskbarMedia 承载)
 * - MediaSession/Notification/WakeLock/AudioFocus: Android 专属, 桌面端无对应概念
 *
 * 注册时机: desktop Main.kt, 在所有依赖 provider (AppDbProviders/OkHttpClientProviders/JsEngines 等)
 * 注册之后。模式参考 [io.legado.app.model.AudioPlayProvidersImpl] / registerAndroidAudioPlayProviders。
 */
class DesktopAudioPlayProvider : AudioPlayCommander, AudioPlayBookBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val player = DesktopAudioPlayer()

    /** 对应 AudioPlayService.isRun */
    @Volatile private var running = false

    /** 对应 AudioPlayService.pause */
    @Volatile private var paused = true

    /** 对应 AudioPlayService.playSpeed */
    @Volatile private var playSpeed: Float = 1f

    /** 当前正在播放的 URL */
    @Volatile private var currentUrl: String = ""

    private var sleepTimer: SleepTimer? = null
    private var progressJob: Job? = null

    /** prepare 完成后要 seek 到的起始位置 */
    @Volatile private var pendingStartPos: Int = 0

    /** 正在加载的章节 index 集合, 防并发 */
    private val loadingChapters = arrayListOf<Int>()

    /** 播放器错误后是否已自动重试过一次 (onReady 时重置) */
    private val hasRefreshedOnPlayError = AtomicBoolean(false)

    /** 上次已发布的歌词行号 (仅变化时发) */
    @Volatile
    private var lastLrcPosition = -1

    init {
        // 注册 player 回调, 桥接到 AudioPlayShared 状态 + EventBus
        player.listener = object : DesktopAudioPlayer.Listener {
            override fun onReady(durationMs: Long) {
                // 就绪: 重置重试标志; duration 未知 (-1) 时守卫, 避免写坏 chapter.end
                hasRefreshedOnPlayError.set(false)
                if (durationMs > 0) {
                    postEvent(EventBus.AUDIO_SIZE, durationMs.toInt())
                    AudioPlayShared.saveDurChapter(durationMs)
                }
                postEvent(EventBus.AUDIO_LOADING, false)
                // seekTo 换章/恢复要跳到的记录位置
                val startPos = pendingStartPos
                pendingStartPos = 0
                if (startPos > 0) {
                    player.seekTo(startPos.toLong())
                }
                // 先应用倍速 (mpv speed 属性, 起播/续播都生效)
                player.setSpeed(playSpeed)
                // 缓冲期按过暂停则就绪后不自动起播 (桌面 pause 在 prepare 阶段无效)
                if (paused) {
                    AudioPlayShared.status = Status.PAUSE
                    postEvent(EventBus.AUDIO_STATE, Status.PAUSE)
                    syncSmtc()
                    return
                }
                player.play()
                AudioPlayShared.status = Status.PLAY
                postEvent(EventBus.AUDIO_STATE, Status.PLAY)
                postEvent(EventBus.AUDIO_SPEED, playSpeed)
                startProgressReport()
                // SMTC: 开始播放即上卡 (位置用本次 seek 目标, 避免等首个进度 tick)
                syncSmtc(positionMs = if (startPos > 0) startPos.toLong() else AudioPlayShared.durChapterPos.toLong())
            }

            override fun onEndOfMedia() {
                progressJob?.cancel()
                AudioPlayShared.playPositionChanged(player.duration.toInt())
                AudioPlayShared.next()
                // 末章停止等后续状态变化会再同步, 这里先刷一次 (幂等)
                syncSmtc()
            }

            override fun onError(message: String?) {
                // 首次错误静默 refreshChapter 重试; 第二次才 STOP+日志+toast
                if (hasRefreshedOnPlayError.compareAndSet(false, true)) {
                    refreshChapter()
                    return
                }
                val msg = jvmGetString("desktop_audio_play_error", message ?: "")
                AppLog.put(msg, null, true)
                Toasters.get().toast(msg)
                progressJob?.cancel()
                paused = true
                AudioPlayShared.status = Status.STOP
                postEvent(EventBus.AUDIO_STATE, Status.STOP)
                postEvent(EventBus.AUDIO_LOADING, false)
                syncSmtc(stopped = true)
            }
        }
    }

    // ===== AudioPlayCommander =====

    override val isServiceRunning: Boolean
        get() = running

    override var pendingTimerMinute: Int = 0

    /**
     * 清掉当前章节 URL 并重新加载 (播放器报错后自动重试)。
     */
    private fun refreshChapter() {
        val chapter = AudioPlayShared.durChapter ?: return
        chapter.resourceUrl = null
        AudioPlayShared.durPlayUrl = ""
        loadPlayUrl()
    }

    override fun play() {
        ensureRunning()
        scope.launch {
            // triggerPlay 异常时收掉 LOADING (prepare 卡死由播放器层超时兜底)
            runCatching { triggerPlay(playNew = false) }.onFailure {
                AppLog.put("桌面音频播放启动失败", it)
                postEvent(EventBus.AUDIO_LOADING, false)
                AudioPlayShared.status = Status.STOP
                postEvent(EventBus.AUDIO_STATE, Status.STOP)
                syncSmtc(stopped = true)
            }
        }
    }

    override fun playNew() {
        ensureRunning()
        scope.launch {
            runCatching { triggerPlay(playNew = true) }.onFailure {
                AppLog.put("桌面音频播放启动失败", it)
                postEvent(EventBus.AUDIO_LOADING, false)
                AudioPlayShared.status = Status.STOP
                postEvent(EventBus.AUDIO_STATE, Status.STOP)
                syncSmtc(stopped = true)
            }
        }
    }

    override fun stop() {
        if (!running) return
        progressJob?.cancel()
        // 精确回写当前位置 (不依赖进度 tick)。对照 origin onDestroy: 先取位置再释放
        // (exoPlayer.currentPosition 在 release 前读), 这里须在 player.stop() 之前读,
        // 否则 stopPlayback 会重置播放器位置导致落库进度不精确。
        val pos = player.currentPosition.toInt()
        player.stop()
        paused = true
        AudioPlayShared.durChapterPos = pos
        ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.AUDIO)
        AudioPlayShared.status = Status.STOP
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
        // 停止即收掉加载转圈
        postEvent(EventBus.AUDIO_LOADING, false)
        // saveRead 落库
        AudioPlayShared.book?.let { saveRead(it) }
        syncSmtc(stopped = true)
        // 停止即销毁运行态 (清定时器), 下次 play 重建
        sleepTimer?.cancel()
        sleepTimer = null
        running = false
    }

    override fun stopPlay() {
        if (!running) return
        progressJob?.cancel()
        player.stop()
        paused = true
        ReadTimeRecorder.end(ReadTimeRecorder.Source.AUDIO)
        AudioPlayShared.status = Status.STOP
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
        postEvent(EventBus.AUDIO_LOADING, false)
        // 停止分支同样落库进度
        AudioPlayShared.book?.let { saveRead(it) }
        syncSmtc()
    }

    override fun pause() {
        if (!running) return
        player.pause()
        paused = true
        ReadTimeRecorder.end(ReadTimeRecorder.Source.AUDIO)
        progressJob?.cancel()
        AudioPlayShared.status = Status.PAUSE
        postEvent(EventBus.AUDIO_STATE, Status.PAUSE)
        syncSmtc()
    }

    override fun resume() {
        if (!running) return
        // url 空则触发加载
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
        syncSmtc()
    }

    override fun adjustSpeed(adjust: Float) {
        if (!running) return
        playSpeed = adjust
        player.setSpeed(adjust)
        postEvent(EventBus.AUDIO_SPEED, playSpeed)
        syncSmtc()
    }

    override fun adjustProgress(position: Int) {
        if (!running) return
        player.seekTo(position.toLong())
        AudioPlayShared.durChapterPos = position
        postEvent(EventBus.AUDIO_PROGRESS, position)
        syncSmtc(positionMs = position.toLong())
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
     * 首次调用时初始化 SleepTimer + ReadTimeRecorder。
     * 后续命令复用已建立的作用域与定时器。
     */
    private fun ensureRunning() {
        if (running) return
        running = true
        paused = true
        // SMTC: 首次播放时激活 (幂等)
        DesktopSmtc.init()
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
     * 已有 durPlayUrl 时启动播放。
     */
    private suspend fun triggerPlay(playNew: Boolean) {
        val playUrl = AudioPlayShared.durPlayUrl
        if (playUrl.isEmpty()) {
            doLoadPlayUrl()
            return
        }
        if (currentUrl == playUrl && !playNew && running && player.isPlaying) return
        player.stop()
        paused = false
        val startPos = if (playNew) 0 else AudioPlayShared.book?.durChapterPos ?: 0
        currentUrl = playUrl
        // 先置 LOADING 再准备, prepare 完成 (onReady) 后 seekTo 起始位置
        postEvent(EventBus.AUDIO_LOADING, true)
        AudioPlayShared.status = Status.LOADING
        postEvent(EventBus.AUDIO_STATE, Status.LOADING)
        syncSmtc()
        pendingStartPos = startPos
        player.setUrl(playUrl, emptyMap())
        player.prepare()
    }

    /**
     * 加载当前章节播放 URL。
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
                Toasters.get().toast("book or source is null")
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
            AudioPlayShared.durLrcData = null
            // 并行加载封面 (musicCover 规则) + 歌词, 不阻塞播放 URL 加载
            scope.launch { loadCoverUrl(source, book, chapter) }
            scope.launch { loadLrcData(source, book, chapter) }
            // 取直链 URL (getContentAwait 已用 AnalyzeUrl 解析过书源规则, 返回直链)
            val content = chapter.resourceUrl
                ?: WebBook.getContentAwait(source, book, chapter, needSave = false)
            if (content.isEmpty()) {
                AppLog.put(jvmGetString("desktop_audio_no_resource_url"), null, true)
                postEvent(EventBus.AUDIO_LOADING, false)
                AudioPlayShared.status = Status.STOP
                postEvent(EventBus.AUDIO_STATE, Status.STOP)
                syncSmtc(stopped = true)
                return
            }
            if (chapter.resourceUrl != content) {
                chapter.resourceUrl = content
                if (AudioPlayShared.inBookshelf) {
                    AppDbProviders.get().bookChapterDao.update(chapter)
                }
            }
            // 切章后旧 URL 结果作废 (不清 LOADING, 切章流程的 stopPlay 已处理)
            if (chapter.index != AudioPlayShared.book?.durChapterIndex) {
                return
            }
            AudioPlayShared.durPlayUrl = content
            triggerPlay(
                playNew = AudioPlayShared.durChapterIndex + 1 ==
                    AudioPlayShared.simulatedChapterSize &&
                    AudioPlayShared.durChapterPos == AudioPlayShared.durAudioSize
            )
        } catch (e: Exception) {
            AppLog.put(jvmGetString("desktop_audio_load_failed", e.message ?: ""), e, true)
            postEvent(EventBus.AUDIO_LOADING, false)
            AudioPlayShared.status = Status.STOP
            postEvent(EventBus.AUDIO_STATE, Status.STOP)
            syncSmtc(stopped = true)
        } finally {
            removeLoading(index)
        }
    }

    /**
     * 用书源的 musicCover 规则计算封面 URL, 空规则就用书的默认 cover。
     *
     * 此前桌面端未实现, 音频页封面圆形图与模糊背景恒空 (durCoverUrl 一直为 null)。
     * 求值失败/结果空时回落书籍默认封面, 仍空则不 post (UI 保持占位)。
     */
    private suspend fun loadCoverUrl(
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter,
    ) {
        try {
            // 切章后旧章封面作废
            if (chapter.index != AudioPlayShared.durChapterIndex) return
            val musicCover = bookSource.contentRule.musicCover
            val coverUrl = if (musicCover.isNullOrBlank()) {
                book.getDisplayCover()
            } else {
                // 规则求值失败/结果空时兜底书籍默认封面 (否则 durCoverUrl 恒 null,
                // 音频页封面与模糊背景不显示)
                runCatching {
                    val rule = AnalyzeRuleFactories.create(book, bookSource)
                    rule.coroutineContext = currentCoroutineContext()
                    rule.setBaseUrl(chapter.url)
                    rule.chapter = chapter
                    rule.evalJS(musicCover)?.toString()?.takeIf { it.isNotBlank() }
                }.getOrNull() ?: book.getDisplayCover()
            }
            if (chapter.index != AudioPlayShared.durChapterIndex) return
            AudioPlayShared.durCoverUrl = coverUrl
            if (!coverUrl.isNullOrBlank()) {
                postEvent(EventBus.AUDIO_COVER, coverUrl)
            }
            syncSmtc()
        } catch (e: Exception) {
            AppLog.put(jvmGetString("desktop_cover_load_failed", e.message ?: ""), e)
        }
    }

    /**
     * 用书源的 subContent 规则计算歌词数据。
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
            // 切章后旧章歌词作废
            if (chapter.index != AudioPlayShared.durChapterIndex) return
            val rule = AnalyzeRuleFactories.create(book, bookSource)
            rule.coroutineContext = currentCoroutineContext()
            rule.setBaseUrl(chapter.url)
            rule.chapter = chapter
            val raw = rule.evalJS(subContent) as? List<*> ?: return
            val parsed = LrcParser.parse(raw)
            if (parsed.isEmpty()) return
            if (chapter.index != AudioPlayShared.durChapterIndex) return
            AudioPlayShared.durLrcData = parsed
            postEvent(EventBus.AUDIO_LRC, parsed)
            postEvent(EventBus.AUDIO_LRCPROGRESS, 0)
        } catch (e: Exception) {
            AppLog.put(jvmGetString("desktop_lrc_load_failed", e.message ?: ""), e, true)
        }
    }

    /**
     * 每秒发送播放进度。
     */
    private fun startProgressReport() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val pos = player.currentPosition.toInt()
                AudioPlayShared.durChapterPos = pos
                postEvent(EventBus.AUDIO_PROGRESS, pos)
                // mpv 无独立缓冲概念暴露给进度条, 缓冲条用已播位置近似
                // (原 desktop 不 post 导致缓冲条恒空)
                postEvent(EventBus.AUDIO_BUFFER_PROGRESS, pos)
                // 时长兜底: mediamp READY 时 duration 未知 (-1), loadfile 后变已知;
                // 心跳补发 AUDIO_SIZE, 否则 UI 时长恒 0/旧值 (对齐 shared AudioPlayManager)
                val duration = player.duration
                if (duration > 0 && duration.toInt() != AudioPlayShared.durAudioSize) {
                    AudioPlayShared.durAudioSize = duration.toInt()
                    postEvent(EventBus.AUDIO_SIZE, AudioPlayShared.durAudioSize)
                    AudioPlayShared.saveDurChapter(duration)
                }
                // 同步推进歌词高亮 (AUDIO_LRCPROGRESS 约定发行下标, 不是毫秒)
                val lrc = AudioPlayShared.durLrcData
                if (!lrc.isNullOrEmpty()) {
                    // 无订阅不推进 (节能); 仅行号变化时发
                    if (FlowBus.withSticky(EventBus.AUDIO_LRCPROGRESS).subscriptionCount.value > 0) {
                        val curMs = pos + LRC_OFFSET_MS
                        var line = 0
                        while (line + 1 < lrc.size && lrc[line + 1].first <= curMs) {
                            line++
                        }
                        if (line != lastLrcPosition) {
                            lastLrcPosition = line
                            postEvent(EventBus.AUDIO_LRCPROGRESS, line)
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    // 同章节防并发
    private fun addLoading(index: Int): Boolean = synchronized(loadingChapters) {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        true
    }

    private fun removeLoading(index: Int) = synchronized(loadingChapters) {
        loadingChapters.remove(index)
    }

    // ===== AudioPlayBookBridge =====
    // saveRead/save/getBookSource (desktop 无 BookExtensions, 直接调 DAO)

    /**
     * 同步 SMTC 媒体卡 (Win11 音量浮层/锁屏卡; 非 Windows 直接跳过)。
     * 状态源与托盘/任务栏一致 (AudioPlayShared.status); 停止且朗读未在跑时摘卡。
     */
    private fun syncSmtc(positionMs: Long? = null, stopped: Boolean = false) {
        if (!com.sun.jna.Platform.isWindows()) return
        val status = AudioPlayShared.status
        DesktopSmtc.update(
            SmtcState(
                title = AudioPlayShared.durChapter?.title ?: "",
                artist = AudioPlayShared.book?.name ?: "",
                albumArtist = AudioPlayShared.book?.author ?: "",
                isPlaying = status == Status.PLAY,
                isPaused = status == Status.PAUSE || status == Status.LOADING,
                prevNextEnabled = true,
                positionMs = positionMs ?: AudioPlayShared.durChapterPos.toLong(),
                durationMs = AudioPlayShared.durAudioSize.toLong(),
                playbackRate = playSpeed,
                coverUrl = AudioPlayShared.durCoverUrl,
            )
        )
        if (stopped && !DesktopReadAloudHost.isRun) {
            DesktopSmtc.release()
        }
    }

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

    override suspend fun getBookSource(book: Book): BookSource? = withContext(Dispatchers.IO) {
        try {
            AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
        } catch (e: Exception) {
            AppLog.put(jvmGetString("desktop_audio_get_book_source_failed", e.message ?: ""), e)
            null
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
