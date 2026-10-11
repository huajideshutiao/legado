@file:Suppress("DEPRECATION")

package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.media.AudioFocusController
import io.legado.app.help.media.BecomingNoisyReceiver
import io.legado.app.help.media.MediaPlaybackLock
import io.legado.app.help.media.MediaPlaybackNotification
import io.legado.app.help.media.PhoneCallPauseHandler
import io.legado.app.help.media.PhoneCallState
import io.legado.app.help.media.SleepTimer
import io.legado.app.help.tts.ReadAloudQueue
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.ActiveReadAloudHostPorts
import io.legado.app.model.BookCover
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBookShared
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.notificationManager
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.telephonyManager
import io.legado.app.ui.book.read.page.entities.TextChapterShared
import io.legado.app.ui.book.read.page.entities.getNeedReadAloud
import io.legado.app.ui.book.read.page.entities.title
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.LogUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import io.legado.app.utils.observeSharedPreferences
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 朗读服务
 */
abstract class BaseReadAloudService : BaseService() {

    companion object {
        @JvmStatic
        val isRun: Boolean get() = _isRunFlow.value

        @JvmStatic
        var pause = true
            private set

        /** 朗读服务运行态状态流: [isRun] 的唯一来源, 重放当前值, 等待朗读结束的收尾源 */
        private val _isRunFlow = MutableStateFlow(false)

        @JvmStatic
        val isRunFlow: StateFlow<Boolean> = _isRunFlow.asStateFlow()

        @JvmStatic
        val timeMinute: Int
            get() = sleepTimer?.minutes ?: 0

        @JvmStatic
        private var sleepTimer: SleepTimer? = null

        fun isPlay(): Boolean = isRun && !pause

        private const val TAG = "BaseReadAloudService"
    }

    private val playbackLock by lazy {
        MediaPlaybackLock(
            tag = "legado:ReadAloudService",
            enabled = AppConfig.readAloudWakeLock
        )
    }
    private val audioFocus by lazy {
        AudioFocusController(
            logTag = "TTS",
            isPaused = { pause },
            onPause = { abandon -> pauseReadAloud(abandon) },
            onResume = { resumeReadAloud() }
        )
    }
    private val noisyReceiver = BecomingNoisyReceiver { pauseReadAloud() }
    private val mediaSessionCompat: MediaSessionCompat by lazy {
        MediaSessionCompat(this, "readAloud")
    }
    private val phoneStateListener by lazy {
        ReadAloudPhoneStateListener()
    }

    /** Android 只实现播放端口；业务队列和推进统一复用 shared 控制器。 */
    internal val readAloudController by lazy {
        ReadAloudControllerShared(
            chapterData = androidChapterData,
            chapterNavigation = ActiveReadAloudHostPorts.chapterNavigation,
            playbackPort = object : ReadAloudPlaybackPort {
                override fun onPlay(text: String, paragraphIndex: Int, playbackToken: Long) {
                    currentPlaybackText = text
                    currentPlaybackToken = playbackToken
                    lifecycleScope.launch(Main) { play() }
                }

                override fun onStop() = playStop()
                override fun onPause() = playStop()
                override fun onResume(): Boolean = false
                override fun onSpeechRateChanged(rate: Float) = upSpeechRate(false)
            },
        )
    }

    internal val readBook: ReadBookShared? get() = ActiveReadAloudHostPorts.currentReadBook
    internal var currentPlaybackText: String = ""
        private set
    internal var currentPlaybackToken: Long = 0L
        private set

    internal var textChapter: TextChapterShared? = null

    /**
     * 来电三态状态机 (日志文案/恢复标记语义与下沉前逐字一致)。
     *
     * 平台监听器的注册/注销仍由本类负责 (系统权限与生命周期是 Android 专属)。
     */
    private val phoneCallHandler = PhoneCallPauseHandler(
        isPaused = { pause },
        onPause = { pauseReadAloud() },
        onResume = { resumeReadAloud() },
    )
    private var registeredPhoneStateListener = false
    private var upNotificationJob: Coroutine<*>? = null
    private var chapterWatchJob: Job? = null
    private var cover: Bitmap = BookCover.notificationDefaultCover

    /** 通知标题用书名; 阅读实例摘除后实时取不到, 随每次通知刷新续缓存 */

