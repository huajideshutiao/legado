@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui.book.video

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import io.legado.app.help.http.cookieJarHeader
import io.legado.app.help.media.AvPlayerBufferingObserver
import io.legado.app.help.media.AvPlayerItemStatusObserver
import io.legado.app.help.media.maxLoadedTimeRangeEndMs
import io.legado.app.ui.IosStatusBarHiddenKey
import io.legado.app.ui.IosStatusBarHiddenNotification
import io.legado.app.utils.hasPlayableScheme
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setRate
import platform.AVFoundation.setVolume
import platform.AVFoundation.volume
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIScreen

// iOS 视频播放平台能力: AVPlayer 播控, AVPlayerViewController 仅渲染 (纯视频流)
object IosVideoPlayPlatformProvider : VideoPlayPlatformProvider {

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController = IosVideoPlayerController(screenModel, onPlaybackEnded)

    @Composable
    override fun RenderSurface(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    ) {
        val iosController = controller as? IosVideoPlayerController ?: return
        val videoUrl by screenModel.shared.videoUrl.collectAsState()
        val url = videoUrl?.url
        // 装载键必须同时带上请求头: 只订阅 url 时, 「同 url 换 header」不会重装载 ——
        // 刷新章节重签名的直链 / 切清晰度带不同 Referer / 书签定位后换 header 重试,
        // 都拿着上一批 header 去请求, 服务端 403 而画面停在旧流 (AVPlayer 实例不换,
        // 下面的 playback 重绑 effect 也救不了它 —— 换的只是 item 里的 asset 参数)。
        // Map 的 equals 按内容比, 同一个 AnalyzeUrlCore 反复重组不会误触发重载。
        val headers = videoUrl?.headerMap?.toMap()
        // 订阅播放态快照 (不画任何东西, 只当 effect 的 key): 重绑只在 AVPlayer 实例真的
        // 变了时才需要 (首次装载 null→player / release player→null), 两者都会翻转快照的 idle;
        // 换章不换实例, 本来就不需重绑。UIKitView 的 update 槽拿走了这一份: 它只在本组合体
        // 重组时才跑, 而捕获的 iosController 是稳定引用 → 几乎不会重跑, 靠它绑会漏绑新实例→黑屏。
        val playback by iosController.playback.collectAsState()
        // AVPlayerViewController: 只出画面 (showsPlaybackControls=false), 系统控制条不显示
        val avpvc = remember { AVPlayerViewController() }

        LaunchedEffect(url, headers) {
            if (url != null) {
                iosController.loadUrl(url, headers.orEmpty())
            } else {
                // 切章/刷新把源置 null 只是"没有新源"的数据状态, 不是命令:
                // 不显式 stop 的话上一章画面与声音会一直播到新章解析完
                iosController.stop()
            }
        }

        // 装载状态一变就重绑当前 AVPlayer (实例在 loadUrl 里创建 / release 里被放掉)
        LaunchedEffect(playback) {
            avpvc.player = iosController.player
        }

        UIKitView(
            factory = {
                avpvc.apply {
                    showsPlaybackControls = false
                }.view
            },
            modifier = modifier.fillMaxSize(),
        )
    }

    @Composable
    override fun rememberGestureController(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
    ): VideoGestureController? {
        val iosController = controller as? IosVideoPlayerController ?: return null
        return remember(iosController) {
            VideoGestureController(
                // 播放态/倍速一律读快照: 读 player.rate() 会在暂停态读到 0 ——
                // 暂停态长按倍速取不到原速、松手把暂停的视频擅自恢复播放 (已确认缺陷)
                isPlaying = { iosController.playback.value.isPlaying },
                positionMs = { iosController.positionMs },
                durationMs = { iosController.durationMs },
                speed = { iosController.playback.value.speed },
                setSpeed = { iosController.setSpeed(it) },
                onPlayPause = { iosController.playPause() },
                seekTo = { iosController.seekTo(it) },
                readBrightness = { UIScreen.mainScreen.brightness.toFloat() },
                writeBrightness = { UIScreen.mainScreen.brightness = it.toDouble() },
                // 无 player = 真读不到, 给 null 而不是 1f: 拿假基线会让音量手势起手第一帧
                // 就把系统音量硬拉到 100% (见 VideoGestureController 读槽契约)
                readVolume = { iosController.player?.volume },
                writeVolume = { iosController.player?.setVolume(it) },
                onToggleControls = screenModel::onToggleControls,
                onGestureText = screenModel::onGestureText,
            )
        }
    }

