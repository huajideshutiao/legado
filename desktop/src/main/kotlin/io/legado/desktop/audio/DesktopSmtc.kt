package io.legado.desktop.audio

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import io.legado.app.constant.AppLog
import io.legado.app.constant.Status
import io.legado.app.model.AudioPlayShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import io.legado.desktop.audio.DesktopSmtc.init
import io.legado.desktop.audio.DesktopSmtc.update
import io.legado.desktop.ui.tray.DesktopMediaTray
import java.util.concurrent.Executors

/**
 * Windows SMTC (SystemMediaTransportControls) 一期+二期集成:
 * 让 Win11 音量浮层 / 锁屏媒体卡显示 legado 的音频播放 / 朗读状态, 并接收播放控制。
 *
 * # 机制 (无窗口激活路径 A)
 * - `RoActivateInstance("Windows.Media.Playback.MediaPlayer", IID_IMediaPlayer, out)` 免窗口激活
 *   → QI `ISystemMediaTransportControls` (99FA3FF4-1742-42A6-902E-087D41F965EC)
 * - 元数据: DisplayUpdater (Type=Music) → MusicProperties (Title=章节 / Artist=书名 / AlbumArtist=作者)
 *   → ClearAll + Update()
 * - 进度 (二期): TimelineProperties (5125316A-...) 激活后填 StartTime/EndTime/Position (TimeSpan=100ns),
 *   经 ISMTC2 (EA98D2F6-...) 的 UpdateTimelineProperties 上报; 顺带 SetPlaybackRate 显示倍速
 * - 封面 (二期): Uri.CreateUri(url) → RandomAccessStreamReference.CreateFromUri → put_Thumbnail
 *   (仅包装 URL, 不下载图片)
 * - 事件: add_ButtonPressed (播放/暂停/上一首/下一首/停止) + ISMTC2 的
 *   PlaybackPositionChangeRequested (拖动进度条 seek), 回调里转成 legado 命令
 *
 * # 槽位核对 (windows-rs master 生成码 = Win11 SDK metadata, 已逐项核对)
 * - ISMTC: 6/7 get/put_PlaybackStatus; 8 get_DisplayUpdater; 10/11 IsEnabled; 12/13 IsPlayEnabled;
 *   16/17 IsPauseEnabled; 24/25 IsPreviousEnabled; 26/27 IsNextEnabled;
 *   32 add_ButtonPressed(IEventHandler*, out i64); 33 remove_ButtonPressed(i64)
 * - ISMTC2: 10/11 PlaybackRate; 12 UpdateTimelineProperties(IInspectable*); 13 add/14 remove
 *   PlaybackPositionChangeRequested
 * - DisplayUpdater: 6/7 Type; 10/11 Thumbnail; 12 get_MusicProperties; 16 ClearAll; 17 Update
 * - IMusicDisplayProperties (6BBF0C59-...): 6/7 Title; 8/9 AlbumArtist; 10/11 Artist
 * - TimelineProperties: 6/7 StartTime; 8/9 EndTime; 10/11 MinSeekTime; 12/13 MaxSeekTime; 14/15 Position
 * - IUriRuntimeClassFactory (44A9796F-...): 6 CreateUri
 * - IRandomAccessStreamReferenceStatics (857309DC-...): 7 CreateFromUri (槽6 是 CreateFromFile)
 * - 事件 args: ButtonPressedEventArgs (B7F47116-...) 槽6 get_Button;
 *   PlaybackPositionChangeRequestedEventArgs (B4493F88-...) 槽6 get_RequestedPlaybackPosition (i64)
 * - 枚举: PlaybackStatus Closed=0/Changing=1/Stopped=2/Playing=3/Paused=4; Type Music=1;
 *   Button Play=0/Pause=1/Stop=2/Next=6/Previous=7
 *
 * # 线程模型
 * 所有 COM 调用固定跑在单线程 executor (COM 对象线程亲和, STA); ButtonPressed 回调在系统线程
 * 触发, 只读 args 后把按钮值投递回 executor 执行命令。全部 runCatching, 失败仅 AppLog。
 *
 * # 回调 IID 说明
 * IEventHandler 泛型 pinterface IID 的 SHA-1 派生结果无法与任何资料交叉验证 (任务简报给出的
 * 3F5DA159-6388-5B90-B927-FE5C358F34E1 复算不符), 故回调对象 QI 采用宽松实现 (任意 IID 都
 * 返回自身指针) —— 运行时 add_ButtonPressed 会对回调 QI, 宽松 QI 保证注册成功且与具体 IID 值
 * 解耦; 该对象只被事件源当 IEventHandler 使用, 无安全风险。
 */
