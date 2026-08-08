package io.legado.app.ui.book.audio

import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString

/**
 * Android 端音频播放页 Composable: 复用 shared [SharedAudioPlayScreenContent],
 * 仅注入 app 端差异 (评论钮 + app 端默认值); 封面/模糊背景/歌词/弹窗 slot 全端共享
 * (见 AudioPlaySharedSlots.kt / LrcViewShared.kt)。
 */
@Composable
fun AudioPlayAndroidContent(
    state: AudioPlayUiState,
    onBack: () -> Unit,
    onOpenChangeSource: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenBookSourceEdit: (String) -> Unit,
    onOpenReview: () -> Unit,
    overflowActions: AudioPlayOverflowActions,
    onEvent: (AudioPlayUiEvent) -> Unit,
    sidePanelWidth: Dp = 0.dp,
    sidePanelVisible: Boolean = false,
    sidePanelKind: AudioPlaySidePanelKind? = null,
    sidePanelSlot: @Composable (AudioPlaySidePanelKind) -> Unit = {},
    onTapOutsideSidePanel: (() -> Unit)? = null,
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
        titleBarTrailingSlot = {
            // 评论入口 (reviewUrl 非空才显示; hasReview 随书源切换刷新)
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
        // app 端默认值 (对照 AudioPlayScreenContent.kt 注释)
        timerIconKey = "ic_timer_black_24dp",
        speedIconKey = "ic_fast_forward",
        chapterListIconKey = "ic_chapter_list",
        filletLabelColor = rememberColor("arco_fill_3"),
        playMenuButtonPressedBgEnabled = true,
        playMenuAlpha = 0.7f,
        titleBarHorizontalPadding = 0.dp,
        playModeIconPadding = 8.dp,
    )
}
