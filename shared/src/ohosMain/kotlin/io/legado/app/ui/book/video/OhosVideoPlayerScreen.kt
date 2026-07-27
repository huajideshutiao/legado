package io.legado.app.ui.book.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.data.entities.Book
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * 鸿蒙端视频播放页入口 (包装 shared/sharedUiMain 的 [VideoPlayerScreenContent])。
 *
 * 对照 iOS 端 [IosVideoPlayerScreen] 实现模式: 仅做平台适配, 业务展示骨架复用 shared。
 *
 * 阻塞点: videoRenderSlot 依赖平台视频播放器 (鸿蒙端原 ArkTS Video 组件 + VideoController,
 * 或 media bridge AVPlayer + surface 绑定 napi), Compose 侧未接入,
 * 本入口仅做 stub 让编译通过, 后续接入鸿蒙平台视频播放器。
 *
 * 原 ArkTS VideoPlay.ets 用 ArkUI Video 组件自管 AVPlayer (VideoController.start/pause/seek),
 * 同时注册 media bridge 事件监听器预留 surface 绑定; Compose 化后需在 ohosMain 侧实现
 * videoRenderSlot 桥接 (后续 KP)。
 *
 * @param onBack 返回回调
 * @param onOpenToc 跳转目录 (由 OhosNavHost 注入)
 * @param onOpenChangeSource 跳转切换书源 (由 OhosNavHost 注入)
 */
@Composable
fun OhosVideoPlayerScreen(
    book: Book,
    onBack: () -> Unit,
    onOpenToc: () -> Unit = {},
    onOpenChangeSource: () -> Unit = {},
) {
    // KP-ohos: 全部 stub 数据, videoRenderSlot 待接入 ArkUI Video / AVPlayer surface
    // 鸿蒙端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val prevChapterText = rememberString("ohos_prev_chapter_not_implemented")
    val nextChapterText = rememberString("ohos_next_chapter_not_implemented")
    val videoPlaybackText = rememberString("ohos_video_playback_not_implemented")
    val progressDragText = rememberString("ohos_progress_drag_not_implemented")
    val playbackSpeedText = rememberString("ohos_playback_speed_not_implemented")
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
            // KP-ohos: videoRenderSlot 依赖 ArkUI Video / AVPlayer surface, stub 空 Box
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