internal object DesktopSmtc {

    // ==================== 枚举 ====================

    private const val PLAYBACK_CLOSED = 0
    private const val PLAYBACK_CHANGING = 1
    private const val PLAYBACK_STOPPED = 2
    private const val PLAYBACK_PLAYING = 3
    private const val PLAYBACK_PAUSED = 4

    private const val TYPE_MUSIC = 1

    private const val BUTTON_PLAY = 0
    private const val BUTTON_PAUSE = 1
    private const val BUTTON_STOP = 2
    private const val BUTTON_NEXT = 6
    private const val BUTTON_PREVIOUS = 7

    private const val COINIT_APARTMENTTHREADED = 0x2

    // ==================== 运行时类名 ====================

    private const val CLASS_MEDIAPLAYER = "Windows.Media.Playback.MediaPlayer"
    private const val CLASS_TIMELINE_PROPS =
        "Windows.Media.SystemMediaTransportControlsTimelineProperties"
    private const val CLASS_URI = "Windows.Foundation.Uri"
    private const val CLASS_RASR = "Windows.Storage.Streams.RandomAccessStreamReference"

    // ==================== IID (16 字节内存布局: Data1/2/3 小端 + Data4 原序) ====================

    private val IID_MEDIAPLAYER = guidBytes("381A83CB-6FFF-499B-8D64-2885DFC1249E")
    private val IID_IINSPECTABLE = guidBytes("AF86E2E0-B12D-4C6A-9C5A-D7AA65101E90")
    private val IID_ISMTC = guidBytes("99FA3FF4-1742-42A6-902E-087D41F965EC")
    private val IID_ISMTC2 = guidBytes("EA98D2F6-7F3C-4AF2-A586-72889808EFB1")
    private val IID_MUSIC_PROPS = guidBytes("6BBF0C59-D0A0-4D26-92A0-F978E1D18E7B")
    private val IID_BUTTON_ARGS = guidBytes("B7F47116-A56F-4DC8-9E11-92031F4A87C2")
    private val IID_POS_ARGS = guidBytes("B4493F88-EB28-4961-9C14-335E44F3E125")
    private val IID_URI_FACTORY = guidBytes("44A9796F-723E-4FDF-A218-033E75B0C084")
    private val IID_RASR_STATICS = guidBytes("857309DC-3FBF-4E7D-986F-EF3B1A07A964")

    // ==================== vtable 槽位 ====================

    // ISystemMediaTransportControls
    private const val SLOT_GET_PLAYBACK_STATUS = 6
    private const val SLOT_PUT_PLAYBACK_STATUS = 7
    private const val SLOT_GET_DISPLAY_UPDATER = 8
    private const val SLOT_PUT_IS_ENABLED = 11
    private const val SLOT_PUT_IS_PLAY_ENABLED = 13
    private const val SLOT_PUT_IS_PAUSE_ENABLED = 17
    private const val SLOT_PUT_IS_PREV_ENABLED = 25
    private const val SLOT_PUT_IS_NEXT_ENABLED = 27
    private const val SLOT_ADD_BUTTON_PRESSED = 32
    private const val SLOT_REMOVE_BUTTON_PRESSED = 33

    // ISystemMediaTransportControls2
    private const val SLOT_ISMTC2_PUT_PLAYBACK_RATE = 11
    private const val SLOT_ISMTC2_UPDATE_TIMELINE = 12
    private const val SLOT_ISMTC2_ADD_POS_CHANGE = 13
    private const val SLOT_ISMTC2_REMOVE_POS_CHANGE = 14

