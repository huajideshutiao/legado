package io.legado.app.ui.book.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.legado.app.model.AudioPlayShared
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [AudioPlayScreenContent] 的 @Preview。
 *
 * coverSlot/lrcSlot/对话框 slot 均用占位实现 (真实实现依赖 Coil/平台歌词组件)。
 */

private val previewAudioCoverSlot: @Composable (String?, Modifier) -> Unit = { _, modifier ->
    Box(
        modifier.background(Color(0xFF34495E), DesignTokens.shapeDefault),
        contentAlignment = Alignment.Center,
    ) {
        Text("封面", color = Color.White)
    }
}

private val previewLrcSlot: @Composable (Modifier) -> Unit = { modifier ->
    Box(modifier, contentAlignment = Alignment.Center) {
        Text("这里是歌词滚动区", color = Color(0xFF888888))
    }
}

@AppPreview
@Composable
fun AudioPlayScreenContentPlayingPreview() = LegadoThemePreview {
    AudioPlayScreenContent(
        title = "三体(有声剧)",
        subTitle = "第十二章 黑暗森林",
        coverUrl = null,
        coverVisible = true,
        timerMinute = 0,
        speed = 1.0f,
        progressMs = 754_000,
        durationMs = 1_800_000,
        bufferMs = 1_200_000,
        isPlaying = true,
        loading = false,
        playMode = AudioPlayShared.PlayMode.LIST_LOOP,
        prevEnabled = true,
        nextEnabled = true,
        accentColor = Color(0xFF165DFF),
        onBack = {},
        onOpenChangeSource = {},
        onCoverClick = {},
        onTogglePlay = {},
        onPrev = {},
        onNext = {},
        onChangePlayMode = {},
        onOpenToc = {},
        onSeek = {},
        onSetTimer = {},
        onSetSpeed = {},
        coverSlot = previewAudioCoverSlot,
        lrcSlot = previewLrcSlot,
        timerDialogSlot = { _, _, _ -> },
        speedDialogSlot = { _, _, _ -> },
    )
}

@AppPreview
@Composable
fun AudioPlayScreenContentPausedLoadingPreview() = LegadoThemePreview {
    AudioPlayScreenContent(
        title = "三体(有声剧)",
        subTitle = "第十三章 面壁者",
        coverUrl = null,
        coverVisible = true,
        timerMinute = 30,
        speed = 1.5f,
        progressMs = 0,
        durationMs = 0,
        bufferMs = 0,
        isPlaying = false,
        loading = true,
        playMode = AudioPlayShared.PlayMode.SINGLE_LOOP,
        prevEnabled = false,
        nextEnabled = true,
        accentColor = Color(0xFF165DFF),
        onBack = {},
        onOpenChangeSource = {},
        onCoverClick = {},
        onTogglePlay = {},
        onPrev = {},
        onNext = {},
        onChangePlayMode = {},
        onOpenToc = {},
        onSeek = {},
        onSetTimer = {},
        onSetSpeed = {},
        onStop = {},
        coverSlot = previewAudioCoverSlot,
        lrcSlot = previewLrcSlot,
        timerDialogSlot = { _, _, _ -> },
        speedDialogSlot = { _, _, _ -> },
    )
}

@AppPreview
@Composable
fun AudioPlayScreenContentDarkPreview() = LegadoThemePreview(dark = true) {
    AudioPlayScreenContent(
        title = "三体(有声剧)",
        subTitle = "第十二章 黑暗森林",
        coverUrl = null,
        coverVisible = true,
        timerMinute = 0,
        speed = 1.0f,
        progressMs = 754_000,
        durationMs = 1_800_000,
        bufferMs = 1_500_000,
        isPlaying = true,
        loading = false,
        playMode = AudioPlayShared.PlayMode.RANDOM,
        prevEnabled = true,
        nextEnabled = true,
        accentColor = Color(0xFF165DFF),
        onBack = {},
        onOpenChangeSource = {},
        onCoverClick = {},
        onTogglePlay = {},
        onPrev = {},
        onNext = {},
        onChangePlayMode = {},
        onOpenToc = {},
        onSeek = {},
        onSetTimer = {},
        onSetSpeed = {},
        coverSlot = previewAudioCoverSlot,
        lrcSlot = previewLrcSlot,
        timerDialogSlot = { _, _, _ -> },
        speedDialogSlot = { _, _, _ -> },
    )
}