    /**
     * iOS 没有"系统级全屏真实态"可读 (无编程改方向 / 无窗口全屏能力), 返回 null =
     * 全屏是页面意图驱动, 共享层沿用 `UiState.isSystemFullScreen` 渲染。
     */
    @Composable
    override fun rememberSystemFullScreen(): Boolean? = null

    // 顶栏整体隐藏后唯一退出口在顶栏菜单里, 而 iOS 的 PlatformBackHandler 是 no-op
    // (无物理/手势返回进统一链) → 视频页进全屏后既退不出全屏也打不开菜单。
    // 声明本端无系统返回通道, 由路由在全屏期间常驻一个退出入口。
    override val supportsSystemBack: Boolean get() = false

    override fun applyFullscreen(enabled: Boolean) {
        setStatusBarHidden(enabled)
    }

    /**
     * 系统级全屏 (对照 Android 横屏 / 鸿蒙锁向): iOS 无编程改方向能力, 能落地的只有
     * 状态栏显隐这一条通道, 但它必须**真的有动作** —— 上一版本方法是接口默认空实现,
     * 点一下只翻 `UiState.isSystemFullScreen`, 页面按全屏重排而系统栏纹丝不动,
     * 观感上就是"进得去、画面没变、退不出去"。
     *
     * 退全屏入口: iOS 的 PlatformBackHandler 是 no-op, 所以靠本对象上面的
     * [supportsSystemBack] = false 让共享层在全屏期间常驻一个退出钮 (不随控制栏自动隐藏而消失),
     * 与本方法搭成完整闭环: 系统栏真的隐了, 也真的退得回来。
     */
    override fun applySystemFullScreen(enabled: Boolean) {
        setStatusBarHidden(enabled)
    }

    /** 全屏观感统一通道: 经 SwiftUI 根视图 .statusBarHidden 桥, 与 WindowPolicy.setSystemBars 同源 */
    private fun setStatusBarHidden(hidden: Boolean) {
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = IosStatusBarHiddenNotification,
            `object` = null,
            userInfo = mapOf(IosStatusBarHiddenKey to hidden),
        )
    }
}

/**
 * AVPlayer 视频控制器: 播放/暂停/进度/倍速 (参考 IosHttpTtsPlayer 的 AVPlayer 用法)。
 *
 * 播放态以 [playback] 单一快照流回显 (共享层不再缓存播放态, 平台也不再"记得回灌");
 * 判定口径见 [PlaybackSnapshot]:
 * - [playWhenReady] 是**用户意图** (自己维护), 不取 player.rate() —— 暂停/缓冲中 rate 为 0
 *   或不确定, 拿它当意图会让播放钮永远停在播放三角
 * - 倍速取**请求值** [requestedSpeed], 同理不取 rate() (暂停态会显示 0X)
 * - "媒体在装载但还没出帧" ([ready] 为 false) 折叠进 isBuffering, 不留黑屏无指示的空档
 */
