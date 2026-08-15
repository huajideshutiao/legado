package io.legado.app.ui.book.audio

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * iOS 音频播放平台 UI: 复用 shared [SharedAudioPlayScreenContent]
 * (封面/模糊背景/歌词/弹窗 slot 全端共享, 见 AudioPlaySharedSlots.kt / LrcViewShared.kt)。
 *
 * 视觉参数已全部收拢为 shared 默认 (评论钮/图标/回显标签底色/透明度/内边距/按压底),
 * 本类与 app/desktop/鸿蒙 端同为纯透传。AVPlayer 播控由 IosAudioPlayCommander 承载。
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
        onTapOutsideSidePanel: (() -> Unit)?,
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
            onTapOutsideSidePanel = onTapOutsideSidePanel,
            // 评论钮已收拢为 SharedAudioPlayScreenContent 默认 (见 AudioPlaySharedSlots.kt)
        )
    }
}
