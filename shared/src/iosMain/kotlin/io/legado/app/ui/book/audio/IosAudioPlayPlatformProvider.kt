package io.legado.app.ui.book.audio

import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString

/**
 * iOS 音频播放平台 UI: 复用 shared [SharedAudioPlayScreenContent]
 * (封面/模糊背景/歌词/弹窗 slot 全端共享, 见 AudioPlaySharedSlots.kt / LrcViewShared.kt)。
 *
 * AVPlayer 播控由 IosAudioPlayCommander 承载。
 */
object IosAudioPlayPlatformProvider : AudioPlayPlatformProvider {

    @Composable
    override fun Content(
        state: AudioPlayUiState,
        onBack: () -> Unit,
        onOpenChangeSource: () -> Unit,
        onOpenToc: () -> Unit,
        onOpenBookSourceEdit: (String) -> Unit,
        onOpenReview: () -> Unit,
        overflowActions: AudioPlayOverflowActions,
        onEvent: (AudioPlayUiEvent) -> Unit,
        sidePanelWidth: Dp,
        sidePanelVisible: Boolean,
        sidePanelKind: AudioPlaySidePanelKind?,
        sidePanelSlot: @Composable (AudioPlaySidePanelKind) -> Unit,
    ) {
        SharedAudioPlayScreenContent(
            state = state,
            onBack = onBack,
            onOpenChangeSource = onOpenChangeSource,
            onOpenToc = onOpenToc,
            onOpenBookSourceEdit = onOpenBookSourceEdit,
            onOpenReview = onOpenReview,
            overflowActions = overflowActions,
            onEvent = onEvent,
            sidePanelWidth = sidePanelWidth,
            sidePanelVisible = sidePanelVisible,
            sidePanelKind = sidePanelKind,
            sidePanelSlot = sidePanelSlot,
            // 评论入口 (reviewUrl 非空才显示; hasReview 随书源切换刷新)
            titleBarTrailingSlot = {
                if (state.hasReview) {
                    IconButton(onClick = onOpenReview) {
                        Icon(
                            painter = rememberPainter("ic_edit"),
                            contentDescription = rememberString("review"),
                            tint = Color.White,
                        )
                    }
                }
            },
        )
    }
}
