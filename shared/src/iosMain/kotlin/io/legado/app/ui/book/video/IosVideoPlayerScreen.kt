package io.legado.app.ui.book.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * iOS 端视频播放页入口 (包装 shared/sharedUiMain 的 [VideoPlayerScreenContent])。
 *
 * 阻塞点: videoRenderSlot 依赖平台视频播放器 (iOS 端 AVPlayerLayer / VideoPlayer 未接入),
 * 本入口仅做 stub 让编译通过, 后续 KP6+ 接入 iOS 平台视频播放器。
 *
 * @param onBack 返回回调
 * @param onOpenToc 跳转目录 (由 IosNavHost 注入)
 * @param onOpenChangeSource 跳转切换书源 (由 IosNavHost 注入)
 */
@Composable
fun IosVideoPlayerScreen(
    onBack: () -> Unit,
    onOpenToc: () -> Unit = {},
    onOpenChangeSource: () -> Unit = {},
) {
    // KP-iOS: 全部 stub 数据, videoRenderSlot 待接入 AVPlayerLayer
    // iOS 端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val prevChapterText = rememberString("ios_prev_chapter_not_implemented")
    val nextChapterText = rememberString("ios_next_chapter_not_implemented")
    val videoPlaybackText = rememberString("ios_video_playback_not_implemented")
    val progressDragText = rememberString("ios_progress_drag_not_implemented")
    val playbackSpeedText = rememberString("ios_playback_speed_not_implemented")
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("video_play"),
            onBack = onBack,
        )
        VideoPlayerScreenContent(
            bookName = "",
            chapterTitle = "",
            curChapterIndex = 0,
            chapterSize = 0,
            onBack = onBack,
            onOpenToc = onOpenToc,
            onOpenChangeSource = onOpenChangeSource,
            onPrevChapter = {
                Toasters.get().toast(prevChapterText)
            },
            onNextChapter = {
                Toasters.get().toast(nextChapterText)
            },
            // KP-iOS: videoRenderSlot 依赖 AVPlayerLayer, stub 空 Box
            videoRenderSlot = { Box(Modifier) },
            onPlayPause = {
                Toasters.get().toast(videoPlaybackText)
            },
            onSeekDelta = { _ ->
                Toasters.get().toast(progressDragText)
            },
            onSpeedChange = { _ ->
                Toasters.get().toast(playbackSpeedText)
            },
        )
    }
}
