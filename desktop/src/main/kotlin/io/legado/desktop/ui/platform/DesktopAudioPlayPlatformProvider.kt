package io.legado.desktop.ui.platform

import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import io.legado.app.ui.book.audio.AudioPlayOverflowActions
import io.legado.app.ui.book.audio.AudioPlayPlatformProvider
import io.legado.app.ui.book.audio.AudioPlaySidePanelKind
import io.legado.app.ui.book.audio.AudioPlayUiEvent
import io.legado.app.ui.book.audio.AudioPlayUiState
import io.legado.app.ui.book.audio.SharedAudioPlayScreenContent
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString

/**
 * desktop 端 [AudioPlayPlatformProvider] 实现: 复用 shared [SharedAudioPlayScreenContent]
 * (封面/模糊背景/歌词/弹窗 slot 全端共享, 见 AudioPlaySharedSlots.kt / LrcViewShared.kt)。
 *
 * 播放引擎由 [io.legado.desktop.audio.DesktopAudioPlayProvider] (AudioPlayCommander/Bridge) 承载,
 * 此处仅提供音频页 UI Content, 与 app 端 AudioPlayScreen 职责对齐。
 */
class DesktopAudioPlayPlatformProvider : AudioPlayPlatformProvider {

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
