package io.legado.desktop.audio

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.removeType
import io.legado.app.help.media.SleepTimer
import io.legado.app.help.toast.Toasters
import io.legado.app.model.AudioPlayBookBridge
import io.legado.app.model.AudioPlayBookBridges
import io.legado.app.model.AudioPlayCommander
import io.legado.app.model.AudioPlayCommanders
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.audio.AudioPlayManager
import io.legado.app.model.audio.AudioPlayManagerListener
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.utils.postEvent
import io.legado.desktop.help.tts.DesktopReadAloudHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 桌面端 AudioPlay 平台 provider (对应 app 端 [io.legado.app.model.AudioPlayProvidersImpl])。
 *
 * 实现 [AudioPlayCommander] + [AudioPlayBookBridge] + [AudioPlayManagerListener],
 * 播放引擎用 [DesktopAudioPlayer] (mediamp-mpv 引擎, FFmpeg 全格式);
 * **章节加载/封面/歌词/进度编排全部委托 shared [AudioPlayManager]**
 * (与 app/iOS/鸿蒙同源, 2026-08 去重: 原 desktop 手抄的 doLoadPlayUrl /
 * loadCoverUrl / loadLrcData / startProgressReport / refreshChapter 副本已删除,
 * 适配器 [DesktopAudioPlayController] + [DesktopAudioPlayAnalyzeRuleFactory] 复用)。
 *
 * # 与 app 端 AudioPlayService 行为对照
 * - play/pause/resume/stop: 直接调 [DesktopAudioPlayer] 对应方法 + postEvent(AUDIO_STATE)
 * - adjustProgress: player.seekTo (mpv 原生 seek, 不再重新拉流跳帧)
 * - adjustSpeed: player.setSpeed (mpv speed 属性, 保音高)
 * - loadPlayUrl/refreshChapter/loadCoverUrl/loadLrcData/进度上报/歌词推进: shared
 *   [AudioPlayManager] 统一编排 (与 app/iOS/鸿蒙同源), 本类只做平台副作用
 *   (triggerPlay 起播 / toast / SMTC 同步)
 * - setTimer/addTimer: 复用 shared commonMain 的 [SleepTimer] (行为与 app 端完全一致)
 * - saveRead/save/getBookSource: 直接调 AppDbProviders 暴露的 DAO (等价 app 端 BookExtensions)
 *
 * # 不实现 (与 app 端 AudioPlayService 差异)
 * - MediaSession/Notification/WakeLock/AudioFocus: Android 专属, 桌面端无对应概念
 *   (托盘/任务栏/通知由 DesktopMediaTray/DesktopTaskbarMedia 承载)
 *
 * 注册时机: desktop Main.kt, 在所有依赖 provider (AppDbProviders/OkHttpClientProviders/JsEngines 等)
 * 注册之后。模式参考 [io.legado.app.model.AudioPlayProvidersImpl] / registerAndroidAudioPlayProviders。
 */
