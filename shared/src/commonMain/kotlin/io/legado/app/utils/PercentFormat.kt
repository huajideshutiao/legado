package io.legado.app.utils

/**
 * KMP 百分比格式化 (替代 `java.util.Locale.US + String.format`)。
 *
 * 背景: `modules/shared/.../TextPage.kt` 原 `formatPercent(value: Double)` 用
 * `String.format(Locale.US, "%.1f%%", value * 100)` 输出形如 "45.6%" 的进度字符串。
 * `java.util.Locale` 在 Kotlin/Native iOS target 不可用, 阻塞 iOS 编译, 故抽出 expect/actual。
 *
 * 各平台 actual 实现策略:
 * - jvm/android: 仍用 `String.format(Locale.US, "%.1f%%", value * 100)`, 保持与原实现完全一致
 *   (Locale.US 保证小数点为 "." 不随系统区域变化)。
 * - iOS/鸿蒙: 纯 Kotlin 实现, 等价 `sprintf("%.1f%%")`, 不依赖 java.util.Locale。
 *
 * @param value 0.0~1.0 的进度值 (如 0.456 表示 45.6%)。
 * @return 形如 "45.6%" 的字符串, 小数位固定 1 位。
 */
expect fun formatPercentUs(value: Double): String
