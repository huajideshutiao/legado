package io.legado.app.ui.book.audio

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * 音频播放页平台 UI provider 的共享默认实现 (2026-08 去重)。
 *
 * 原 app/desktop/iOS/鸿蒙 四端各有一份逐字相同的纯透传副本
 * (AndroidAudioPlayPlatformProvider / DesktopAudioPlayPlatformProvider /
 * IosAudioPlayPlatformProvider / OhosAudioPlayPlatformProvider), 视觉参数收拢后
 * 已零平台差异, 现收敛为本对象, 四端宿主统一注册:
 * - app MainActivity → `AudioPlayPlatformProviders.register(SharedAudioPlayPlatformProvider)`
 * - desktop Main.kt → 同上
 * - iOS MainViewController / ohos MainOhos → 同上
 *
 * 复用 shared [SharedAudioPlayScreenContent] (封面/模糊背景/歌词/弹窗 slot 全端共享,
 * 见 AudioPlaySharedSlots.kt / LrcViewShared.kt); 歌词取色 [rememberLrcColors] 同源。
 * 鸿蒙未注册 BookImageLoaders (coil3 无 ohosArm64 变体) 时, 封面 slot 走
 * getOrNull 回退直接显示内置默认封面 (见 SharedAudioCoverSlot)。
 */
object SharedAudioPlayPlatformProvider : AudioPlayPlatformProvider {

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