    // ISystemMediaTransportControlsDisplayUpdater
    private const val SLOT_DU_PUT_TYPE = 7
    private const val SLOT_DU_PUT_THUMBNAIL = 11
    private const val SLOT_DU_GET_MUSIC_PROPERTIES = 12
    private const val SLOT_DU_CLEAR_ALL = 16
    private const val SLOT_DU_UPDATE = 17

    // IMusicDisplayProperties
    private const val SLOT_MUSIC_PUT_TITLE = 7
    private const val SLOT_MUSIC_PUT_ALBUM_ARTIST = 9
    private const val SLOT_MUSIC_PUT_ARTIST = 11

    // ISystemMediaTransportControlsTimelineProperties
    private const val SLOT_TL_PUT_START_TIME = 7
    private const val SLOT_TL_PUT_END_TIME = 9
    private const val SLOT_TL_PUT_MIN_SEEK_TIME = 11
    private const val SLOT_TL_PUT_MAX_SEEK_TIME = 13
    private const val SLOT_TL_PUT_POSITION = 15

    // IUriRuntimeClassFactory / IRandomAccessStreamReferenceStatics
    private const val SLOT_URI_CREATE = 6
    private const val SLOT_RASR_CREATE_FROM_URI = 7

    // 事件 args
    private const val SLOT_ARGS_GET_BUTTON = 6
    private const val SLOT_ARGS_GET_POSITION = 6

    // IUnknown
    private const val SLOT_QUERY_INTERFACE = 0
    private const val SLOT_RELEASE = 2

    private const val S_OK = 0

    /** 0x80004002 超出 Int.MAX, 必须显式转 Int 才能用于 getOrDefault。 */
    private val E_NOINTERFACE: Int = 0x80004002.toInt()

    /** TimeSpan 刻度: 100ns; ms → 刻度 = ms * 10000 */
    private const val TIMESPAN_PER_MS = 10_000L

    // ==================== 状态 (仅 executor 线程访问; 标记跨线程) ====================

    @Volatile
    private var activated = false

    @Volatile
    private var comInitialized = false

    private var smtc: Pointer? = null
    private var smtc2: Pointer? = null
    private var displayUpdater: Pointer? = null
    private var musicProps: Pointer? = null
    private var timelineProps: Pointer? = null
    private var uriFactory: Pointer? = null
    private var rasrStatics: Pointer? = null

    /** add_ButtonPressed 返回的 token (remove 时必须原样传回)。 */
    private var buttonToken: Long = 0L

    /** add_PlaybackPositionChangeRequested 返回的 token。 */
    private var positionToken: Long = 0L

    /** 回调对象 vtable 内存 (强引用防 GC; 运行时可能持引用到 remove 之后)。 */
    private var buttonHandlerVtable: Memory? = null
    private var positionHandlerVtable: Memory? = null

    /** 封面 URL 去重 (避免每次 update 重复走 Uri/RASR)。 */
    private var lastCoverUrl: String? = null

    /** 单线程 executor: 所有 COM 调用固定在此线程 (对象线程亲和)。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "legado-smtc").apply { isDaemon = true }
    }

    // ==================== 公开 API (幂等, 全 runCatching) ====================

    /** 激活 MediaPlayer → QI SMTC → 初始化 DisplayUpdater → 订阅按钮事件 (幂等)。 */
    fun init() {
        if (!Platform.isWindows()) return
        executor.execute {
            runCatching { ensureInit() }.onFailure {
                AppLog.put("SMTC 初始化失败", it)
            }
        }
    }

    /** 推送播放状态/元数据/进度/封面到系统媒体卡 (自动补一次 [init])。 */
    fun update(state: SmtcState) {
        if (!Platform.isWindows()) return
        executor.execute {
            runCatching {
                ensureInit()
                applyState(state)
            }.onFailure {
                AppLog.put("SMTC 状态更新失败", it)
            }
        }
    }

    /** 摘除事件回调 + 释放全部 COM 引用 (幂等; 下次 [init]/[update] 会重新激活)。 */
    fun release() {
        if (!Platform.isWindows()) return
        executor.execute {
            runCatching { doRelease() }.onFailure {
                AppLog.put("SMTC 释放失败", it)
            }
        }
    }

