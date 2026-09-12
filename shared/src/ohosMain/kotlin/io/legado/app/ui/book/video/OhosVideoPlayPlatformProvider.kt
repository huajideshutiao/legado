package io.legado.app.ui.book.video

import kotlin.concurrent.Volatile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.ArkUIView2
import androidx.compose.ui.napi.js
import androidx.compose.runtime.remember
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/**
 * 鸿蒙端 [VideoPlayPlatformProvider]: 控制器经 napi Media 桥操作 AVPlayer (playerId "videoBook"),
 * 画面经 CPF [ArkUIView2] interop 混排 ArkTS XComponent surface。
 *
 * 控制器复用 [OhosNativeBridge] 的 media tsfn + @CName legado_media_event 事件回调,
 * 与 OhosAudioPlayCommander ("audioBook") / OhosHttpTtsPlayer ("httpTts") 各持独立 AVPlayer 实例。
 */
object OhosVideoPlayPlatformProvider : VideoPlayPlatformProvider {

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController = OhosVideoPlayerController(screenModel, onPlaybackEnded)

    /**
     * 画面渲染: 经 CPF interop 把 ArkTS 的 XComponent(type:'surface') 混排进 Compose 层级。
     *
     * ArkTS 侧需以 [ARKUI_BUILDER_VIDEO_SURFACE] 为 key 调 `registerComposeInteropBuilder`
     * 注册一个 @Builder, 内部 `XComponent({type:'surface'}).onLoad{}` 取到 surfaceId 后
     * 交给 MediaBridgeHandler 上对应 playerId 的 AVPlayer (`player.surfaceId = id`)。
     * 播控命令仍走既有 media tsfn 通道, 与本视图无耦合。
     *
     * interactive=false: 触摸留给 Compose 控件层 (进度条/手势), ArkUI 侧不参与触摸测试。
     */
    @Composable
    override fun RenderSurface(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    ) {
        val videoUrl by screenModel.shared.videoUrl.collectAsState()
        val url = videoUrl?.url
        val ohosController = controller as? OhosVideoPlayerController

        LaunchedEffect(url) {
            if (url != null) {
                ohosController?.loadUrl(url)
            } else {
                // 切章/刷新把源置 null 只是"没有新源"的数据状态, 不是命令:
                // 不显式 stop 的话上一章画面与声音会一直播到新章解析完
                ohosController?.stop()
            }
        }

        // 无源时不挂 XComponent: AVPlayer 渲染到的是 ArkUI 侧的 surface, 新源到达前那块
        // surface 还留着上一章的末帧 (声音已停但画面不走), 把节点移出组合就让位给黑底;
        // 新 url 到达后重建 XComponent, onLoad 重新上报 surfaceId,
        // `setVideoSurfaceId` / `bindVideoSurfaceIfNeeded` 两条路径都会把当前 player 绑上去。
        if (url != null) {
            ArkUIView2(
                name = ARKUI_BUILDER_VIDEO_SURFACE,
                modifier = modifier.fillMaxSize(),
                parameter = js { "playerId"(OhosNativeBridge.PLAYER_ID_VIDEO_BOOK) },
                background = Color.Black,
                interactive = false,
            )
        } else {
            Box(modifier.fillMaxSize().background(Color.Black))
        }
    }

    @Composable
    override fun rememberGestureController(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
    ): VideoGestureController? {
        val ohosController = controller as? OhosVideoPlayerController ?: return null
        return remember(ohosController) {
            VideoGestureController(
                // 手势侧读数保持原样 (读控制器字段): playing/speed/亮度/音量都是本端真值;
                // 快照只是给共享层回显用的唯一数据源
                isPlaying = { ohosController.isPlaying },
                positionMs = { ohosController.positionMs },
                durationMs = { ohosController.durationMs },
                speed = { ohosController.speed },
                setSpeed = { ohosController.setSpeed(it) },
                onPlayPause = { ohosController.playPause() },
                seekTo = { ohosController.seekTo(it) },
                readBrightness = {
                    // 不可读时给 null 而不是 0.5f: 拿假基线会让第一帧就把系统亮度硬拉到一半
                    if (ohosController.brightness >= 0f) ohosController.brightness else null
                },
                writeBrightness = { ohosController.setBrightness(it) },
                readVolume = { ohosController.volume },
                writeVolume = { ohosController.setVolume(it) },
                onToggleControls = screenModel::onToggleControls,
                onGestureText = screenModel::onGestureText,
            )
        }
    }

