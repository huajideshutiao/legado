package io.legado.app.ui.root

import androidx.compose.animation.SharedTransitionScope.ResizeMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale

// CPF fork CMP 1.9.2-0.5.0-25 尚未跟进官方 1.11 的 scaleToBounds 重命名, 这里仍是大驼峰。
internal actual val photoSharedResizeMode: ResizeMode =
    ResizeMode.ScaleToBounds(ContentScale.Fit, Alignment.Center)
