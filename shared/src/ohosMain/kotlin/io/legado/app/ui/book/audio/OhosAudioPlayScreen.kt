package io.legado.app.ui.book.audio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.help.toast.Toasters
import io.legado.app.model.AudioPlayShared
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * 鸿蒙端音频播放页入口 (包装 shared/sharedUiMain 的 [AudioPlayScreenContent])。
 *
 * 对照 iOS 端 [IosAudioPlayScreen] 实现模式: 仅做平台适配, 业务展示骨架复用 shared。
 *
 * 阻塞点: 鸿蒙端音频播放器 (AVPlayer, 原 ArkTS 经 MediaBridgeHandler napi 桥接) 与
 * 封面/歌词 slot (依赖平台图片加载/自绘 LrcView) 均未在 Compose 侧实现,
 * 本入口仅做 stub 让编译通过, 后续接入鸿蒙平台播放器与 slot 实现。
 *
 * 原 ArkTS AudioPlay.ets 通过 MediaBridgeHandler (handleMediaCommand/addMediaEventListener)
 * 驱动 AVPlayer; Compose 化后需在 ohosMain 侧实现 PlatformPlayer 桥接 (后续 KP)。
 *
 * @param onBack 返回回调
 * @param onOpenChangeSource 跳转切换书源 (由 OhosNavHost 注入)
 * @param onOpenToc 跳转目录 (由 OhosNavHost 注入)
 */
@Composable
fun OhosAudioPlayScreen(
    onBack: () -> Unit,
    onOpenChangeSource: () -> Unit = {},
    onOpenToc: () -> Unit = {},
) {
    // KP-ohos: 全部 stub 数据, AVPlayer 与封面/歌词 slot 待接入
    // 鸿蒙端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val coverSwitchText = rememberString("ohos_cover_switch_not_implemented")
    val audioPlaybackText = rememberString("ohos_audio_playback_not_implemented")
    val prevTrackText = rememberString("ohos_prev_track_not_implemented")
    val nextTrackText = rememberString("ohos_next_track_not_implemented")
    val playModeSwitchText = rememberString("ohos_play_mode_switch_not_implemented")
    val audioProgressDragText = rememberString("ohos_audio_progress_drag_not_implemented")
    val audioTimerText = rememberString("ohos_audio_timer_not_implemented")
    val audioPlaybackSpeedText = rememberString("ohos_audio_playback_speed_not_implemented")
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("audio_play"),
            onBack = onBack,
        )
        AudioPlayScreenContent(
            title = "",
            subTitle = "",
            coverUrl = null,
            coverVisible = false,
            timerMinute = 0,
            speed = 1f,
            progressMs = 0,
            durationMs = 0,
            bufferMs = 0,
            isPlaying = false,
            loading = false,
            playMode = AudioPlayShared.PlayMode.LIST_END_STOP,
            prevEnabled = false,
            nextEnabled = false,
            accentColor = io.legado.app.ui.compose.theme.AppTheme.colors.accent,
            onBack = onBack,
            onOpenChangeSource = onOpenChangeSource,
            onCoverClick = {
                Toasters.get().toast(coverSwitchText)
            },
            onTogglePlay = {
                // TODO: 鸿蒙端 AVPlayer 播放/暂停, 经 MediaBridgeHandler 桥接接入
                Toasters.get().toast(audioPlaybackText)
            },
            onPrev = {
                Toasters.get().toast(prevTrackText)
            },
            onNext = {
                Toasters.get().toast(nextTrackText)
            },
            onChangePlayMode = {
                Toasters.get().toast(playModeSwitchText)
            },
            onOpenToc = onOpenToc,
            onSeek = { _ ->
                Toasters.get().toast(audioProgressDragText)
            },
            onSetTimer = { _ ->
                Toasters.get().toast(audioTimerText)
            },
            onSetSpeed = { _ ->
                Toasters.get().toast(audioPlaybackSpeedText)
            },
            onStop = null,
            // KP-ohos: 封面/歌词 slot 依赖平台图片加载/自绘, stub 空 Box
            coverSlot = { _, _ -> Box(Modifier) },
            lrcSlot = { Box(Modifier) },
            titleBarTrailingSlot = {},
            timerDialogSlot = { _, _, _ -> },
            speedDialogSlot = { _, _, _ -> },
        )
    }
}