    /** ArkTS 侧 `registerComposeInteropBuilder` 的注册 key (需与 ets 端字面量一致)。 */
    const val ARKUI_BUILDER_VIDEO_SURFACE: String = "legadoVideoSurface"

    override fun applyFullscreen(enabled: Boolean) {
        OhosNativeBridge.setWindowFullScreenLayout(enabled)
        OhosNativeBridge.setWindowSystemBarEnable(!enabled)
    }

    /**
     * 鸿蒙的系统级全屏 = 锁横屏 + 隐藏系统栏, 两者必须一起做。
     *
     * 上一版只 `setWindowPreferredOrientation(2)`: 画面转成横屏但状态栏/导航栏还在, 而页面
     * 已按全屏布局渲染 (共享层读 [rememberSystemFullScreen] = null → 用页面意图标志),
     * 观感就是"图标显示退出全屏、实际根本没全屏"。这里复用 [applyFullscreen] 的同一条
     * 系统栏通道补齐缺失的一半; 退出时同时复位方向 (0 = UNSPECIFIED)。
     */
    override fun applySystemFullScreen(enabled: Boolean) {
        // 横屏全屏: 2=LANDSCAPE / 0=UNSPECIFIED (枚举映射见 OhosPlatformServices.setOrientation)
        OhosNativeBridge.setWindowPreferredOrientation(if (enabled) 2 else 0)
        OhosNativeBridge.setWindowFullScreenLayout(enabled)
        OhosNativeBridge.setWindowSystemBarEnable(!enabled)
    }

    /**
     * 鸿蒙全屏是页面意图驱动 (锁向 + 隐藏系统栏, 没有"窗口真实全屏态"可读), 返回 null
     * 让共享层沿用 `UiState.isSystemFullScreen`。
     *
     * 也不需改 [supportsSystemBack]: ArkUI 返回键已接到 OnBackPressedDispatcher
     * (见 PlatformBackHandler.ohos.kt), 全屏期间用户总有系统返回可退。
     */
    @Composable
    override fun rememberSystemFullScreen(): Boolean? = null
}

/**
 * 视频播放控制器: 命令经 napi Media 桥发 ArkTS AVPlayer, 事件回推缓存状态。
 * playerId 固定 "videoBook", 与音频书/HttpTTS 互不抢占 (同 OhosAvAudioPlayController 模式)。
 *
 * 播放态以 [playback] 单一快照流回显 (共享层不再缓存播放态, 平台也不再"记得回灌"):
 * ArkTS 上报的 playing/paused/buffering/ready/end 与本地命令下发一起折叠进快照,
 * 判定口径见 [PlaybackSnapshot]。
 */
