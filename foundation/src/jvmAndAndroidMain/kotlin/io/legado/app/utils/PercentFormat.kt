package io.legado.app.utils

import java.util.Locale

/**
 * `formatPercentUs` 的 jvmAndAndroidMain actual 实现。
 *
 * 直接委托 JDK `String.format`, Locale.US 保证小数点为 "." 不随系统区域变化,
 * 与原 TextPage.formatPercent 实现完全一致 (包括 "%.1f%%" 格式与 HALF_EVEN 舍入)。
 */
actual fun formatPercentUs(value: Double): String =
    String.format(Locale.US, "%.1f%%", value * 100)
