package io.legado.app.ui.book.video

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.LayoutInflater
import android.view.TextureView
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.ui.compose.component.zoomMaxPanX
import io.legado.app.ui.compose.component.zoomMaxPanY
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.hasPlayableScheme
import io.legado.app.utils.toggleSystemBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil
import kotlin.math.floor

class AndroidVideoPlayPlatformProvider(
    private val activity: MainActivity,
) : VideoPlayPlatformProvider {

    companion object {
        /** 宽高比端点向内取整的刻度 (万分位足够表达系统区间端点, 又不至于溢出 Int) */
        private const val RATIO_SCALE = 10000

        /**
         * 系统允许的画中画宽高比闭区间 (framework-res 的
         * `config_pictureInPictureMin/MaxAspectRatio`, AOSP config.xml 默认 0.41841004184 / 2.39)。
         * 系统资源按名取不到时按 `PictureInPictureParams.Builder.setAspectRatio` KDoc 写明的
         * 公开契约 2.39:1 ~ 1:2.39 收敛。
         */
        private val pipAspectRatioBounds: Pair<Float, Float> by lazy {
            val res = Resources.getSystem()
            val minId = res.getIdentifier(
                "config_pictureInPictureMinAspectRatio", "dimen", "android"
            )
            val maxId = res.getIdentifier(
                "config_pictureInPictureMaxAspectRatio", "dimen", "android"
            )
            if (minId != 0 && maxId != 0) {
                res.getFloat(minId) to res.getFloat(maxId)
            } else {
                0.41841004184f to 2.39f
            }
        }

        /** 小窗内遥控 RemoteAction (上一章/播放暂停/下一章) → 广播 → [MainActivity.pipControlReceiver] 的协议常量 */
        const val ACTION_MEDIA_CONTROL = "io.legado.app.pip.MEDIA_CONTROL"
        const val EXTRA_CONTROL_TYPE = "pip_control_type"

        const val CONTROL_PLAY = 1
        const val CONTROL_PAUSE = 2
        const val CONTROL_PREV = 3
        const val CONTROL_NEXT = 4
    }

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController = AndroidVideoPlayerController(activity, screenModel, onPlaybackEnded)

    /** 画中画真实态 (Activity 回调驱动, Compose 可观察); 展开/关闭小窗后翻回 false。 */
    internal var isInPip: Boolean by mutableStateOf(false)
        private set

    /** 进入小窗时的视频控制器 (RemoteAction 命令路由与参数刷新用); 下次进入时覆盖,
     *  页面退出后残留的是已 release 的实例 (仅剩快照字段, 不再持媒体), 无需主动清理。 */
    private var activeController: AndroidVideoPlayerController? = null

    /** 视频渲染面引用 (画中画源矩形按它算); 渲染面离开组合时置空 */
    private var playerView: PlayerView? = null

    /** 本次小窗请求的结算回调 (进入结果送达时调用并作废); null = 没有请求在飞。 */
    private var pipRequestSettled: (() -> Unit)? = null

    /** [MainActivity.onPictureInPictureModeChanged] 转发入口, 同时是进入请求的结果送达点。 */
    internal fun onPipModeChanged(isInPip: Boolean) {
        this.isInPip = isInPip
        val settled = pipRequestSettled ?: return
        pipRequestSettled = null
        settled()
    }

    @Composable
    override fun rememberIsInPictureInPicture(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) isInPip else null

    override fun enterPictureInPicture(
        controller: VideoPlayerController?,
        onSettled: () -> Unit,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val c = controller as? AndroidVideoPlayerController
        activeController = c
        pipRequestSettled = onSettled
        // 系统拒绝 (策略禁用/窗口不可用) 回 false: 共享层据此结束请求期, 页面回到常规渲染
        val accepted = activity.enterPictureInPictureMode(buildPipParams(c))
        // 拒绝时结果已经在手, 结算由共享层按返回值完成 (迟到的模式回调不得再结算一次)
        if (!accepted) pipRequestSettled = null
        return accepted
    }

    /** 播放态/视频比例变化时刷新小窗参数 (RemoteAction 图标随播放态切换); 非小窗态是 no-op。 */
    internal fun refreshPipParams(controller: AndroidVideoPlayerController) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPip) return
        val playing = controller.playback.value.isPlaying
        val size = controller.videoSize
        // 去重: publishPlayback 在 seek/缓冲等高频路径也会调, 同参重复 set 系统参数纯浪费 IPC
        if (playing == lastPipPlaying && size.width == lastPipWidth && size.height == lastPipHeight
            && controller.pipTitle == lastPipTitle
        ) return
        lastPipPlaying = playing
        lastPipWidth = size.width
        lastPipHeight = size.height
        lastPipTitle = controller.pipTitle
        activity.setPictureInPictureParams(buildPipParams(controller))
    }

    private var lastPipPlaying = false
    private var lastPipWidth = 0
    private var lastPipHeight = 0
    private var lastPipTitle: String? = null

    /** 小窗 RemoteAction 命令 ([MainActivity.pipControlReceiver] 转发) */
    internal fun onPipControl(controlType: Int) {
        when (controlType) {
            CONTROL_PREV -> activeController?.prevChapter()
            CONTROL_PLAY -> activeController?.playPause()
            CONTROL_PAUSE -> activeController?.pause()
            CONTROL_NEXT -> activeController?.nextChapter()
        }
    }

    /** [MainActivity.onStop] 转发, 官方 PiP 指南钦定模式 (视频只在可见时播放):
     *  activity 完全不可见即暂停, 不区分来源。小窗挂在前台期间 activity 只 pause
     *  不 stop, 小窗播放不受影响; 关小窗/按 home/切后台都落到这里。 */
    internal fun onActivityStopped() {
        activeController?.pause()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(controller: AndroidVideoPlayerController?): PictureInPictureParams {
        val playing = controller?.playback?.value?.isPlaying == true
        return PictureInPictureParams.Builder()
            .setAspectRatio(pipAspectRatio(controller))
            .setActions(buildRemoteActions(playing))
            .apply {
                // 源矩形 = 视频区在窗口中的实际位置与尺寸: 官方指南明确设置它才能让进入画中画
                // 的转场按这块区域无缝缩放, Android 12+ 对退出画中画同样生效 —— 不设就只能按
                // 整窗插值, 小窗展开回页时画面跳变
                pipSourceRect(playerView)?.let { setSourceRectHint(it) }
                // 无缝缩放保持系统默认 (开): 它是为视频内容准备的; 关掉会让系统在小窗里按新尺寸
                // 重新呈现内容 —— 小窗里会看到完整界面而不是只有画面
                // 小窗头部标题与视频页标题栏同源 (API 33+ 才有该展示位, 低版本不设置)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    controller?.pipTitle?.takeIf { it.isNotEmpty() }?.let { setTitle(it) }
                }
            }
            .build()
    }

    /**
     * 视图重排后把最新参数 (含可见区域) 同步给系统; 非小窗态是 no-op。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun refreshPipSourceRect() {
        val controller = activeController ?: return
        if (pipSourceRect(playerView) == null) return
        activity.setPictureInPictureParams(buildPipParams(controller))
    }

    /** 视频区的窗口坐标矩形; 视图未布局或未在组合时为 null (不设源矩形)。 */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipSourceRect(view: PlayerView?): Rect? {
        val v = view ?: return null
        if (v.width <= 0 || v.height <= 0) return null
        val location = IntArray(2)
        v.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + v.width, location[1] + v.height)
    }

    /** 小窗遥控三键 (官方 RemoteActions + 广播模式): 上一章/下一章恒显, 中键图标按播放态二选一 */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildRemoteActions(playing: Boolean): List<RemoteAction> {
        fun controlAction(@DrawableRes iconRes: Int, titleRes: Int, controlType: Int): RemoteAction {
            val pendingIntent = PendingIntent.getBroadcast(
                activity,
                controlType,
                Intent(ACTION_MEDIA_CONTROL)
                    .putExtra(EXTRA_CONTROL_TYPE, controlType)
                    .setPackage(activity.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val title = activity.getString(titleRes)
            return RemoteAction(
                Icon.createWithResource(activity, iconRes),
                title,
                title,
                pendingIntent,
            )
        }
        return listOf(
            controlAction(R.drawable.ic_skip_previous, R.string.pip_prev_chapter, CONTROL_PREV),
            if (playing) {
                controlAction(R.drawable.ic_pause_24dp, R.string.pip_pause, CONTROL_PAUSE)
            } else {
                controlAction(R.drawable.ic_play_24dp, R.string.pip_play, CONTROL_PLAY)
            },
            controlAction(R.drawable.ic_skip_next, R.string.pip_next_chapter, CONTROL_NEXT),
        )
    }

    /**
     * 当前媒体宽高比; 未出画面/纯音频流 (0x0) 按布局默认 16:9 (media3 VideoSize.UNKNOWN 规格如此)。
     *
     * 媒体比例超出系统允许区间时按区间端点收敛: 系统在
     * `ActivityClientController.ensureValidPictureInPictureActivityParams` 里用
     * `PinnedTaskController.isValidPictureInPictureAspectRatio` (min <= ratio <= max) 校验,
     * 越界抛 `IllegalArgumentException("Aspect ratio is too extreme")`。
     */
    private fun pipAspectRatio(controller: VideoPlayerController?): Rational {
        val size = (controller as? AndroidVideoPlayerController)?.videoSize ?: VideoSize.UNKNOWN
        if (size.width <= 0 || size.height <= 0) return Rational(16, 9)
        val ratio = size.width.toFloat() / size.height
        val (minRatio, maxRatio) = pipAspectRatioBounds
        return when {
            // 端点按 RATIO_SCALE 向内取整: 直接取整可能落到区间外 (min 0.41841004184 截断成
            // 0.4184 就小于下界), 收敛值必须仍在闭区间内
            ratio > maxRatio -> Rational(floor(maxRatio * RATIO_SCALE).toInt(), RATIO_SCALE)
            ratio < minRatio -> Rational(ceil(minRatio * RATIO_SCALE).toInt(), RATIO_SCALE)
            else -> Rational(size.width, size.height)
        }
    }

    // media3 UnstableApi: PlayerView 控制接口 (setShowBuffering/resizeMode 等)。
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    @Composable
    override fun RenderSurface(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    ) {
        val androidController = controller as AndroidVideoPlayerController

        // 横屏自动进入全屏 (对照原版 onConfigurationChanged: 只在方向真翻转的那一次动)。
        // 键不能带 isPhone: 它由容器尺寸算出, 分屏拖窗 / 折叠屏展开都会让它变, 原写法会在这
        // 些与方向无关的时机重跑, 把用户手动开的窗口内全屏静默踢掉。
        val config = LocalConfiguration.current
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        val windowSize = LocalWindowInfo.current.containerSize
        val isPhone = with(LocalDensity.current) {
            minOf(windowSize.width, windowSize.height).toDp() < DesignTokens.wideScreenMinWidth
        }
        var lastLandscape by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(isLandscape) {
            val previous = lastLandscape
            lastLandscape = isLandscape
            // 首次组合只立基线: 进页时设备本来就横持着也不该替用户开全屏 (原版
            // onConfigurationChanged 本来就是"变化才回调", 不在创建时跑)
            if (previous == null || previous == isLandscape) return@LaunchedEffect
            // 右下角钮的系统级全屏会强制转横屏, 那一次翻转由它自己负责; 原写法会再叠一层
            // 窗口内全屏, 使两种全屏的互斥在安卓失效 (退出系统级全屏后卡在窗口内全屏)
            if (screenModel.state.value.isSystemFullScreen) return@LaunchedEffect
            screenModel.setFullScreen(isLandscape && isPhone)
        }

        // 画面缩放 (共享手势层只写状态): 缩放施加在 Compose 图层上, 与桌面同一机制。
        // 图层高于媒体库自己的内容帧布局, 不受它的测量与裁剪影响; 图层变换只对参与 View 合成的
        // 渲染面可见 —— 渲染面必须是纹理视图 (布局资源已声明), 不是时下方显式报错。
        val zoomState = screenModel.zoom.collectAsState()
        val videoAspectRatioState = screenModel.videoAspectRatio.collectAsState()
        AndroidView(
            factory = { context ->
                val view = LayoutInflater.from(context)
                    .inflate(R.layout.view_video_player, null, false) as PlayerView
                view.apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    player = androidController.player
                    this@AndroidVideoPlayPlatformProvider.playerView = this
                    // 独立合成的渲染面 (SurfaceView) 不参与 View 合成, 图层变换对它无效 ——
                    // 这种失效必须在日志里可见
                    if (videoSurfaceView !is TextureView) {
                        AppLog.put("视频渲染面不是纹理视图, 画面缩放不会生效")
                    }
                    // 退出小窗时系统会先把视图层级重排成全屏布局: 官方要求在动画开始前把最新
                    // 可见区域交给系统, 否则展开动画按过时矩形插值, 画面跳变
                    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) refreshPipSourceRect()
                    }
                }
            },
            update = { view -> view.player = androidController.player },
            modifier = modifier
                .fillMaxSize()
                // 放大后的画面不能盖到选集网格/控制栏上 (裁剪必须在变换外层)
                .clipToBounds()
                // 状态在图层参数更新阶段读取 (非组合期): 缩放/几何变化只重算图层参数, 不触发重组
                .graphicsLayer {
                    val zoom = zoomState.value
                    val aspectRatio = videoAspectRatioState.value
                    val scale = zoom.scale.takeIf { it.isFinite() } ?: 1f
                    // 平移范围与共享手势层同一公式 (内容 FIT 后超出容器的部分)。媒体库对宽高比
                    // 有 1% 容差 (差异小于它时内容帧就是容器), 这里按严格 FIT 算, 最多偏 0.5%
                    val maxPanX = zoomMaxPanX(size.width, size.height, aspectRatio, scale)
                    val maxPanY = zoomMaxPanY(size.width, size.height, aspectRatio, scale)
                    scaleX = scale
                    scaleY = scale
                    translationX = zoom.panX * maxPanX
                    translationY = zoom.panY * maxPanY
                },
        )

        // videoUrl: 非 null = 装载新源, null = 章节重新加载中 (loadChapter 先清空旧源)
        LaunchedEffect(androidController, screenModel) {
            screenModel.shared.videoUrl.collect { source ->
                // 清空 url 只是「没有新源」的数据状态, 不是停止命令: 不显式 stop 的话上一章
                // 画面与声音会一直播到新章解析完 (对照原版 menu_refresh 先 player.pause()
                // 再 refreshChapter, 切章路径上原版换新 player 天然停了旧的)
                if (source == null) {
                    androidController.stop()
                    return@collect
                }
                androidController.updateSource(source)
            }
        }
        DisposableEffect(androidController, screenModel) {
            androidController.bind(screenModel)
            onDispose {
                androidController.unbind()
                // 离开视频页释放外部视频读句柄, 并解除方向锁
                ExternalVideoHandle.release()
                releaseFullscreenOrientation()
            }
        }
        // 渲染面引用 (画中画源矩形按它算): 离开组合后置空
        DisposableEffect(androidController) {
            onDispose { playerView = null }
        }
    }

    @Composable
    override fun rememberGestureController(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
    ): VideoGestureController {
        val androidController = controller as AndroidVideoPlayerController
        val audioManager = remember {
            activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        val maxVolume = remember(audioManager) {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }
        return remember(androidController, audioManager, maxVolume) {
            VideoGestureController(
                isPlaying = { androidController.player.isPlaying },
                positionMs = { androidController.player.currentPosition },
                durationMs = { androidController.player.duration },
                speed = { androidController.player.playbackParameters.speed },
                setSpeed = { speed ->
                    androidController.player.playbackParameters = PlaybackParameters(
                        speed,
                        androidController.player.playbackParameters.pitch,
                    )
                },
                // 双击/拖动结束续播与控制栏播放钮同源: 播完 (STATE_ENDED) 一律从头重播
                onPlayPause = { androidController.playPause() },
                seekTo = { androidController.player.seekTo(it) },
                readBrightness = {
                    val a = activity.window.attributes
                    if (a.screenBrightness <= 0f) 0f else a.screenBrightness
                },
                writeBrightness = { value ->
                    activity.window.attributes = activity.window.attributes.apply {
                        screenBrightness = value
                    }
                },
                readVolume = { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() },
                writeVolume = { value ->
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value.toInt(), 0)
                },
                onToggleControls = screenModel::onToggleControls,
                onGestureText = screenModel::onGestureText,
                volumeMax = maxVolume.toFloat(),
                volumeStep = 1f,
                // 画面缩放: 手势只改共享状态, 由渲染层施加到视频面 (见 RenderSurface)
                zoom = VideoZoomHandle(
                    current = { screenModel.zoom.value },
                    set = screenModel::onZoomChange,
                    aspectRatio = { screenModel.videoAspectRatio.value },
                ),
            )
        }
    }

    override fun applyFullscreen(enabled: Boolean) {
        activity.toggleSystemBar(!enabled)
    }

    // 系统级全屏的方向锁: 进全屏锁横屏, 退全屏锁竖屏
    override fun applySystemFullScreen(enabled: Boolean) {
        activity.requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /** 离开视频页时解除方向锁。 */
    internal fun releaseFullscreenOrientation() {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

@SuppressLint("UnsafeOptInUsageError")
internal class AndroidVideoPlayerController(
    private val activity: MainActivity,
    private var screenModel: VideoPlayScreenModel,
    private val onPlaybackEnded: () -> Unit,
) : VideoPlayerController {
    val player: ExoPlayer = ExoPlayerHelper.createHttpExoPlayer(activity)
    private var bound = false

    /** 当前媒体视频尺寸 (PiP 宽高比声明用); 未出画面/纯音频为 UNKNOWN。 */
    var videoSize: VideoSize = VideoSize.UNKNOWN
        private set

    /** 播放态快照流 (回显唯一数据源, 见共享层 [PlaybackSnapshot]): 共享层现取,
     *  本端不再拿 onPlayerState 回灌页面状态。 */
    private val _playback = MutableStateFlow(PlaybackSnapshot())
    override val playback: StateFlow<PlaybackSnapshot> = _playback.asStateFlow()

    /** 已加载的 url: 页面转场会重建 RenderSurface 组合, LaunchedEffect(videoUrl.collect)
     *  随之重启并被 StateFlow 补发同值, 守卫避免重复 setMediaItem+prepare 从头重播
     *  (对齐桌面 startedUrl / iOS / ohos loadedUrl 模式)。[stop] 必须同步清掉它,
     *  内存 m3u8 清单分支这里存的是清单文本 (同址去重仍用得上), 它**不是可播 URI**,
     *  不能拿去重装媒体 —— 见 [playableUrl]。 */
    private var loadedUrl: String? = null

    /**
     * 本次装载对应的章号; 错误回调用它与当前章号比对, 不一致 = 旧章晚到错误, 直接丢弃
     * (否则会把"刷新"落到刚切到的新章上, 新章白多跑一轮装载)。
     */
    private var loadedChapterIndex: Int = -1

    /**
     * 可播 URI (直链 / 本地地址); 内存 m3u8 清单分支为 null。
     *
     * 格式重试要拿地址重建 MediaItem, 而内存分支的 [loadedUrl] 存的是清单文本, 拿去重建
     * 必失败并坠入格式重环 —— 所以格式重试只对 [playableUrl] 非空时生效 (与 [updateSource]
     * 的 `hasPlayableScheme()` 分流同口径)。
     */
    private var playableUrl: String? = null

    /** 本次装载已试过的格式覆盖: 同一格式不重装第二次, 交替错误码下硬性截断。 */
    private val triedFormats = mutableSetOf<String>()

    /** 当前媒体请求头快照 (格式重试重装时复用; [updateSource] 换地址时刷新)。 */
    private var lastHeaders: Map<String, String> = emptyMap()

    /** 从播放器现值映射一份快照并发射 (media3 各回调都在状态变更之后触发, 直读 player 即可)。 */
    private fun publishPlayback(p: Player = player) {
        _playback.value = PlaybackSnapshot(
            playWhenReady = p.playWhenReady,
            isPlaying = p.isPlaying,
            // 折叠「媒体尚未就绪 / 在等数据」: 共享层不再读 playbackState 兜底,
            // IDLE + playWhenReady = 起播前 (已 prepare 未出帧) 也算缓冲,
            // 否则原版的起播转圈会丢 (桌面/iOS/鸿蒙同口径)
            isBuffering = p.playbackState == Player.STATE_BUFFERING ||
                (p.playbackState == Player.STATE_IDLE && p.playWhenReady),
            speed = p.playbackParameters.speed,
            ended = p.playbackState == Player.STATE_ENDED,
            idle = p.playbackState == Player.STATE_IDLE,
        )
        // 小窗内播放/暂停钮图标随播放态刷新 (内部自判是否在画中画, 否则 no-op)
        (screenModel.platform as? AndroidVideoPlayPlatformProvider)?.refreshPipParams(this)
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publishPlayback()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publishPlayback()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            publishPlayback()
            // 播放真正成功 (READY) 才重置错误重试标记 (对齐原版 VideoPlayActivity
            // onPlaybackStateChanged: STATE_READY → hasRefreshedOnPlayError = false;
            // 链接不可用时永不 READY, 同章节只自动重试一次, 不再无限循环)
            if (playbackState == Player.STATE_READY) {
                // 重试预算随播放成功重置 (与 resetRetryOnPlayError 同口径): 已试格式清空只发生在
                // 真正 READY 之后, 坏流永远到不了这里 → 交替错误码仍被硬性截断
                triedFormats.clear()
                screenModel.shared.resetRetryOnPlayError()
            }
            if (playbackState == Player.STATE_ENDED) onPlaybackEnded()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            publishPlayback()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            this@AndroidVideoPlayerController.videoSize = videoSize
            // 画面显示比例 (含变形像素比): 手势层的可平移范围与质心锚点按它算, 变化时共享层重钳位置
            screenModel.onVideoAspectRatioChanged(
                if (videoSize.height > 0) {
                    videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                } else null
            )
            // 画中画中自动连播跨比例章节时同步刷新系统小窗 (内部自判是否在 PiP, 否则 no-op)
            (screenModel.platform as? AndroidVideoPlayPlatformProvider)
                ?.refreshPipParams(this@AndroidVideoPlayerController)
        }

        override fun onPlayerError(error: PlaybackException) {
            // 旧章晚到错误 (装载已被切章/取消作废) 直接丢弃: 否则 retryOnPlayError 会按
            // 新章 index 重拉, 把刚启动的新章装载取消并重跑一轮 (格式重试也可能拿新媒体验
            // 验旧错误码)。
            if (loadedChapterIndex != screenModel.shared.curChapterIndex.value) {
                return
            }
            // 对齐 FongMi ExoPlayerEngine.handleError → retryFormat: jar 代理地址无扩展名,
            // progressive 首装失败后按错误码换 mimeType 重装续播; 同一格式只重一次
            // (原版同错误码会反复 setFormat 成环, 属明显缺陷不复刻, 重试仍败则走下方上报)。
            // 换格式只对"可播 URI"成立: 内存 m3u8 分支的 [loadedUrl] 是清单文本, 拿它
            // setMediaItem 必失败并坠入换格式环 (见 [playableUrl])。
            val retryUrl = playableUrl
            val retryFormat = ExoPlayerHelper.retryMimeType(error.errorCode)
            if (retryUrl != null && retryFormat != null && triedFormats.add(retryFormat)) {
                // 复用本次装载的暂停意图位: 用户暂停中遇错重装同一条媒体, 不得被强制起播
                // (对照 [updateSource] 的 keepPaused 口径; 出错进 IDLE 也不清意图位)
                val keepPaused = !player.playWhenReady
                val retryPositionMs = player.currentPosition.coerceAtLeast(0L)
                setPlayableMedia(retryUrl, lastHeaders, retryFormat)
                player.prepare()
                if (retryPositionMs > 0L) player.seekTo(retryPositionMs)
                if (!keepPaused) player.play()
                publishPlayback()
                return
            }
            // 对齐 iOS handlePlayError: 先清 loadedUrl 守卫, 自动重试 (refreshChapter 重新
            // emit 同 URL) 才能放行重载
            loadedUrl = null
            playableUrl = null
            val retried = screenModel.shared.retryOnPlayError(
                seekPositionMs = player.currentPosition.coerceAtLeast(0L),
            )
            if (retried) return
            val sourceType = error is ExoPlaybackException &&
                error.type == ExoPlaybackException.TYPE_SOURCE
            // 由书进入的口径逐字不变: 只把源类错误摆到页面上 (非源错误多为可自愈抖动,
            // 原版只写日志)。直投必须全报: retryOnPlayError 在直投恒 false, 什么错都到不了
            // “重新装载”, 不报就是一圈永不落地的转圈 + 连「重新加载」入口都没有
            if (!sourceType && !screenModel.shared.isDirect) return
            val message = if (!sourceType) {
                "视频播放出错"
            } else {
                when (error.sourceException) {
                    is UnrecognizedInputFormatException -> "不是视频链接"
                    is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException -> "视频地址不可用"
                    else -> "视频播放出错"
                }
            }
            AppLog.put(message, error, true)
            // 重试后仍失败: 上报 UI 显示错误 (原版只写日志, 用户要求"及时表现出来")
            screenModel.dispatch(VideoPlayUiEvent.ShowError(message))
        }
    }

    fun bind(model: VideoPlayScreenModel) {
        screenModel = model
        if (!bound) {
            bound = true
            player.addListener(listener)
            // 绑定时回放一次初始态 (下沉前原版 setCurrentPlayer 加完监听就直接写了四值,
            // 下沉时只留下了「变化沿」): 监听器只报变更, 转场窗口里播放器若已在播/已 ENDED,
            // 新快照流会停在初值 —— UI 表现为缓冲圈永转 / 丢 ENDED 不自动下一章
            publishPlayback()
        }
    }

    fun unbind() {
        if (bound) {
            player.removeListener(listener)
            bound = false
        }
    }

    fun updateSource(analyzeUrl: AnalyzeUrlCore) {
        // 同 URL 已在播则跳过: 转场结束 RenderSurface 重新组合后 collect 补发同值,
        // 不重复 setMediaItem (会清播放列表并把位置重置为 0)
        if (analyzeUrl.url == loadedUrl) return
        // 换源前先记用户意图 (必须在写 loadedUrl 之前取): 对照原版 switchResolution 的
        // `if (oldPlayer?.isPlaying ?: false) newPlayer.play()` —— 用户手动暂停后换清晰度,
        // 不得被强制播回去; 原版读 isPlaying 会把「暂停中/缓冲中」误判成非播放意图, 这里读
        // playWhenReady (意图位)。无媒体 (= 首次装载 / 刚 stop()) 则照旧自动起播。
        val keepPaused = loadedUrl != null
                && player.playbackState != Player.STATE_IDLE
                && !player.playWhenReady
        loadedUrl = analyzeUrl.url
        lastHeaders = analyzeUrl.headerMap
        triedFormats.clear()
        loadedChapterIndex = screenModel.shared.curChapterIndex.value
        // 直链判定用 shared 的同一份判据 (http/https/file/content): 上一版只判 http 开头,
        // 外部投来的本地视频 (file:// 与 content://) 会掉进下面的内存 m3u8 清单分支 ——
        // 拿一条文件 URI 去建 HlsMediaSource, 观感就是进页黑屏报错。
        if (analyzeUrl.url.hasPlayableScheme()) {
            playableUrl = analyzeUrl.url
            // 外部 content:// 视频: 确保持有读句柄, 播它的 dup 副本
            if (analyzeUrl.url.startsWith("content://", ignoreCase = true)) {
                ExternalVideoHandle.hold(activity, analyzeUrl.url)
            }
            setPlayableMedia(analyzeUrl.url, analyzeUrl.headerMap, currentResolutionMime())
        } else {
            // 内存 m3u8 清单文本不是可播 URI: 标记为 null, 格式重试不得拿清单文本重建媒体
            playableUrl = null
            val fakeUrl = analyzeUrl.headerMap["Referer"]
            val dataSourceFactory = DataSource.Factory {
                object : DataSource {
                    private val http = ExoPlayerHelper.okhttpDataFactory.createDataSource()
                    private val memory =
                        ByteArrayDataSource(analyzeUrl.url.toByteArray(Charsets.UTF_8))
                    private var memoryData = false
                    private val active get() = if (memoryData) memory else http
                    override fun addTransferListener(transferListener: TransferListener) {
                        http.addTransferListener(transferListener)
                        memory.addTransferListener(transferListener)
                    }

                    override fun open(dataSpec: DataSpec): Long {
                        memoryData = dataSpec.uri == fakeUrl?.toUri()
                        return active.open(dataSpec)
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        active.read(buffer, offset, length)

                    override fun getUri(): Uri? = active.uri
                    override fun close() = active.close()
                }
            }
            player.setMediaSource(
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(
                    MediaItem.Builder().setUri(fakeUrl).setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                )
            )
        }
        player.prepare()
        // 本次装载的起播位置: 与 videoUrl 同拍下发 (见 VideoPlayViewModelShared.startPositionMs),
        // 首次进页恢复已存进度 / 书签定位 / 刷新续播 / 切清晰度都靠它 (对照原版 refreshPlayer 的
        // `if (viewModel.position != 0L) seekTo(viewModel.position)`); >0 时也照常起播
        val startPositionMs = screenModel.shared.startPositionMs.value
        if (startPositionMs > 0L) player.seekTo(startPositionMs)
        if (!keepPaused) player.play()
        publishPlayback()
    }

    /**
     * 装载可播地址: 外部 content:// 视频走持有句柄的数据源, 其余走默认路径。
     */
    private fun setPlayableMedia(url: String, headers: Map<String, String>, mime: String?) {
        val item = ExoPlayerHelper.createMediaItem(url, headers, mime)
        if (ExternalVideoHandle.holds(url)) {
            player.setMediaSource(
                ProgressiveMediaSource.Factory(
                    DataSource.Factory { ExternalVideoDataSource(url) }
                ).createMediaSource(item)
            )
        } else {
            player.setMediaItem(item)
        }
    }

    /**
     * 当前清晰度档的源声明媒体类型 (TVBox format 透传, FongMi PlaySpec.format 同语义);
     * 无则 null 走 URL 形态推断。读 videoSource/currentResolutionIndex 的时序安全依赖
     * shared 写序: 两者均先于 videoUrl 写入, updateSource 由 videoUrl 订阅触发时已就位。
     */
    private fun currentResolutionMime(): String? =
        screenModel.shared.videoSource.value?.getResolution(
            screenModel.shared.currentResolutionIndex.value,
        )?.mime?.takeIf { it.isNotBlank() }

    /**
     * 停止并卸载当前媒体 (共享层 videoUrl 置 null = 章节重新加载中/刷新, 见
     * [VideoPlayerController.stop]): 不显式停的话切章时上一章画面与声音会一直响到新章解析完。
     * 必须同步清 [loadedUrl] 守卫, 否则同链接重试会被当成「已在播」跳过。
     *
     * 先 [Player.stop] 再 clearMediaItems, 顺序不能反: 清空播放列表在 media3 眼里等同"没有媒体
     * 可播", 会被报成 STATE_ENDED, 监听器据此当成"播完"再推进一章 (点选集播下一集、点下一章
     * 跳一章都是它)。先 stop() 把状态落到 IDLE, media3 就不会再把空列表遮罩成 ENDED
     * (removeMediaItemsInternal 对 STATE_IDLE 提前跳过); 保留清空列表这一步, 是为了让
     * 位置/时长读数归 0 —— 另外三端卸载后位置都读 0 (桌面 resetSideFlowsToIdle / 鸿蒙
     * cachedPosition / iOS 摘掉 item), 共享层保存进度的屏障也按这个口径判 ("媒体已卸载时
     * 读到的 0 不是真实进度")。只用 stop() 会保留位置读数 (官方契约: does not reset the
     * playback position), 切章后立刻退出会把上一章的位置写成新章进度。
     */
    override fun stop() {
        loadedUrl = null
        playableUrl = null
        triedFormats.clear()
        loadedChapterIndex = -1
        player.stop()
        player.clearMediaItems()
        publishPlayback()
    }

    /**
     * 按当前地址与请求头重装一次 (外部直投错误遮罩的「重新加载」, 见
     * [VideoPlayerController.reload])。
     *
     * 不靠重发 StateFlow: 渲染层订阅的就是这条地址, 同值不发射、collect 也不会重跑。
     * 这里复用 [stop] (清 [loadedUrl] 守卫 + 卸媒体), 再用**同一个** AnalyzeUrlCore 重走
     * [updateSource]: 直链与内存 m3u8 两条分支按原地址形态自然分流, 起播位置仍读
     * startPositionMs (ScreenModel 在 reload 前已写好续播点)。
     * 守卫不清的话 [updateSource] 第一行的同址判定会把重试直接吃成空操作。
     */
    override fun reload() {
        val source = screenModel.shared.videoUrl.value
        stop()
        if (source == null) return
        updateSource(source)
    }

    override val positionMs: Long get() = player.currentPosition.coerceAtLeast(0L)
    override val durationMs: Long get() = player.duration.coerceAtLeast(0L)
    override val bufferedMs: Long get() = player.bufferedPosition.coerceAtLeast(0L)

    /** 小窗标题 (与视频页标题栏同源的 bookName): 切章/换源后随参数刷新。 */
    val pipTitle: String
        get() = screenModel.state.value.bookName

    fun prevChapter() {
        screenModel.onPrevChapter()
    }

    fun nextChapter() {
        screenModel.onNextChapter()
    }

    override fun playPause() {
        if (player.isPlaying) player.pause()
        else if (player.playbackState == Player.STATE_ENDED) {
            player.seekToDefaultPosition()
            player.play()
        } else player.play()
    }

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun seekBy(deltaMs: Long) = player.seekTo((positionMs + deltaMs).coerceAtLeast(0L))
    override fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed, player.playbackParameters.pitch)
    }

    override fun seekBack() = player.seekBack()
    override fun seekForward() = player.seekForward()
    override fun release() {
        unbind()
        player.release()
    }
}