    /** 上一次发出的通知快照,用于跳过无变化的 rebuild。 */
    private data class NotificationSnapshot(
        val pause: Boolean,
        val sleepMin: Int,
        val bookName: String?,
        val chapterTitle: String?,
    )

    private var lastNotificationSnapshot: NotificationSnapshot? = null
    private var lastNotificationCover: Bitmap? = null

    var pageChanged = false
    var readAloudByPage = false
        private set

    private var requestedChapterPosition = 0

    private val androidChapterData = object : ReadAloudChapterDataPort {
        override val chapterCount: Int get() = readBook?.simulatedChapterSize ?: 0

        override fun loadChapterPlan(
            chapterIndex: Int,
            chapterPosition: Int,
            fromLastSpeakable: Boolean,
        ): ReadAloudChapterPlan? {
            val chapter = readBook?.curTextChapter?.value
                ?.takeIf { it.chapterIndex == chapterIndex && it.pages.isNotEmpty() && !it.pages.first().isMsgPage }
                ?: return null
            textChapter = chapter
            readAloudByPage = AppConfig.readAloudByPage
            val paragraphs =
                ReadAloudQueue.splitParagraphs(chapter.getNeedReadAloud(0, readAloudByPage, 0))
            if (paragraphs.isEmpty()) return null
            val target = chapterPosition.coerceAtLeast(0)
            var offset = 0
            var index = 0
            if (fromLastSpeakable) {
                index =
                    paragraphs.indexOfLast { !it.matches(io.legado.app.constant.AppPattern.notReadAloudRegex) }
                if (index < 0) return null
                offset = paragraphs.take(index).sumOf { it.length + 1 }
            } else {
                while (index + 1 < paragraphs.size && offset + paragraphs[index].length + 1 <= target) {
                    offset += paragraphs[index].length + 1
                    index++
                }
            }
            return ReadAloudChapterPlan(
                paragraphs = paragraphs,
                paragraphIndex = index,
                paragraphOffset = if (fromLastSpeakable) 0 else
                    (target - offset).coerceIn(0, paragraphs[index].length),
                // Android 计划的位置必须是请求目标；段起点另由 paragraphOffset 描述。
                chapterPosition = if (fromLastSpeakable) offset else target,
            )
        }
    }

    @CallSuper
    override fun onCreate() {
        super.onCreate()
        // 系统杀进程后重启服务 (无启动意图) 时没有任何可朗读实例: 直接退出, 不挂假"正在朗读"通知
        if (readBook == null) {
            stopSelf()
            return
        }
        _isRunFlow.value = true
        pause = false
        // 控制器读完末章只置终态, 段末推进不经过本服务: 跟随终态收服务, 通知与运行态一起收束
        // (对照原版 nextChapter 里"没有下一章就 stopSelf")
        lifecycleScope.launch {
            readAloudController.state.collectLatest { state ->
                if (state == ReadAloudControllerShared.ReadAloudState.COMPLETED) stopSelf()
            }
        }
        sleepTimer = SleepTimer(
            scope = lifecycleScope,
            postMinute = { ActiveReadAloudHostPorts.positionPublisher.publishTimer(it) },
            isPaused = { pause },
            onTimeout = { ReadAloud.stop(this) },
            onTick = { upReadAloudNotification() }
        )
        observeLiveBus()
        initMediaSession()
        noisyReceiver.register(this)
        initPhoneStateListener()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        val book = readBook?.bookValue
        ReadTimeRecorder.start(ReadTimeRecorder.Source.READ_ALOUD, book?.name ?: "")
        if (AppConfig.ttsTimer > 0) {
            sleepTimer?.set(AppConfig.ttsTimer)
            toastOnUi("朗读定时 ${AppConfig.ttsTimer} 分钟")
        }
        BookCover.loadNotificationCover(this, book?.getDisplayCover(), lifecycleScope) {
            cover = it
            upReadAloudNotification()
        }
    }

    private fun observeLiveBus() {
        observeSharedPreferences { _, key ->
            when (key) {
                PreferKey.ignoreAudioFocus,
                PreferKey.pauseReadAloudWhilePhoneCalls -> initPhoneStateListener()
            }
        }
    }

