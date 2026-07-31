package io.legado.desktop.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import io.legado.app.constant.AppLog
import io.legado.app.ui.book.video.VideoPlayPlatformProvider
import io.legado.app.ui.book.video.VideoPlayScreenModel
import io.legado.app.ui.book.video.VideoPlayerController
import io.legado.desktop.help.video.MpvDetector
import io.legado.desktop.help.video.MpvPlayer
import kotlinx.coroutines.delay
import java.awt.Canvas
import java.awt.Component

/**
 * desktop 端 [VideoPlayPlatformProvider] 真实实现: 外部 mpv 进程渲染。
 *
 * # 架构 (对照 desktop/help/video/MpvPlayer.kt)
 *
 * - **一个 URL = 一个 mpv 进程**: 切章/切分辨率时旧进程 quit, 新进程 start
 * - **嵌入**: Windows 经 `SwingPanel(Canvas)` + 反射拿 HWND → `--wid` 嵌入 Compose 区域;
 *   macOS/Linux 拿不到原生句柄, wid=null 走 mpv 独立窗口 (与 MpvPlayer 设计一致)
 * - **控制层**: mpv 内建 OSC (--osc=yes), 不再叠 Compose 控制层 (SwingPanel 是重量级 AWT)
 * - **IPC 桥**: 经 MpvPlayer.command 转发 playPause/seekTo/setSpeed; positionMs/durationMs
 *   由 mpv observe_property 事件持续刷新
 *
 * # 与 iOS 端 [IosVideoPlayPlatformProvider] 对照
 *
 * iOS 用 AVPlayer + AVPlayerViewController; desktop 用 mpv 外部进程, 控制语义对齐:
 * playPause → cycle pause / seekTo → seek absolute / setSpeed → set_property speed
 */
class DesktopVideoPlayPlatformProvider : VideoPlayPlatformProvider {

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController = DesktopVideoPlayerController(onPlaybackEnded)

    @Composable
    override fun Render(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    ) {
        val desktopController = controller as? DesktopVideoPlayerController ?: return
        val url = screenModel.shared.videoUrl.value?.url
        val headers = screenModel.shared.videoUrl.value?.headerMap ?: emptyMap()
        val startMs = screenModel.shared.curBook?.durChapterPos?.toLong() ?: 0L
        val bookName = screenModel.shared.curBook?.name ?: ""
        val chapterTitle = screenModel.state.value.let {
            if (it.chapterTitle.isNotEmpty()) it.chapterTitle else ""
        }

        // AWT Canvas 供 mpv --wid 嵌入 (Windows); macOS/Linux 不用, 走独立窗口
        val canvas = remember { Canvas() }
        SwingPanel(factory = { canvas }, modifier = modifier.background(Color.Black))

        // URL 变化时启动新播放器 (每 URL 一进程, 对齐 MpvPlayer 设计)
        LaunchedEffect(url) {
            if (url.isNullOrEmpty()) return@LaunchedEffect
            // 等 Canvas 可显示 (peer 就绪), 至多 2s; Windows 才能拿 HWND 嵌入
            var wid: Long? = null
            if (MpvDetector.isWindows) {
                var attempts = 0
                while (wid == null && attempts < 20) {
                    wid = canvas.nativeHwnd()
                    if (wid == null) {
                        delay(100)
                        attempts++
                    }
                }
            }
            val mediaTitle =
                if (chapterTitle.isNotEmpty()) "$bookName - $chapterTitle" else bookName
            desktopController.startPlayback(url, headers, startMs, wid, bookName, mediaTitle)
        }
    }
}

/**
 * desktop 视频控制器: 包装 [MpvPlayer], 桥接 [VideoPlayerController] 接口到 mpv IPC 命令。
 *
 * - playPause → `cycle pause`
 * - seekTo → `seek <sec> absolute`
 * - seekBy → `seek <sec> relative`
 * - setSpeed → `set_property speed <val>`
 * - positionMs/durationMs → 读 MpvPlayer.lastPosMs/durationMs (IPC observe_property 持续刷新)
 */
class DesktopVideoPlayerController(
    private val onPlaybackEnded: () -> Unit,
) : VideoPlayerController {

    private var player: MpvPlayer? = null

    /** 启动新播放 (切章/切分辨率时旧进程 quit 再 start, 对齐 MpvPlayer "一实例一播放" 设计) */
    fun startPlayback(
        url: String,
        headers: Map<String, String>,
        startMs: Long,
        wid: Long?,
        windowTitle: String,
        mediaTitle: String,
    ) {
        player?.quit(discardProgress = false)
        val mpvPath = MpvDetector.detect()
        if (mpvPath == null) {
            AppLog.put("mpv 未安装或未检测到, 无法播放视频 (设置可配置 mpvPath)", null, true)
            return
        }
        player = MpvPlayer(
            mpvPath = mpvPath,
            onEof = onPlaybackEnded,
            onPlayError = { msg -> AppLog.put("mpv playback error: $msg", null, true) },
        ).apply {
            start(
                playPath = url,
                headers = headers,
                startMs = startMs,
                wid = wid,
                windowTitle = windowTitle,
                mediaTitle = mediaTitle,
            )
        }
    }

    override val positionMs: Long get() = player?.lastPosMs ?: 0L
    override val durationMs: Long get() = player?.durationMs ?: 0L
    override val bufferedMs: Long get() = durationMs

    override fun playPause() = player?.command("cycle", "pause") ?: Unit
    override fun seekTo(positionMs: Long) =
        player?.command("seek", positionMs / 1000.0, "absolute") ?: Unit

    override fun seekBy(deltaMs: Long) =
        player?.command("seek", deltaMs / 1000.0, "relative") ?: Unit

    override fun setSpeed(speed: Float) =
        player?.command("set_property", "speed", speed) ?: Unit

    override fun seekBack() = seekBy(-10000)
    override fun seekForward() = seekBy(10000)

    override fun release() {
        player?.quit(discardProgress = false)
        player = null
    }
}

/**
 * Windows HWND 提取: 经反射读 `sun.awt.windows.WComponentPeer.getHwnd()`。
 *
 * - Canvas peer 在组件可显示后才非 null (SwingPanel factory 返回后, AWT 容器挂载后)
 * - macOS/Linux 返回 null (MpvPlayer wid=null 走独立窗口)
 */
private fun Canvas.nativeHwnd(): Long? {
    // Component.peer 包私有, 经反射访问
    val peer = runCatching {
        Component::class.java.getDeclaredField("peer").apply { isAccessible = true }.get(this)
    }?.getOrNull() ?: return null
    if (!MpvDetector.isWindows) return null
    return runCatching {
        val clazz = Class.forName("sun.awt.windows.WComponentPeer")
        val method = clazz.getMethod("getHwnd")
        (method.invoke(peer) as? Number)?.toLong()
    }.getOrNull()
}