class DesktopAudioPlayProvider : AudioPlayCommander, AudioPlayBookBridge, AudioPlayManagerListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val player = DesktopAudioPlayer()

    /** 播放器控制适配 (mediamp-mpv) + 章节编排 (shared manager), 与 app/iOS/鸿蒙同构。 */
    private val controller = DesktopAudioPlayController(player)
    private val manager =
        AudioPlayManager(controller, scope, DesktopAudioPlayAnalyzeRuleFactory, this)

    /** 对应 AudioPlayService.isRun */
    @Volatile private var running = false

    /** 对应 AudioPlayService.pause */
    @Volatile private var paused = true

    /** 对应 AudioPlayService.playSpeed */
    @Volatile private var playSpeed: Float = 1f

    /** 当前正在播放的 URL */
    @Volatile private var currentUrl: String = ""

    private var sleepTimer: SleepTimer? = null

    /** prepare 完成后要 seek 到的起始位置 */
    @Volatile private var pendingStartPos: Int = 0

    /** 播放器错误后是否已自动重试过一次 (onReady 时重置) */
    private val hasRefreshedOnPlayError = AtomicBoolean(false)

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
                // 进度上报 + 歌词推进走 shared manager (与 app/iOS/鸿蒙同源)
                manager.upPlayProgress()
                manager.upPlayProgressForLrc()
                // SMTC: 开始播放即上卡 (位置用本次 seek 目标, 避免等首个进度 tick)
                syncSmtc(positionMs = if (startPos > 0) startPos.toLong() else AudioPlayShared.durChapterPos.toLong())
            }

            override fun onEndOfMedia() {
                manager.cancelProgressJobs()
                AudioPlayShared.playPositionChanged(player.duration.toInt())
                AudioPlayShared.next()
                // 末章停止等后续状态变化会再同步, 这里先刷一次 (幂等)
                syncSmtc()
            }

            override fun onError(message: String?) {
                // 首次错误静默 refreshChapter 重试; 第二次才 STOP+日志+toast
                if (hasRefreshedOnPlayError.compareAndSet(false, true)) {
                    manager.refreshChapter()
                    return
                }
                val msg = jvmGetString("desktop_audio_play_error", message ?: "")
                AppLog.put(msg, null, true)
                Toasters.get().toast(msg)
                manager.cancelProgressJobs()
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
        manager.cancelProgressJobs()
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
        manager.cancelProgressJobs()
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
        manager.cancelProgressJobs()
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
        manager.upPlayProgress()
        manager.upPlayProgressForLrc()
        syncSmtc()
    }

    override fun adjustSpeed(adjust: Float) {
        if (!running) return
        playSpeed = adjust
        manager.playSpeed = adjust
        player.setSpeed(adjust)
        postEvent(EventBus.AUDIO_SPEED, playSpeed)
        // 变速后 lrc 推进 delay 需按新速率重算 (对齐 app/iOS)
        manager.upPlayProgressForLrc()
        syncSmtc()
    }

    override fun adjustProgress(position: Int) {
        if (!running) return
        player.seekTo(position.toLong())
        AudioPlayShared.durChapterPos = position
        postEvent(EventBus.AUDIO_PROGRESS, position)
        // seek 后歌词位置失效, 重算 (对齐 app/iOS)
        manager.resetLrcPosition()
        manager.upPlayProgressForLrc()
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

    /** 章节加载/封面/歌词编排全走 shared manager (与 app/iOS/鸿蒙同源)。 */
    override fun loadPlayUrl() {
        ensureRunning()
        manager.loadPlayUrl()
    }

    // ===== AudioPlayManagerListener (shared manager 的平台副作用回调) =====

    override fun onTriggerPlay(playNew: Boolean) {
        scope.launch { triggerPlay(playNew) }
    }

    override fun onLoadCover(url: String?) {
        // 封面已由 manager postEvent(AUDIO_COVER) 推送 UI; SMTC 卡封面读 durCoverUrl, 刷新一次
        syncSmtc()
    }

    override fun onResetCoverCache() {
        // desktop 无通知/MediaSession 封面缓存, 无需处理
    }

    override fun onToast(message: String) {
        Toasters.get().toast(message)
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
        // shared manager 的歌词推进 delay 按此速率缩放
        manager.playSpeed = playSpeed
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
     * 已有 durPlayUrl 时启动播放 (shared manager 加载完成后经 [onTriggerPlay] 回调进入;
     * play/playNew 命令在 url 为空时兜底走 [AudioPlayManager.loadPlayUrl] 补齐资源)。
     */
    private fun triggerPlay(playNew: Boolean) {
        val playUrl = AudioPlayShared.durPlayUrl
        if (playUrl.isEmpty()) {
            manager.loadPlayUrl()
            return
        }
        if (currentUrl == playUrl && !playNew && running && player.isPlaying) return
        player.stop()
        manager.cancelProgressJobs()
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

    // ===== AudioPlayBookBridge =====
    // saveRead/save/getBookSource (desktop 无 BookExtensions, 直接调 DAO)

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
}

/**
 * 桌面端注册 AudioPlay 平台 provider (对应 app 端 registerAndroidAudioPlayProviders)。
 *
 * 必须在所有依赖 provider (AppDbProviders / OkHttpClientProviders / JsEngines /
 * SourceHelpAccessors / registerDesktopWebBookProviders) 注册之后调用,
 * 因 manager.loadPlayUrl 经 [io.legado.app.model.webBook.WebBook.getContentAwait]
 * 间接访问 appDb + webBook 编排层 + JS 引擎。
 *
 * 调用时机: desktop Main.kt, 在 registerDesktopWebBookProviders() / registerDesktopJsEngines() 之后。
 */
fun registerDesktopAudioPlayProviders() {
    val impl = DesktopAudioPlayProvider()
    AudioPlayCommanders.register(impl)
    AudioPlayBookBridges.register(impl)
}
