package io.legado.app.ui.book.audio

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

class AndroidAudioPlayPlatformProvider : AudioPlayPlatformProvider {
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
        AudioPlayAndroidContent(
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
        )
    }
}
