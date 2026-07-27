package io.legado.app.ui.preview

// KMP @Preview 抽象: Compose 1.8.2 desktop jar 仅含 androidx.compose.desktop.ui.tooling.preview.Preview,
// 缺少 androidx.compose.ui.tooling.preview.Preview (1.11.1+ 才补齐); 各端 actual 桥接到平台原生 @Preview
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
expect annotation class AppPreview()