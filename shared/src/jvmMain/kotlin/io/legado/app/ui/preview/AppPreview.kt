package io.legado.app.ui.preview

import androidx.compose.desktop.ui.tooling.preview.Preview

// Desktop/JVM: Compose 1.8.2 desktop jar 仅含 androidx.compose.desktop.ui.tooling.preview.Preview,
// 桥接到该平台原生 @Preview (IDE 渲染 + SOURCE 保留)
actual typealias AppPreview = Preview