    @CallSuper
    override fun onDestroy() {
        super.onDestroy()
        playbackLock.release()
        _isRunFlow.value = false
        pause = true
        ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.READ_ALOUD)
        audioFocus.abandon()
        noisyReceiver.unregister(this)
        sleepTimer?.cancel()
        sleepTimer = null
        chapterWatchJob?.cancel()
        chapterWatchJob = null
        readAloudController.stop()
        ActiveReadAloudHostPorts.positionPublisher.publishState(Status.STOP)
        notificationManager.cancel(NotificationId.ReadAloudService)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        mediaSessionCompat.release()
        ActiveReadAloudHostPorts.uploadProgress()
        unregisterPhoneStateListener(phoneStateListener)
        upNotificationJob?.invokeOnCompletion {
            notificationManager.cancel(NotificationId.ReadAloudService)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && readBook == null) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        when (intent?.action) {
            IntentAction.play -> startReadAloud(
                intent.getBooleanExtra("play", true),
                intent.getIntExtra("pageIndex", readBook?.durPageIndexValue ?: 0),
                intent.getIntExtra("startPos", 0)
            )

            IntentAction.pause -> pauseReadAloud()
            IntentAction.resume -> resumeReadAloud()
            IntentAction.upTtsSpeechRate -> upSpeechRate(true)
            IntentAction.prevParagraph -> prevP()
            IntentAction.nextParagraph -> nextP()
            IntentAction.prev -> prevChapter()
            IntentAction.next -> nextChapter()
            IntentAction.addTimer -> sleepTimer?.add()
            IntentAction.setTimer -> sleepTimer?.set(intent.getIntExtra("minute", 0))
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startReadAloud(play: Boolean, pageIndex: Int, startPos: Int) {
        if (chapterWatchJob?.isActive != true) armChapterWatch()
        val chapter = readBook?.curTextChapter?.value
        val chapterIndex = chapter?.chapterIndex ?: readBook?.durChapterIndexValue ?: return
        val startPageIndex = pageIndex.coerceIn(0, chapter?.lastIndex?.coerceAtLeast(0) ?: 0)
        requestedChapterPosition = if (chapter != null) {
            chapter.getReadLength(startPageIndex) + startPos
        } else {
            (readBook?.durChapterPosValue ?: 0) + startPos
        }.coerceAtLeast(0)
        if (play) {
            pageChanged = false
            readAloudController.start(chapterIndex, requestedChapterPosition)
        } else {
            pageChanged = true
        }
    }

    /**
     * 盯活动阅读页的当前章排版产物: 换章 / 重排 / 阅读页重建后按新章重建朗读队列。
     *
     * 对照原版 `ReadBookShared.curPageChanged` → `readAloud(!isReadAloudPause)` 那条回环:
     * Compose 阅读页切章走 ViewModel, 不经 curPageChanged, 故由本服务自己盯 StateFlow。
     */
    private fun armChapterWatch() {
        chapterWatchJob?.cancel()
        chapterWatchJob = lifecycleScope.launch {
            ActiveReadAloudHostPorts.chapterUpdates.collectLatest { update ->
                val error = update.error
                if (error != null) {
                    readAloudController.reportError(error)
                } else if (update.ready &&
                    readAloudController.state.value == ReadAloudControllerShared.ReadAloudState.WAITING &&
                    update.chapterIndex == readAloudController.chapterIndex.value
                ) {
                    readAloudController.retryWaiting(update.chapterIndex)
                }
            }
        }
    }

    open fun play() {
        playbackLock.acquire()
        _isRunFlow.value = true
        pause = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        ActiveReadAloudHostPorts.positionPublisher.publishState(Status.PLAY)
    }

    abstract fun playStop()

    @CallSuper
    open fun pauseReadAloud(abandonFocus: Boolean = true) {
        readAloudController.pause()
        playbackLock.release()
        pause = readAloudController.state.value != ReadAloudControllerShared.ReadAloudState.PLAYING
        ReadTimeRecorder.end(ReadTimeRecorder.Source.READ_ALOUD)
        if (abandonFocus) audioFocus.abandon()
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        ActiveReadAloudHostPorts.positionPublisher.publishState(Status.PAUSE)
        ActiveReadAloudHostPorts.uploadProgress()
    }

    @CallSuper
    open fun resumeReadAloud() {
        if (pageChanged) {
            val chapterIndex = readBook?.durChapterIndexValue ?: return
            pageChanged = false
            readAloudController.start(chapterIndex, requestedChapterPosition)
        } else if (readAloudController.state.value == ReadAloudControllerShared.ReadAloudState.PAUSED) {
            readAloudController.resume()
        }
        if (readAloudController.state.value == ReadAloudControllerShared.ReadAloudState.PLAYING) {
            resumeReadAloudInternal()
        }
    }

    private fun resumeReadAloudInternal() {
        pause = false
        ReadTimeRecorder.start(ReadTimeRecorder.Source.READ_ALOUD, readBook?.bookValue?.name ?: "")
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        ActiveReadAloudHostPorts.positionPublisher.publishState(Status.PLAY)
    }

    /** 播放端确认真正恢复后由子类调用；controller 是唯一 pause/resume 状态机。 */
    protected fun onPlaybackResumed() = resumeReadAloudInternal()

    abstract fun upSpeechRate(reset: Boolean = false)

    fun upTtsProgress(progress: Int) {
        ActiveReadAloudHostPorts.positionPublisher.publishPosition(progress)
    }

    private fun prevP() = readAloudController.prevParagraph()

    private fun nextP() = readAloudController.nextParagraph()

    /**
     * 请求音频焦点。失败时暂停并 toast。
     */
    fun requestFocus(): Boolean {
        val granted = audioFocus.request()
        if (!granted) {
            // 保持 controller 的 PAUSED/pending 语义，恢复时必须重新走完整 play/prepare。
            pauseReadAloud(false)
            toastOnUi("未获取到音频焦点")
        }
        return granted
    }

    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MediaHelp.MEDIA_SESSION_ACTIONS)
                .setState(state, readAloudController.paragraphIndex.value.toLong(), 1f)
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        "ACTION_ADD_TIMER",
                        androidAppString("set_timer"),
                        R.drawable.ic_time_add_24dp
                    ).build()
                )
                .build()
        )
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = resumeReadAloud()
            override fun onPause() = pauseReadAloud()
            override fun onSkipToNext() {
                if (AppConfig.mediaButtonPerNext) nextChapter() else nextP()
            }
            override fun onSkipToPrevious() {
                if (AppConfig.mediaButtonPerNext) prevChapter() else prevP()
            }

            override fun onStop() {
                stopSelf()
            }
            override fun onCustomAction(action: String, extras: Bundle?) {
                if (action == "ACTION_ADD_TIMER") sleepTimer?.add()
            }
        })
        mediaSessionCompat.setMediaButtonReceiver(
            broadcastPendingIntent<MediaButtonReceiver>(Intent.ACTION_MEDIA_BUTTON)
        )
        mediaSessionCompat.isActive = true
    }

    private fun upReadAloudNotification() {
        val snapshot = NotificationSnapshot(
            pause = pause,
            sleepMin = sleepTimer?.minutes ?: 0,
            bookName = readBook?.bookValue?.name,
            chapterTitle = readBook?.curTextChapter?.value?.title,
        )
        if (snapshot == lastNotificationSnapshot && lastNotificationCover === cover) return
        lastNotificationSnapshot = snapshot
        lastNotificationCover = cover
        upNotificationJob?.cancel()
        upNotificationJob = execute {
            try {
                val notification = createNotification()
                notificationManager.notify(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    private fun createNotification(): NotificationCompat.Builder {
        val current = sleepTimer?.minutes ?: 0
        val title = when {
            pause -> androidAppString("read_aloud_pause")
            current > 0 -> androidAppString("read_aloud_timer", current)
            else -> androidAppString("read_aloud_t")
        } + ": ${readBook?.bookValue?.name.orEmpty()}"
        val subtitle = readBook?.curTextChapter?.value?.title?.takeUnless { it.isBlank() }
            ?: androidAppString("read_aloud_s")
        val playPause = if (pause) {
            MediaPlaybackNotification.Action(
                R.drawable.ic_play_24dp,
                androidAppString("resume"),
                aloudServicePendingIntent(IntentAction.resume)
            )
        } else {
            MediaPlaybackNotification.Action(
                R.drawable.ic_pause_24dp,
                androidAppString("pause"),
                aloudServicePendingIntent(IntentAction.pause)
            )
        }
        // fix #4090: android 14 lock screen 媒体控件需要在 MediaStyle 上挂 session token
        val sessionToken = if (AppConfig.systemMediaControlCompatibilityChange) {
            mediaSessionCompat.sessionToken
        } else null
        return MediaPlaybackNotification.build(
            context = this,
            channelId = AppConst.channelIdReadAloud,
            title = title,
            subtitle = subtitle,
            cover = cover,
            // 路由 extra 经 MainActivity → NavigateTo("last_read") 打开最近阅读书籍
            // 用独立 action (IntentAction.activityReadAloud) 区分 PendingIntent 身份,
            // 避免 FLAG_UPDATE_CURRENT 下多个通知坍缩为同一 PendingIntent 互相覆盖 extras
            // (origin 音频/朗读分别指向 AudioPlayActivity/ReadBookActivity 天然隔离)
            contentIntent = activityPendingIntent<MainActivity>(IntentAction.activityReadAloud) {
                putExtra("route", "last_read")
            },
            actions = listOf(
                MediaPlaybackNotification.Action(
                    R.drawable.ic_time_add_24dp,
                    androidAppString("set_timer"),
                    aloudServicePendingIntent(IntentAction.addTimer)
                ),
                MediaPlaybackNotification.Action(
                    R.drawable.ic_skip_previous,
                    androidAppString("previous_chapter"),
                    aloudServicePendingIntent(IntentAction.prev)
                ),
                playPause,
                MediaPlaybackNotification.Action(
                    R.drawable.ic_skip_next,
                    androidAppString("next_chapter"),
                    aloudServicePendingIntent(IntentAction.next)
                ),
                MediaPlaybackNotification.Action(
                    R.drawable.ic_stop_black_24dp,
                    androidAppString("stop"),
                    aloudServicePendingIntent(IntentAction.stop)
                ),
            ),
            compactActionIndices = intArrayOf(1, 2, 3),
            sessionToken = sessionToken,
            subText = androidAppString("read_aloud"),
            category = NotificationCompat.CATEGORY_TRANSPORT,
            foregroundBehavior = NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE,
        )
    }

    override fun startForegroundNotification() {
        // 挂前台通知的决策点: 没有朗读实例就没有可播内容, 不挂"正在朗读"假通知
        // (进程重启后权限授予回调也会走到这里, 不能只在 onCreate/onStartCommand 把关)
        if (readBook == null) {
            stopSelf()
            return
        }
        execute {
            try {
                val notification = createNotification()
                startForeground(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
                //创建通知出错不结束服务就会崩溃,服务必须绑定通知
                stopSelf()
            }
        }
    }

    abstract fun aloudServicePendingIntent(actionStr: String): PendingIntent?

    open fun prevChapter() {
        ReadTimeRecorder.flushAll()
        resumeReadAloudInternal()
        readAloudController.prevChapter()
    }

    open fun nextChapter() {
        AppLog.putDebug("${readBook?.curTextChapter?.value?.title} 朗读结束跳转下一章并朗读")
        ReadTimeRecorder.flushAll()
        resumeReadAloudInternal()
        readAloudController.nextChapter()
        if (readAloudController.state.value == ReadAloudControllerShared.ReadAloudState.COMPLETED) stopSelf()
    }

    private fun initPhoneStateListener() {
        val needRegister = AppConfig.ignoreAudioFocus && AppConfig.pauseReadAloudWhilePhoneCalls
        if (needRegister && registeredPhoneStateListener) return
        if (needRegister) registerPhoneStateListener(phoneStateListener)
        else unregisterPhoneStateListener(phoneStateListener)
    }

    private fun unregisterPhoneStateListener(l: PhoneStateListener) {
        if (registeredPhoneStateListener) {
            withReadPhoneStatePermission {
                telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE)
                registeredPhoneStateListener = false
            }
        }
    }

    private fun registerPhoneStateListener(l: PhoneStateListener) {
        withReadPhoneStatePermission {
            telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            registeredPhoneStateListener = true
        }
    }

    private fun withReadPhoneStatePermission(block: () -> Unit) {
        try {
            block.invoke()
        } catch (_: SecurityException) {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.READ_PHONE_STATE)
                .rationale(androidAppString("read_aloud_read_phone_state_permission_rationale"))
                .onGranted {
                    try {
                        block.invoke()
                    } catch (_: SecurityException) {
                        LogUtils.d(TAG, "Grant read phone state permission fail.")
                    }
                }
                .request()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    inner class ReadAloudPhoneStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            phoneCallHandler.onCallStateChanged(
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> PhoneCallState.Idle
                    TelephonyManager.CALL_STATE_RINGING -> PhoneCallState.Ringing
                    else -> PhoneCallState.OffHook
                }
            )
        }
    }

}