    // ==================== 初始化 ====================

    private fun ensureInit() {
        if (activated) return
        ensureCom()
        val h = hstring(CLASS_MEDIAPLAYER)
        try {
            val out = PointerByReference()
            // 激活路径 A (免窗口): 先按 IMediaPlayer 取, 失败回落 IInspectable
            var hr = combase("RoActivateInstance").invokeInt(arrayOf(h, IID_MEDIAPLAYER, out))
            if (hr != S_OK || out.value == null) {
                hr = combase("RoActivateInstance").invokeInt(arrayOf(h, IID_IINSPECTABLE, out))
            }
            if (hr != S_OK || out.value == null) {
                throw IllegalStateException("RoActivateInstance(MediaPlayer) hr=$hr")
            }
            val mediaPlayer = out.value
            try {
                val smtcRef = PointerByReference()
                hr = vtbl(mediaPlayer, SLOT_QUERY_INTERFACE, IID_ISMTC, smtcRef)
                if (hr != S_OK || smtcRef.value == null) {
                    throw IllegalStateException("QI ISMTC hr=$hr")
                }
                smtc = smtcRef.value

                // DisplayUpdater + MusicProperties
                val duRef = PointerByReference()
                hr = vtbl(smtcRef.value, SLOT_GET_DISPLAY_UPDATER, duRef)
                if (hr == S_OK && duRef.value != null) {
                    displayUpdater = duRef.value
                    val mpRef = PointerByReference()
                    if (vtbl(
                            duRef.value,
                            SLOT_DU_GET_MUSIC_PROPERTIES,
                            mpRef
                        ) == S_OK && mpRef.value != null
                    ) {
                        val propsRef = PointerByReference()
                        if (vtbl(
                                mpRef.value,
                                SLOT_QUERY_INTERFACE,
                                IID_MUSIC_PROPS,
                                propsRef
                            ) == S_OK
                            && propsRef.value != null
                        ) {
                            musicProps = propsRef.value
                        }
                        vtbl(mpRef.value, SLOT_RELEASE)
                    }
                    // 初始 Type=Music
                    vtbl(duRef.value, SLOT_DU_PUT_TYPE, TYPE_MUSIC)
                    vtbl(duRef.value, SLOT_DU_CLEAR_ALL)
                    vtbl(duRef.value, SLOT_DU_UPDATE)
                }

                // ISMTC2 (可选: 老系统可能没有, 只影响进度/倍速/seek)
                val s2Ref = PointerByReference()
                if (vtbl(
                        smtcRef.value,
                        SLOT_QUERY_INTERFACE,
                        IID_ISMTC2,
                        s2Ref
                    ) == S_OK && s2Ref.value != null
                ) {
                    smtc2 = s2Ref.value
                }

                // 订阅 ButtonPressed (回调对象强引用 + 宽松 QI)
                val buttonVt = buildVtable(buttonInvoke)
                buttonHandlerVtable = buttonVt
                val token = LongByReference()
                hr = vtbl(smtcRef.value, SLOT_ADD_BUTTON_PRESSED, buttonVt, token)
                if (hr == S_OK) {
                    buttonToken = token.value
                } else {
                    AppLog.put("SMTC add_ButtonPressed 失败 hr=$hr, 播放控制不可用")
                }

                // 订阅 seek 请求 (ISMTC2)
                smtc2?.let { s2 ->
                    val posVt = buildVtable(positionInvoke)
                    positionHandlerVtable = posVt
                    val posToken = LongByReference()
                    val hr2 = vtbl(s2, SLOT_ISMTC2_ADD_POS_CHANGE, posVt, posToken)
                    if (hr2 == S_OK) {
                        positionToken = posToken.value
                    } else {
                        AppLog.put("SMTC add_PlaybackPositionChangeRequested 失败 hr=$hr2")
                    }
                }

                vtbl(smtcRef.value, SLOT_PUT_IS_ENABLED, 1)
                activated = true
            } finally {
                vtbl(mediaPlayer, SLOT_RELEASE)
            }
        } finally {
            deleteHString(h)
        }
    }

