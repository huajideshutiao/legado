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
 * iOS 端音频播放页入口 (包装 shared/sharedUiMain 的 [AudioPlayScreenContent])。
 *
 * 阻塞点: TTS/音频播放器 (AVPlayer) 与封面/歌词 slot (依赖平台图片加载/自绘 LrcView)
 * 均未在 iOS 端实现, 本入口仅做 stub 让编译通过, 后续 KP6+ 接入 iOS 平台播放器与 slot 实现。
 *
 * @param onBack 返回回调
 * @param onOpenChangeSource 跳转切换书源 (由 IosNavHost 注入)
 * @param onOpenToc 跳转目录 (由 IosNavHost 注入)
 */
@Composable
fun IosAudioPlayScreen(
    onBack: () -> Unit,
    onOpenChangeSource: () -> Unit = {},
    onOpenToc: () -> Unit = {},
) {
    // KP-iOS: 全部 stub 数据, AVPlayer 与封面/歌词 slot 待接入
    // iOS 端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val coverSwitchText = rememberString("ios_cover_switch_not_implemented")
    val audioPlaybackText = rememberString("ios_audio_playback_not_implemented")
    val prevTrackText = rememberString("ios_prev_track_not_implemented")
    val nextTrackText = rememberString("ios_next_track_not_implemented")
    val playModeSwitchText = rememberString("ios_play_mode_switch_not_implemented")
    val audioProgressDragText = rememberString("ios_audio_progress_drag_not_implemented")
    val audioTimerText = rememberString("ios_audio_timer_not_implemented")
    val audioPlaybackSpeedText = rememberString("ios_audio_playback_speed_not_implemented")
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
                // TODO: iOS 端 AVPlayer 播放/暂停, KP6+ 接入
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
            // KP-iOS: 封面/歌词 slot 依赖平台图片加载/自绘, stub 空 Box
            coverSlot = { _, _ -> Box(Modifier) },
            lrcSlot = { Box(Modifier) },
            titleBarTrailingSlot = {},
            timerDialogSlot = { _, _, _ -> },
            speedDialogSlot = { _, _, _ -> },
        )
    }
}