class OhosVideoPlayerController(
    private val screenModel: VideoPlayScreenModel,
    private val onPlaybackEnded: () -> Unit,
) : VideoPlayerController, OhosNativeBridge.MediaEventListener {

    @Volatile
    private var playing = false

    /**
     * 用户播放意图 (对照 media3 playWhenReady): 命令下发时置位, ArkTS 事件同步。
     * 不能用 [playing] 顶替 —— 缓冲中 playing 为 false, 播放钮会在卡顿瞬间跳成播放三角。
     */
    @Volatile
    private var playWhenReady = true

    @Volatile
    var speed = 1f
        private set

    @Volatile
    var volume = 1f
        private set

    @Volatile
    var brightness = -1f
        private set
    @Volatile
    private var cachedDuration = 0L
    @Volatile
    private var cachedPosition = 0L
    @Volatile
    private var loadedUrl: String? = null
    @Volatile
    private var listenerRegistered = false
    /** AVPlayer prepare 完成 (onReady) 前视为加载中 */
    @Volatile
    private var ready = false
    /** 缓冲百分比 (ArkTS onBufferingUpdate 推送, 0-100) */
    @Volatile
    private var bufferingPercent = 100

    /** 已缓存时长 ms (ArkTS onCachedDuration 推送 = AVPlayer CACHED_DURATION 档) */
    @Volatile
    private var cachedBufferedMs = 0L

    /** 播完标记 (对照 media3 STATE_ENDED, ArkTS onEndOfMedia 置位) */
    @Volatile
    private var ended = false

    /** 本次装载待恢复的起播位置; AVPlayer 未 prepared 时 seek 不可靠, 等 ArkTS onReady 再应用 */
    @Volatile
    private var pendingStartSeekMs = 0L

    /** 起播位置是否已应用 (含本次无需应用), 保证一次装载只生效一次 */
    @Volatile
    private var startSeekApplied = true

    /** 缓冲中: 链接就绪后 AVPlayer 未 prepare 完成 (onReady 前), 或缓冲百分比 < 100 (起播/卡顿/seek)。 */
    val isBuffering: Boolean
        get() = !ready || bufferingPercent < 100

    val isPlaying: Boolean
        get() = playing

    private val _playback = MutableStateFlow(PlaybackSnapshot())

    /** 播放态快照 (共享层回显唯一数据源) */
    override val playback: StateFlow<PlaybackSnapshot> = _playback.asStateFlow()

    /**
     * 把本地真值 + ArkTS 回推折叠成 [PlaybackSnapshot]。
     *
     * 各 `onMediaEvent` 分支与命令下发处都调它 (原先的 syncBuffering 只更新一条布尔流,
     * 共享层现在只读这一份快照)。无源 ([loadedUrl] == null) 时 idle = true, 缓冲判定
     * 强制落下, 免得 stop 后转圈常亮。
     */
    private fun publishPlayback() {
        val hasMedia = loadedUrl != null
        _playback.update {
            it.copy(
                playWhenReady = playWhenReady && hasMedia,
                isPlaying = playing && hasMedia,
                isBuffering = hasMedia && isBuffering,
                speed = speed,
                ended = ended,
                idle = !hasMedia,
            )
        }
    }

    // 加载 URL: 经 tsfn 发 setSourceUrl 命令, ArkTS 创建 AVPlayer 设源 prepare
    fun loadUrl(url: String) {
        if (url == loadedUrl) return
        // 换源前记下用户意图 (必须在写 loadedUrl 之前取): 暂停态换源 (切清晰度) 不得被强制
        // 起播; 无守卫 (= 首次装载 / 刚 stop()) 照旧自动起播。play 命令延到 onReady 再发:
        // ArkTS 侧 prepare 未完成时 play 会被状态机拒 (同 OhosAudioPlayCommander 的时序)。
        val keepPlaying = loadedUrl == null || playWhenReady
        loadedUrl = url
        playWhenReady = keepPlaying
        ready = false
        playing = false
        ended = false
        bufferingPercent = 0
        cachedBufferedMs = 0L
        // 本次装载的起播位置: 与 videoUrl 同拍下发 (见 VideoPlayViewModelShared.startPositionMs),
        // 首屏恢复进度 / 书签定位 / 刷新续播 / 切清晰度都靠它, >0 时也照常起播
        pendingStartSeekMs = screenModel.shared.startPositionMs.value
        startSeekApplied = pendingStartSeekMs <= 0L
        ensureListener()
        sendCommand(MediaCommand(action = "setSourceUrl", url = url))
        publishPlayback()
    }

    private fun ensureListener() {
        if (!listenerRegistered) {
            OhosNativeBridge.setMediaEventListener(OhosNativeBridge.PLAYER_ID_VIDEO_BOOK, this)
            listenerRegistered = true
        }
    }

    override val positionMs: Long get() = cachedPosition
    override val durationMs: Long get() = cachedDuration

    /**
     * 已缓冲到的时间点 (进度条缓冲层用)。
     *
     * AVPlayer 的 CACHED_DURATION 档给的是"已缓存时长", 从当前播放位置往后算,
     * 换成绝对时间点才能画进度条; 不用 [bufferingPercent] —— 那是起播缓冲进度,
     * 缓冲完成后恒 100, 折算成时长就是一条永远铺满的假缓冲条。
     */
    override val bufferedMs: Long
        get() {
            if (cachedBufferedMs <= 0L) return 0L
            val end = cachedPosition + cachedBufferedMs
            return if (cachedDuration > 0L) end.coerceAtMost(cachedDuration) else end
        }

    override fun playPause() {
        if (playing || playWhenReady) {
            // 意图位 / playing 任一为真都算"正在播或在等播": 下发暂停并落下意图
            // (缓冲卡顿中 playing 还没回来, 只看它会按出一次 play)
            sendCommand(MediaCommand(action = "pause"))
            playing = false
            playWhenReady = false
        } else {
            if (ended) {
                // 播完后按播放 = 重播本片 (AVPlayer 停在 completed 时直接 play 不一定回头)
                ended = false
                cachedPosition = 0L
                sendCommand(MediaCommand(action = "seekTo", position = 0L))
            }
            // play 命令只表示请求起播；重试计数只在 ArkTS 回推 onReady 后清零。
            sendCommand(MediaCommand(action = "play"))
            if (speed != 1f) sendCommand(MediaCommand(action = "setSpeed", speed = speed))
            playWhenReady = true
        }
        publishPlayback()
    }

    /** 无条件暂停 (不 toggle): 对照原版点标题进详情前的 player?.pause() */
    override fun pause() {
        playWhenReady = false
        playing = false
        sendCommand(MediaCommand(action = "pause"))
        publishPlayback()
    }

    /**
     * 停止并卸载当前媒体 (渲染层观察到源被清空时调用)。
     *
     * 约束: ArkTS `handleMediaCommand` 的 action 白名单里只有 setSource/setSourceUrl/play/
     * pause/stop/seekTo/setSpeed/syncNowPlaying/clearNowPlaying/release, 没有"清源"这一档,
     * 本文件也不得新造它不认识的字符串 —— 所以用既有 `stop` (player.stop() 让 AVPlayer 回
     * idle 并卸媒体), Kotlin 侧同步清 [loadedUrl] 守卫与就绪位。下一次 loadUrl 的
     * setSourceUrl 在 ArkTS 内部会先 releasePlayer 重建实例, 不会残留上一章的画面与声音。
     */
    override fun stop() {
        val hadSource = loadedUrl != null
        loadedUrl = null
        ready = false
        playing = false
        ended = false
        bufferingPercent = 100
        cachedBufferedMs = 0L
        cachedPosition = 0L
        cachedDuration = 0L
        pendingStartSeekMs = 0L
        startSeekApplied = true
        // 从未装载过 (无 ArkTS 侧实例可停) 就不下发: 避免空跑一条命令, 也避开旧实例
        // 残留事件与新装载抢状态
        if (hadSource) sendCommand(MediaCommand(action = "stop"))
        publishPlayback()
    }

    override fun seekTo(positionMs: Long) {
        cachedBufferedMs = 0L
        sendCommand(MediaCommand(action = "seekTo", position = positionMs))
        cachedPosition = positionMs
        // 手动 seek 即视为已定位: 未消费的恢复位置不得在之后把用户拽回去
        startSeekApplied = true
        publishPlayback()
    }

    override fun seekBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    override fun setSpeed(speed: Float) {
        this.speed = speed
        // 与 iOS 不同: AVPlayer setSpeed 只改倍速, 不带"以该速率起播"语义,
        // 暂停态下发不会把视频恢复播放, 因此照常透传 (缓存值同时驱动倍速钮回显)
        sendCommand(MediaCommand(action = "setSpeed", speed = speed))
        publishPlayback()
    }

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
        sendCommand(MediaCommand(action = "setVolume", volume = volume.toDouble()))
    }

    fun setBrightness(b: Float) {
        brightness = b.coerceIn(0f, 1f)
        OhosNativeBridge.setWindowBrightness(brightness)
    }

    override fun seekBack() = seekBy(-10000)
    override fun seekForward() = seekBy(10000)

    override fun release() {
        if (brightness >= 0f) {
            OhosNativeBridge.setWindowBrightness(-1f)
            brightness = -1f
        }
        sendCommand(MediaCommand(action = "release"))
        if (listenerRegistered) {
            OhosNativeBridge.setMediaEventListener(OhosNativeBridge.PLAYER_ID_VIDEO_BOOK, null)
            listenerRegistered = false
        }
        playing = false
        playWhenReady = false
        ended = false
        ready = false
        bufferingPercent = 100
        cachedBufferedMs = 0L
        cachedDuration = 0L
        cachedPosition = 0L
        pendingStartSeekMs = 0L
        startSeekApplied = true
        loadedUrl = null
        publishPlayback()
    }

    // ArkTS AVPlayer 事件回调 (同 OhosAvAudioPlayController.onMediaEvent 模式)
    override fun onMediaEvent(eventJson: String) {
        val event = runCatching {
            KS_JSON.decodeFromString(MediaEvent.serializer(), eventJson)
        }.getOrNull() ?: return
        // 无源 (stop / release / 出错后) 一律丢弃: tsfn 投递是异步的, 刚停掉的旧实例还会排队的
        // 残留事件 (paused / error / position), 接下来会把我们自己的 stop 当成播放错误,
        // 反过来触发一次空重试 (或把新章的起播意图抹掉)
        if (loadedUrl == null) return
        when (event.event) {
            "onReady" -> {
                ready = true
                bufferingPercent = 100
                screenModel.shared.resetRetryOnPlayError()
                // 就绪才定位: AVPlayer 未 prepared 时 seek 不可靠, 也不用 delay 掩盖时序
                if (!startSeekApplied) {
                    startSeekApplied = true
                    val startMs = pendingStartSeekMs
                    if (startMs > 0L) {
                        sendCommand(MediaCommand(action = "seekTo", position = startMs))
                        cachedPosition = startMs
                    }
                }
                // 起播意图在装载侧定下 (见 [loadUrl]), prepare 完成后在此落地
                if (playWhenReady) {
                    sendCommand(MediaCommand(action = "play"))
                    if (speed != 1f) sendCommand(MediaCommand(action = "setSpeed", speed = speed))
                }
            }

            "onBufferingUpdate" -> event.percent?.let { bufferingPercent = it.toInt() }

            "onCachedDuration" -> event.cachedDuration?.let { cachedBufferedMs = it }

            "onEndOfMedia" -> {
                playing = false
                playWhenReady = false
                ended = true
                bufferingPercent = 100
                // 先落快照再推下一章: 末章 (无下一章) 停在末帧时钮不能还画着暂停条
                publishPlayback()
                onPlaybackEnded()
                return
            }

            "onError" -> {
                playing = false
                playWhenReady = false
                ended = false
                bufferingPercent = 100
                // 先取位置再清守卫: 重试要带当前真实进度续播
                val pos = cachedPosition
                loadedUrl = null
                ready = false
                publishPlayback()
                val message = event.message ?: "视频播放出错"
                val retried = screenModel.shared.retryOnPlayError(seekPositionMs = pos)
                if (!retried) {
                    screenModel.dispatch(VideoPlayUiEvent.ShowError(message))
                }
                return
            }

            "onDuration" -> event.duration?.let { cachedDuration = it }
            "onPosition" -> event.position?.let { cachedPosition = it }
            "onPlaying" -> {
                playing = true
                ended = false
                // ArkTS 真在播 ⇒ 意图不可能是暂停, 一并同步 (命令下发处已置位, 这里是兜底)
                playWhenReady = true
                bufferingPercent = 100
            }

            "onPaused" -> {
                playing = false
                bufferingPercent = 100
                // 只在这次装载已就绪后才落下意图位: 换源前排队的旧实例 paused 上报
                // 不能把新章的自动起播意图抹掉 (否则新章停在首帧不动)
                if (ready) playWhenReady = false
            }
        }
        publishPlayback()
    }

    private fun sendCommand(cmd: MediaCommand) {
        val stamped = cmd.copy(playerId = OhosNativeBridge.PLAYER_ID_VIDEO_BOOK)
        OhosNativeBridge.sendMediaCommand(
            KS_JSON.encodeToString(
                MediaCommand.serializer(),
                stamped
            )
        )
    }

    @Serializable
    private data class MediaCommand(
        val action: String,
        val playerId: String = "",
        val url: String? = null,
        val position: Long? = null,
        val speed: Float? = null,
        val volume: Double? = null,
    )

    @Serializable
    private data class MediaEvent(
        val event: String,
        val message: String? = null,
        val percent: Long? = null,
        val duration: Long? = null,
        val cachedDuration: Long? = null,
        val position: Long? = null,
    )
}
