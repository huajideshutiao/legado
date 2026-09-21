package io.legado.app.ui.root

import androidx.compose.animation.SharedTransitionScope.ResizeMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale

internal actual val photoSharedResizeMode: ResizeMode =
    ResizeMode.scaleToBounds(ContentScale.Fit, Alignment.Center)
