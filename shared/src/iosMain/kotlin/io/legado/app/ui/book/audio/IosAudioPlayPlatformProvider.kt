package io.legado.app.ui.book.audio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// iOS 音频播放平台 UI: 渲染标题/进度/播控, AVPlayer 播控由 IosAudioPlayCommander 承载
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
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(state.title)
            Spacer(Modifier.height(8.dp))
            Text(state.subTitle)
            Spacer(Modifier.height(8.dp))
            Text("${state.progressMs} / ${state.durationMs}")
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text("上一章", modifier = Modifier.clickable { onEvent(AudioPlayUiEvent.Prev) })
                Text(
                    if (state.isPlaying) "暂停" else "播放",
                    modifier = Modifier.clickable { onEvent(AudioPlayUiEvent.TogglePlay) },
                )
                Text("下一章", modifier = Modifier.clickable { onEvent(AudioPlayUiEvent.Next) })
            }
        }
    }
}
