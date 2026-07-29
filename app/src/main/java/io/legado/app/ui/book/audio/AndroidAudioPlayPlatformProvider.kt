package io.legado.app.ui.book.audio

import androidx.compose.runtime.Composable

class AndroidAudioPlayPlatformProvider : AudioPlayPlatformProvider {
    @Composable
    override fun Content(
        state: AudioPlayUiState,
        onBack: () -> Unit,
        onOpenChangeSource: () -> Unit,
        onOpenToc: () -> Unit,
        onOpenBookSourceEdit: (String) -> Unit,
        onOpenReview: () -> Unit,
        onEvent: (AudioPlayUiEvent) -> Unit,
    ) {
        AudioPlayAndroidContent(
            state = state,
            onBack = onBack,
            onOpenChangeSource = onOpenChangeSource,
            onOpenToc = onOpenToc,
            onOpenBookSourceEdit = onOpenBookSourceEdit,
            onOpenReview = onOpenReview,
            onEvent = onEvent,
        )
    }
}
