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
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
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

    /** [MainActivity.onPictureInPictureModeChanged] 转发入口。 */
    internal fun onPipModeChanged(isInPip: Boolean) {
        this.isInPip = isInPip
    }

    @Composable
    override fun rememberIsInPictureInPicture(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) isInPip else null

    override fun enterPictureInPicture(controller: VideoPlayerController?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val c = controller as? AndroidVideoPlayerController
        activeController = c
        activity.enterPictureInPictureMode(buildPipParams(c))
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
                // 小窗头部标题与视频页标题栏同源 (API 33+ 才有该展示位, 低版本不设置)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    controller?.pipTitle?.takeIf { it.isNotEmpty() }?.let { setTitle(it) }
                }
            }
            .build()
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

        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    player = androidController.player
                }
            },
            update = { it.player = androidController.player },
            modifier = modifier.fillMaxSize(),
        )

        // videoUrl: 非 null = 装载新源, null = 章节重新加载中 (loadChapter 先清空旧源)
        LaunchedEffect(androidController, screenModel) {
            screenModel.shared.videoUrl.collect { source ->
                // 清空 url 只是「没有新源」的数据状态, 不是停止命令: 不显式 stop 的话上一章
                // 画面与声音会一直播到新章解析完 (对照原版 menu_refresh 先 player.pause()
                // 再 refreshChapter, 切章路径上原版换新 player 天然停了旧的)
                if (source != null) androidController.updateSource(source)
                else androidController.stop()
            }
        }
        DisposableEffect(androidController, screenModel) {
            androidController.bind(screenModel)
            onDispose { androidController.unbind() }
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
                onPlayPause = {
                    val p = androidController.player
                    if (p.isPlaying) p.pause() else p.play()
                },
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
            )
        }
    }

    override fun applyFullscreen(enabled: Boolean) {
        activity.toggleSystemBar(!enabled)
    }

    // 横屏全屏 (对照原版全屏钮 requestedOrientation 切换): 退出写 UNSPECIFIED 而非 PORTRAIT,
    // 单 Activity 下方向锁挂在 MainActivity 上, 留锁会随视频页出栈带到全 app
    override fun applySystemFullScreen(enabled: Boolean) {
        activity.requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
internal class AndroidVideoPlayerController(
    activity: MainActivity,
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
                player.setMediaItem(
                    ExoPlayerHelper.createMediaItem(retryUrl, lastHeaders, retryFormat),
                    player.currentPosition.coerceAtLeast(0L),
                )
                player.prepare()
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
            player.setMediaItem(
                ExoPlayerHelper.createMediaItem(
                    analyzeUrl.url,
                    analyzeUrl.headerMap,
                    currentResolutionMime(),
                )
            )
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