    private fun ensureCom() {
        if (comInitialized) return
        val hr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, COINIT_APARTMENTTHREADED).toInt()
        if (hr == S_OK || hr == 1 /* S_FALSE: 本线程已初始化 */) {
            comInitialized = true
        } else {
            AppLog.put("SMTC CoInitializeEx 失败 hr=$hr")
        }
    }

    // ==================== 状态推送 ====================

    private fun applyState(state: SmtcState) {
        val s = smtc ?: return
        val status = when {
            state.isPlaying -> PLAYBACK_PLAYING
            state.isPaused -> PLAYBACK_PAUSED
            else -> PLAYBACK_STOPPED
        }
        vtbl(s, SLOT_PUT_PLAYBACK_STATUS, status)
        vtbl(s, SLOT_PUT_IS_ENABLED, 1)
        vtbl(s, SLOT_PUT_IS_PLAY_ENABLED, if (state.isPlaying) 0 else 1)
        vtbl(s, SLOT_PUT_IS_PAUSE_ENABLED, if (state.isPlaying) 1 else 0)
        vtbl(s, SLOT_PUT_IS_PREV_ENABLED, if (state.prevNextEnabled) 1 else 0)
        vtbl(s, SLOT_PUT_IS_NEXT_ENABLED, if (state.prevNextEnabled) 1 else 0)

        // 元数据: Title=章节 / Artist=书名 / AlbumArtist=作者 (对照原版 MediaMetadata)
        musicProps?.let { music ->
            putHString(music, SLOT_MUSIC_PUT_TITLE, state.title)
            putHString(music, SLOT_MUSIC_PUT_ARTIST, state.artist)
            if (state.albumArtist.isNotEmpty()) {
                putHString(music, SLOT_MUSIC_PUT_ALBUM_ARTIST, state.albumArtist)
            }
        }

        // 进度 (二期): 有时长才推 TimelineProperties
        if (state.durationMs > 0 && state.positionMs >= 0) {
            applyTimeline(state.positionMs, state.durationMs)
        }

        // 倍速 (ISMTC2.PlaybackRate, 浮层显示语速)
        if (state.playbackRate > 0f) {
            smtc2?.let { vtbl(it, SLOT_ISMTC2_PUT_PLAYBACK_RATE, state.playbackRate.toDouble()) }
        }

        // 封面 (二期): URL → Uri → RandomAccessStreamReference → Thumbnail (不下载图片)
        applyCover(state.coverUrl)

        displayUpdater?.let { vtbl(it, SLOT_DU_UPDATE) }
    }

    private fun applyTimeline(positionMs: Long, durationMs: Long) {
        val s2 = smtc2 ?: return
        val tl = ensureTimelineProps() ?: return
        val end = durationMs * TIMESPAN_PER_MS
        val pos = positionMs.coerceIn(0L, durationMs) * TIMESPAN_PER_MS
        vtbl(tl, SLOT_TL_PUT_START_TIME, 0L)
        vtbl(tl, SLOT_TL_PUT_END_TIME, end)
        vtbl(tl, SLOT_TL_PUT_MIN_SEEK_TIME, 0L)
        vtbl(tl, SLOT_TL_PUT_MAX_SEEK_TIME, end)
        vtbl(tl, SLOT_TL_PUT_POSITION, pos)
        vtbl(s2, SLOT_ISMTC2_UPDATE_TIMELINE, tl)
    }

    private fun ensureTimelineProps(): Pointer? {
        timelineProps?.let { return it }
        val h = hstring(CLASS_TIMELINE_PROPS)
        try {
            val out = PointerByReference()
            val hr = combase("RoActivateInstance").invokeInt(arrayOf(h, IID_IINSPECTABLE, out))
            if (hr == S_OK && out.value != null) {
                timelineProps = out.value
            }
        } finally {
            deleteHString(h)
        }
        return timelineProps
    }

    /** 封面: 只把 URL 包装成 RandomAccessStreamReference 交给系统 (系统按需取流)。 */
    private fun applyCover(url: String?) {
        if (url == lastCoverUrl) return
        lastCoverUrl = url
        if (url.isNullOrBlank()) return
        val du = displayUpdater ?: return
        val factory = uriFactory ?: RoGetActivationFactory(CLASS_URI, IID_URI_FACTORY)?.also {
            uriFactory = it
        }
        ?: return
        val uri = PointerByReference()
        val h = hstring(url)
        var hr = try {
            vtbl(factory, SLOT_URI_CREATE, h, uri)
        } finally {
            deleteHString(h)
        }
        if (hr != S_OK || uri.value == null) {
            AppLog.put("SMTC Uri 创建失败 hr=$hr")
            return
        }
        try {
            val statics =
                rasrStatics ?: RoGetActivationFactory(CLASS_RASR, IID_RASR_STATICS)?.also {
                    rasrStatics = it
                } ?: return
            val stream = PointerByReference()
            hr = vtbl(statics, SLOT_RASR_CREATE_FROM_URI, uri.value, stream)
            if (hr == S_OK && stream.value != null) {
                try {
                    vtbl(du, SLOT_DU_PUT_THUMBNAIL, stream.value)
                } finally {
                    vtbl(stream.value, SLOT_RELEASE)
                }
            } else {
                AppLog.put("SMTC CreateFromUri 失败 hr=$hr")
            }
        } finally {
            vtbl(uri.value, SLOT_RELEASE)
        }
    }

    // ==================== 释放 ====================

    private fun doRelease() {
        if (!activated) return
        val s = smtc
        if (s != null) {
            if (buttonToken != 0L) {
                runCatching { vtbl(s, SLOT_REMOVE_BUTTON_PRESSED, buttonToken) }
                buttonToken = 0L
            }
            val s2 = smtc2
            if (s2 != null && positionToken != 0L) {
                runCatching { vtbl(s2, SLOT_ISMTC2_REMOVE_POS_CHANGE, positionToken) }
                positionToken = 0L
            }
        }
        listOfNotNull(
            timelineProps,
            smtc2,
            musicProps,
            displayUpdater,
            smtc,
            uriFactory,
            rasrStatics
        )
            .forEach { runCatching { vtbl(it, SLOT_RELEASE) } }
        timelineProps = null
        smtc2 = null
        musicProps = null
        displayUpdater = null
        smtc = null
        uriFactory = null
        rasrStatics = null
        buttonHandlerVtable = null
        positionHandlerVtable = null
        lastCoverUrl = null
        activated = false
    }

    // ==================== 命令分发 (对照托盘 DesktopTaskbarMedia 的优先级: 音频优先, 否则朗读) ====================

    private fun dispatchButton(button: Int) {
        val audioActive = AudioPlayShared.status != Status.STOP
        val aloud = DesktopMediaTray.readAloud
        val aloudActive = aloud?.controller?.state?.value?.let {
            it == ReadAloudState.PLAYING || it == ReadAloudState.PAUSED
        } ?: false
        when (button) {
            BUTTON_PLAY -> when {
                audioActive -> when (AudioPlayShared.status) {
                    Status.PAUSE -> AudioPlayShared.resume()
                    Status.PLAY, Status.LOADING -> Unit
                    else -> AudioPlayShared.loadOrUpPlayUrl()
                }

                aloudActive -> if (aloud.controller.state.value == ReadAloudState.PAUSED) {
                    aloud.controller.resume()
                }
            }

            BUTTON_PAUSE -> when {
                audioActive -> if (AudioPlayShared.status == Status.PLAY) AudioPlayShared.pause()
                aloudActive -> if (aloud.controller.state.value == ReadAloudState.PLAYING) {
                    aloud.controller.pause()
                }
            }

            BUTTON_NEXT -> if (audioActive) AudioPlayShared.next()
            else if (aloudActive) aloud.controller.nextParagraph()

            BUTTON_PREVIOUS -> if (audioActive) AudioPlayShared.prev()
            else if (aloudActive) aloud.controller.prevParagraph()

            BUTTON_STOP -> if (audioActive) AudioPlayShared.stop()
            else if (aloudActive) aloud.controller.stop()
        }
    }

    // ==================== IEventHandler 回调 (vtable 手写) ====================

    private interface HandlerQi : Callback {
        fun qi(thisPtr: Pointer, iid: Pointer, ppv: Pointer): Int
    }

    private interface HandlerAddRef : Callback {
        fun addRef(thisPtr: Pointer): Int
    }

    private interface HandlerRelease : Callback {
        fun release(thisPtr: Pointer): Int
    }

    private interface HandlerInvoke : Callback {
        fun invoke(thisPtr: Pointer, sender: Pointer, args: Pointer): Int
    }

    /** 宽松 QI: 任意 IID 都返回自身指针 (pinterface IID 无法可靠复算, 见类注释)。 */
    private val handlerQi = object : HandlerQi {
        override fun qi(thisPtr: Pointer, iid: Pointer, ppv: Pointer): Int = runCatching {
            ppv.setPointer(0, thisPtr)
            S_OK
        }.getOrDefault(E_NOINTERFACE)
    }

    /** 引用计数仅做形式 (对象生命周期 = 进程生命周期, 从不真正释放)。 */
    private val handlerAddRef = object : HandlerAddRef {
        override fun addRef(thisPtr: Pointer): Int = 2
    }

    private val handlerRelease = object : HandlerRelease {
        override fun release(thisPtr: Pointer): Int = 1
    }

    /** ButtonPressed: 读按钮值 → 投递到 executor 执行命令 (回调在系统线程, 不做 COM 长调用)。 */
    private val buttonInvoke = object : HandlerInvoke {
        override fun invoke(thisPtr: Pointer, sender: Pointer, args: Pointer): Int {
            val button = readArgsInt(args, IID_BUTTON_ARGS, SLOT_ARGS_GET_BUTTON)
            if (button != null) {
                executor.execute {
                    runCatching { dispatchButton(button) }.onFailure {
                        AppLog.put("SMTC 按钮命令执行失败", it)
                    }
                }
            }
            return S_OK
        }
    }

    /** PlaybackPositionChangeRequested: 100ns → ms → AudioPlayShared.adjustProgress。 */
    private val positionInvoke = object : HandlerInvoke {
        override fun invoke(thisPtr: Pointer, sender: Pointer, args: Pointer): Int {
            val pos100ns = readArgsLong(args, IID_POS_ARGS, SLOT_ARGS_GET_POSITION)
            if (pos100ns != null) {
                val ms = pos100ns / TIMESPAN_PER_MS
                executor.execute {
                    runCatching {
                        if (AudioPlayShared.status != Status.STOP) {
                            AudioPlayShared.adjustProgress(ms.toInt())
                        }
                    }.onFailure {
                        AppLog.put("SMTC seek 命令执行失败", it)
                    }
                }
            }
            return S_OK
        }
    }

    /** 构建 4 槽 vtable (QI/AddRef/Release/Invoke), 返回对象内存 (首字段即 vtable 指针)。 */
    private fun buildVtable(invoke: HandlerInvoke): Memory {
        val vt = Memory(4L * Native.POINTER_SIZE)
        vt.setPointer(0, CallbackReference.getFunctionPointer(handlerQi))
        vt.setPointer(
            Native.POINTER_SIZE.toLong(),
            CallbackReference.getFunctionPointer(handlerAddRef)
        )
        vt.setPointer(
            2L * Native.POINTER_SIZE,
            CallbackReference.getFunctionPointer(handlerRelease)
        )
        vt.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(invoke))
        return vt
    }

    /** QI args 对象 → 槽 [slot] 读 int → Release。 */
    private fun readArgsInt(args: Pointer, iid: Memory, slot: Int): Int? = runCatching {
        val ppv = PointerByReference()
        if (vtbl(args, SLOT_QUERY_INTERFACE, iid, ppv) != S_OK || ppv.value == null) return null
        try {
            val value = IntByReference()
            if (vtbl(ppv.value, slot, value) != S_OK) return null
            value.value
        } finally {
            vtbl(ppv.value, SLOT_RELEASE)
        }
    }.getOrNull()

    /** QI args 对象 → 槽 [slot] 读 long (TimeSpan)。 */
    private fun readArgsLong(args: Pointer, iid: Memory, slot: Int): Long? = runCatching {
        val ppv = PointerByReference()
        if (vtbl(args, SLOT_QUERY_INTERFACE, iid, ppv) != S_OK || ppv.value == null) return null
        try {
            val value = LongByReference()
            if (vtbl(ppv.value, slot, value) != S_OK) return null
            value.value
        } finally {
            vtbl(ppv.value, SLOT_RELEASE)
        }
    }.getOrNull()

    // ==================== COM 基础设施 ====================

    /** 按 vtable 序号调用 COM 方法 (同 WindowsFileDialogs.vtbl / DesktopTaskbarMedia.vtbl)。 */
    private fun vtbl(target: Pointer, index: Int, vararg args: Any?): Int {
        val vtable = target.getPointer(0)
        val method = vtable.getPointer(index.toLong() * Native.POINTER_SIZE)
        return Function.getFunction(method, Function.ALT_CONVENTION)
            .invokeInt(arrayOf(target, *args))
    }

    private fun combase(name: String): Function =
        Function.getFunction("combase", name, Function.ALT_CONVENTION)

    private fun hstring(value: String): Pointer {
        val mem = Memory((value.length + 1) * 2L)
        mem.setWideString(0, value)
        val out = PointerByReference()
        val hr = combase("WindowsCreateString").invokeInt(arrayOf(mem, value.length, out))
        if (hr != S_OK || out.value == null) {
            throw IllegalStateException("WindowsCreateString hr=$hr")
        }
        return out.value
    }

    private fun deleteHString(h: Pointer) {
        runCatching { combase("WindowsDeleteString").invokeInt(arrayOf(h)) }
    }

    private fun putHString(target: Pointer, slot: Int, value: String) {
        val h = hstring(value)
        try {
            val hr = vtbl(target, slot, h)
            if (hr != S_OK) {
                throw IllegalStateException("put HSTRING slot=$slot hr=$hr")
            }
        } finally {
            deleteHString(h)
        }
    }

    private fun RoGetActivationFactory(className: String, iid: Memory): Pointer? {
        val h = hstring(className)
        try {
            val out = PointerByReference()
            val hr = combase("RoGetActivationFactory").invokeInt(arrayOf(h, iid, out))
            if (hr != S_OK || out.value == null) {
                AppLog.put("SMTC RoGetActivationFactory($className) 失败 hr=$hr")
                return null
            }
            return out.value
        } finally {
            deleteHString(h)
        }
    }

    /** "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX" → 16 字节 COM GUID 内存布局 (Data1/2/3 小端)。 */
    private fun guidBytes(s: String): Memory {
        val g = s.replace("-", "")
        val mem = Memory(16)
        mem.setInt(0, g.substring(0, 8).toInt(16))
        mem.setShort(4, g.substring(8, 12).toInt(16).toShort())
        mem.setShort(6, g.substring(12, 16).toInt(16).toShort())
        for (i in 0 until 8) {
            mem.setByte(8L + i, g.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte())
        }
        return mem
    }
}

/**
 * SMTC 媒体卡状态快照 (由音频 / 朗读调用方各自填充简单值, 与托盘同源)。
 *
 * @param title 章节名 (Title)
 * @param artist 书名 (Artist)
 * @param albumArtist 作者 (AlbumArtist, 对照原版 MediaMetadata 的 ALBUM=作者)
 * @param isPlaying 播放中 (PlaybackStatus=Playing, 启用暂停按钮)
 * @param isPaused 暂停中 (PlaybackStatus=Paused)
 * @param prevNextEnabled 是否启用上一首/下一首按钮
 * @param positionMs 当前进度 (毫秒; <0 表示不推进度, 朗读无进度概念)
 * @param durationMs 总时长 (毫秒; <=0 表示不推进度)
 * @param playbackRate 倍速 (>0 时写 ISMTC2.PlaybackRate; 0 表示不写)
 * @param coverUrl 封面 URL (仅音频; 朗读无封面)
 */
data class SmtcState(
    val title: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val prevNextEnabled: Boolean = true,
    val positionMs: Long = -1L,
    val durationMs: Long = -1L,
    val playbackRate: Float = 1f,
    val coverUrl: String? = null,
)
