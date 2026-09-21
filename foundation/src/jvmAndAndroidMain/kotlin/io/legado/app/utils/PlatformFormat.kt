package io.legado.app.utils

import java.util.Locale

/**
 * String.format(Locale.ROOT, "%.0f", value) 的 jvmAndAndroidMain actual 实现。
 *
 * 详见 commonMain/utils/PlatformFormat.kt expect 注释。
 * 直接委托 JDK String.format, Locale.ROOT 保证小数点为 "." 不随系统区域变化。
 */
actual fun formatDoubleNoDecimal(value: Double): String =
    String.format(Locale.ROOT, "%.0f", value)

/**
 * [String.format] 的 jvmAndAndroidMain actual: 直接委托 JDK Formatter (与原行为零 diff)。
 * Locale.ROOT 保证小数点/分组符不随系统区域变化。
 */
actual fun String.format(vararg args: Any?): String =
    java.lang.String.format(Locale.ROOT, this, *args)