class IosVideoPlayerController(
    private val screenModel: VideoPlayScreenModel,
    private val onPlaybackEnded: () -> Unit,
) : VideoPlayerController {

    var player: AVPlayer? = null
        private set
    private var item: AVPlayerItem? = null
    private var endObserver: Any? = null
    private var failObserver: Any? = null
    private var statusObserver: AvPlayerItemStatusObserver? = null
    private var bufferingObserver: AvPlayerBufferingObserver? = null
    private var loadedUrl: String? = null

    /** 本次装载真正生效的请求头 (伪头已剔除), 与 [loadedUrl] 同作装载守卫 */
    private var loadedHeaders: Map<String, String> = emptyMap()

    /** KVO 上报的缓冲态 (item.status 加载中 / timeControlStatus 等待起播), 事件驱动无轮询 */
    private var kvoBuffering = false

    /** 本次装载的 item 是否已到 ReadyToPlay (未就绪一律算"在等数据") */
    private var ready = false

    /** 用户播放意图 (对照 media3 playWhenReady): 装载即播, pause 置否, error/ended 置否 */
    private var playWhenReady = true

    /** 用户请求的倍速 (回显与恢复播放都读它, 见类注释) */
    private var requestedSpeed = 1f

    /** 播完标记 (对照 media3 STATE_ENDED) */
    private var ended = false

    /** 本次装载待恢复的起播位置; AVPlayer 在 item 就绪前 seek 不可靠, 到 onReady 再执行一次 */
    private var pendingStartSeekMs = 0L

    /** 起播位置是否已应用 (含本次无需应用), 保证一次装载只生效一次 */
    private var startSeekApplied = true

    private val _playback = MutableStateFlow(PlaybackSnapshot())

    /** 播放态快照 (共享层回显唯一数据源) */
    override val playback: StateFlow<PlaybackSnapshot> = _playback.asStateFlow()

    /**
     * 装载一条地址 (headers 默认空 = 与修复前逐字同行为)。
     *
     * [headers] 必须与 url 一起进守卫 (见 RenderSurface 装载键注释): 只比 url 会把
     * "同地址换请求头" 吃成一次空装载。
     *
     * 先过 [iosPlayRejectReason]: AVPlayer 播不了的地址不给它试错的份 —— 交给 AVFoundation
     * 只会拿到一个不透明的系统错误 (甚至什么都不报), 黑屏到用户退出为止。
     */
    fun loadUrl(url: String, headers: Map<String, String> = emptyMap()) {
        iosPlayRejectReason(url)?.let { message ->
            rejectUnsupportedAddress(message)
            return
        }
        // cookieJarHeader 是给 legado 自己的 HTTP 栈的开关伪头, 喂进 AVURLAsset 会被
        // 当真实请求头发出 —— 与 commonMain AnalyzeUrlCore.resolveMedia() 同一剔除口径
        // (不直接调 resolveMedia: 它内部的 setCookie 会在组合期同步读库)
        val realHeaders = headers.filterKeys { key -> key != cookieJarHeader }
        if (url == loadedUrl && realHeaders == loadedHeaders) return
        // 换源前记下用户意图: 首次装载 (守卫为空) 与出错重试后的重新装载照旧播,
        // 暂停态换源 (如切清晰度) 不得被强制恢复播放
        val keepPlaying = loadedUrl == null || playWhenReady
        loadedUrl = url
        loadedHeaders = realHeaders
        // 先卸上一件媒体 (保留 AVPlayer 本体: 渲染面绑的是实例引用, 换源不换实例可避开
        // 「新 player 已建但 avpvc 仍指旧实例」的黑屏窗口; 真的换实例只有首装与 release 两条路)
        unloadMedia()
        val nsUrl = NSURL.URLWithString(url) ?: run {
            handlePlayError("视频地址不可用")
            return
        }
        // 请求头经 AVURLAsset options 注入 (与 HttpTtsPlayer.ios.kt / IosMediaService 同一写法);
        // 无头时传 null → 与修复前的 `AVURLAsset(nsUrl, null)` 逐字一致, 零行为变化
        val options: Map<Any?, Any?>? = if (realHeaders.isEmpty()) {
            null
        } else {
            mapOf<Any?, Any?>(AV_URL_ASSET_HTTP_HEADER_FIELDS_KEY to realHeaders)
        }
        val newItem = AVPlayerItem(asset = AVURLAsset(nsUrl, options))
        item = newItem
        val existing = player
        if (existing != null) {
            existing.replaceCurrentItemWithPlayerItem(newItem)
        } else {
            player = AVPlayer(playerItem = newItem)
        }
        // 起播位置与本次 url 同拍下发的 startPositionMs 一致 (装载侧消费, 不再比较时间戳);
        // 必须在注册观察器**之前**定下来: item 命中本地缓存时 KVO 带 Initial 选项会同步回调
        // onReady, 晚一步就错过那一次唯一的定位时机
        pendingStartSeekMs = screenModel.shared.startPositionMs.value
        startSeekApplied = pendingStartSeekMs <= 0L
        registerEndObserver(newItem)
        registerBufferingObserver(newItem)
        registerStatusObserver(newItem)
        if (keepPlaying) startPlayback()
        publishPlayback()
    }

    override val positionMs: Long
        get() = player?.let {
            val sec = CMTimeGetSeconds(it.currentTime())
            if (sec.isNaN() || sec.isInfinite()) 0L else (sec * 1000.0).toLong()
        } ?: 0L

    override val durationMs: Long
        get() = item?.let {
            val sec = CMTimeGetSeconds(it.duration)
            if (sec.isNaN() || sec.isInfinite()) 0L else (sec * 1000.0).toLong()
        } ?: 0L

    /**
     * 已缓冲到的时间点 (进度条缓冲层用)。
     *
     * `AVPlayerItem.loadedTimeRanges` 是 `NSValue`(装 `CMTimeRange`) 数组, 逐段取
     * `CMTimeRangeGetEnd` 的最大值 = 缓冲到哪儿了。seek 后会出现多段不连续区间,
     * 取最大 end 与 media3 `bufferedPosition` 口径一致。
     */
    override val bufferedMs: Long
        get() = item?.maxLoadedTimeRangeEndMs() ?: 0L

    override fun playPause() {
        val pl = player ?: return
        if (playWhenReady) {
            pl.pause()
            playWhenReady = false
        } else {
            if (ended) {
                // 播完后按播放 = 重播本片 (AVPlayer 停在末帧时直接 play 无效果)
                ended = false
                pl.seekToTime(CMTimeMake(0L, 1000))
            }
            startPlayback()
        }
        publishPlayback()
    }

    /** 无条件暂停 (不 toggle): 对照原版点标题进详情前的 player?.pause() */
    override fun pause() {
        playWhenReady = false
        player?.pause()
        publishPlayback()
    }

    override fun seekTo(positionMs: Long) {
        player?.seekToTime(CMTimeMake(positionMs.coerceAtLeast(0L), 1000))
        // 手动 seek 即视为已定位: 未消费的恢复位置不得在之后把用户拽回去
        startSeekApplied = true
        publishPlayback()
    }

    override fun seekBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    /**
     * 倍速: 暂停态**只记录请求值**。
     *
     * AVPlayer `setRate` 的语义是"以该速率起播", 暂停态调用会把视频直接恢复播放 ——
     * 长按倍速松手后暂停中的视频自己跑起来 (已确认缺陷)。恢复播放时由 [startPlayback]
     * 把 [requestedSpeed] 补 apply 一次。
     */
    override fun setSpeed(speed: Float) {
        requestedSpeed = speed
        val pl = player
        if (pl != null && playWhenReady) pl.setRate(speed)
        publishPlayback()
    }

    override fun seekBack() = seekBy(-10000)

    override fun seekForward() = seekBy(10000)

    /**
     * 停止并卸载当前媒体 (渲染层观察到源被清空时调用): pause + `replaceCurrentItemWithPlayerItem(null)`
     * + 清 loadedUrl 守卫, 让同链接重试可用。快照随 idle 翻回"未装载", 播放钮画播放三角。
     */
    override fun stop() {
        loadedUrl = null
        loadedHeaders = emptyMap()
        unloadMedia()
        publishPlayback()
    }

    /**
     * 按当前地址与请求头重装一次 (外部直投错误遮罩的「重新加载」, 见
     * [VideoPlayerController.reload])。
     *
     * 重发 StateFlow 推不动它: 渲染层键是 `LaunchedEffect(url, headers)`, 同址同头不重跑,
     * 而 [loadUrl] 的守卫比的也正是这一对 (url + 剔除伪头后的 headers) —— 守卫不清则重试
     * 被当成“已在播”直接 return。故先用 [stop] 把守卫与媒体一起清掉, 再走与首帧完全同一条
     * [loadUrl] 通道: 伪头剔除 / AVURLAsset 请求头注入 / KVO 与播完通知重注册 / 起播位置
     * (读 shared.startPositionMs, 装完在 onReady 才 seek) 都在里面。
     *
     * AVPlayer 实例不换 ([stop] 只卸 item), 所以不依赖 `LaunchedEffect(playback)` 重绑渲染面。
     */
    override fun reload() {
        val source = screenModel.shared.videoUrl.value
        stop()
        if (source == null) return
        loadUrl(source.url, source.headerMap.toMap())
    }

    override fun release() {
        loadedUrl = null
        loadedHeaders = emptyMap()
        unloadMedia()
        playWhenReady = false
        // 页面销毁: 连 AVPlayer 本体一起放掉 (stop 不放, 见 [unloadMedia] 注释)
        player = null
        publishPlayback()
    }

    /** 以当前请求倍速起播 (play() 只按 1X 起播, 倍速必须随后补 apply) */
    private fun startPlayback() {
        val pl = player ?: return
        playWhenReady = true
        pl.play()
        if (requestedSpeed != 1f) pl.setRate(requestedSpeed)
    }

    /**
     * 把内部真值折叠成 [PlaybackSnapshot]。
     *
     * 每次状态变化 (装载 / play-pause / 倍速 / seek / KVO 回调 / 播完 / 出错 / 停止) 都调它,
     * 共享层读的就是这一份, 平台不再有"记得回灌"的义务。
     */
    private fun publishPlayback() {
        val pl = player
        val hasMedia = pl != null && item != null
        // "装载中但还没出帧" (!ready) 必须算缓冲: 共享层已不再拿 playbackState 兜底,
        // 不折叠进来就是起播前那段黑屏且无任何转圈提示
        val buffering = hasMedia && (kvoBuffering || !ready)
        _playback.update {
            it.copy(
                playWhenReady = playWhenReady && hasMedia,
                isPlaying = hasMedia && (pl?.rate() ?: 0f) > 0f,
                isBuffering = buffering,
                speed = requestedSpeed,
                ended = ended,
                idle = !hasMedia,
            )
        }
    }

    private fun handlePlayError(message: String) {
        // 先取位置再卸载: 卸载后读不到真实进度了
        val pos = positionMs
        // 卸载失败媒体 + 清装载守卫: 快照回到 idle (钮画播放三角, 与 media3 出错进 IDLE 一致),
        // 守卫不清的话重试重新 emit 的同一条 url 必被吃掉 ("重新加载"是死按钮)
        loadedUrl = null
        loadedHeaders = emptyMap()
        unloadMedia()
        playWhenReady = false
        publishPlayback()
        val retried = screenModel.shared.retryOnPlayError(seekPositionMs = pos)
        if (!retried) {
            screenModel.dispatch(VideoPlayUiEvent.ShowError(message))
        }
    }

    /**
     * 本端根本播不了的地址: 直接落错误, 不走 [handlePlayError] 的自动重试 ——
     * 重试会重新拉章节再解析一次, 拿回来的还是同一条 iOS 认不出的地址,
     * 白费一次网络请求与进度条闪烁, 然后还是要报错。
     */
    private fun rejectUnsupportedAddress(message: String) {
        loadedUrl = null
        loadedHeaders = emptyMap()
        unloadMedia()
        playWhenReady = false
        publishPlayback()
        screenModel.dispatch(VideoPlayUiEvent.ShowError(message))
    }

    /**
     * iOS 能直接交给 AVPlayer 的地址 = `http` / `https` (含原生 HLS 的 .m3u8) / `file`。
     *
     * [hasPlayableScheme] 是四端并集 (含 Android 专有的 `content://`), 外部投递链
     * ([io.legado.app.ui.video.VideoDirect]) 按它放行, 所以 `content://` 真的会被投到 iOS
     * 播放页; 不在装载侧显式拦下, AVFoundation 只会给一个读不懂的系统错误甚至静默失败。
     *
     * @return null = 本端可播; 非空 = 给用户看的错误文案
     */
    private fun iosPlayRejectReason(url: String): String? {
        if (url.startsWith("http://", true) || url.startsWith("https://", true) ||
            url.startsWith("file://", true)
        ) {
            return null
        }
        return if (url.hasPlayableScheme()) {
            "该地址是 Android 专有格式 (content://), iOS 播放器无法读取"
        } else {
            "iOS 只支持 http / https / m3u8 直链与本地视频文件, 无法播放该地址"
        }
    }

    private fun registerStatusObserver(target: AVPlayerItem) {
        statusObserver?.dispose()
        statusObserver = null
        val observer = AvPlayerItemStatusObserver(
            item = target,
            onReady = {
                statusObserver = null
                ready = true
                screenModel.shared.resetRetryOnPlayError()
                // item 就绪才 seek: AVPlayer 在 ReadyToPlay 前 seek 不可靠, 也不用 delay 掩盖时序
                if (!startSeekApplied) {
                    startSeekApplied = true
                    if (pendingStartSeekMs > 0L) seekTo(pendingStartSeekMs)
                }
                // 起播前设的 rate 会被装载流程吞掉, 就绪后按请求倍速重申一次
                if (playWhenReady && requestedSpeed != 1f) player?.setRate(requestedSpeed)
                publishPlayback()
            },
            onFailed = { message ->
                statusObserver = null
                ready = false
                handlePlayError(message)
            },
        )
        statusObserver = observer
        observer.start()
    }

    // 缓冲状态 KVO 观察 (事件驱动: item.status 加载中 / player.timeControlStatus 等待起播)
    private fun registerBufferingObserver(target: AVPlayerItem) {
        val pl = player ?: return
        bufferingObserver?.dispose()
        bufferingObserver = null
        val observer = AvPlayerBufferingObserver(
            player = pl,
            item = target,
            onBufferingChange = { buffering ->
                kvoBuffering = buffering
                // 外部暂停兜底 (来电 / 音频会话被打断): 既不在等数据也不在推进 (rate==0)
                // ⇒ 时钟真的停了, 把意图位同步落下, 否则钮停在暂停态而画面已冻
                // (对照 media3 onPlayWhenReadyChanged; ready 前不下落, 免得抢在起播前抹掉意图)
                if (!buffering && ready && playWhenReady && (player?.rate() ?: 0f) == 0f) {
                    playWhenReady = false
                }
                publishPlayback()
            },
        )
        bufferingObserver = observer
        observer.start()
    }

    // 注册播放结束监听 (AVPlayerItemDidPlayToEndTimeNotification 与 AVPlayerItemFailedToPlayToEndTimeNotification)
    private fun registerEndObserver(target: AVPlayerItem) {
        val center = NSNotificationCenter.defaultCenter
        endObserver?.let { center.removeObserver(it) }
        endObserver = null
        failObserver?.let { center.removeObserver(it) }
        failObserver = null
        endObserver = center.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification,
            `object` = target,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            // ENDED 先落快照再推下一章: 否则末章 (无下一章) 停在末帧时钮还画着暂停条
            ended = true
            playWhenReady = false
            publishPlayback()
            onPlaybackEnded()
        }
        failObserver = center.addObserverForName(
            AVPlayerItemFailedToPlayToEndTimeNotification,
            `object` = target,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            ended = false
            playWhenReady = false
            val message = target.error?.localizedDescription ?: "视频播放失败"
            handlePlayError(message)
        }
    }

    /** 卸载当前 item 与观察器 (不清 loadedUrl: 由调用方按语义决定; 也不销毁 AVPlayer 本体) */
    private fun unloadMedia() {
        statusObserver?.dispose()
        statusObserver = null
        bufferingObserver?.dispose()
        bufferingObserver = null
        kvoBuffering = false
        ready = false
        ended = false
        pendingStartSeekMs = 0L
        startSeekApplied = true
        val center = NSNotificationCenter.defaultCenter
        endObserver?.let { center.removeObserver(it) }
        endObserver = null
        failObserver?.let { center.removeObserver(it) }
        failObserver = null
        player?.pause()
        player?.replaceCurrentItemWithPlayerItem(null)
        item = null
    }

    private companion object {
        /**
         * AVURLAsset options 里的 HTTP 请求头 key。
         *
         * 该常量未随 Kotlin/Native platform.AVFoundation 绑定导出 (已核
         * `~/.konan/kotlin-native-prebuilt-<版本号>/klib/platform/ios_arm64/org.jetbrains.kotlin.native.platform.AVFoundation`
         * 的 linkdata: 只有 AVURLAssetHTTPCookiesKey / AVURLAssetHTTPUserAgentKey 等, 没有 HTTPHeaderFieldsKey),
         * 故按同名字面量传 —— 仓库既成写法, 见 HttpTtsPlayer.ios.kt / IosPlatformServices.kt /
         * IosAudioPlayCommander.ios.kt。
         */
        const val AV_URL_ASSET_HTTP_HEADER_FIELDS_KEY = "AVURLAssetHTTPHeaderFieldsKey"
    }
}
