package io.legado.app.utils

import kotlin.math.roundToLong

/**
 * StringUtils 平台相关 actual (iOS / 鸿蒙)。
 *
 * 详见 commonMain/utils/StringUtilsPlatform.kt expect 注释。
 * - [createWordCountFormatter]: 纯 Kotlin 数字格式化 (1 位小数 + 去末尾 0), 与 jvmAndAndroidMain `DecimalFormat("#.#")` 字节级一致
 */
internal actual fun createWordCountFormatter(): (Double) -> String = { value ->
    if (value.isNaN() || value.isInfinite()) {
        value.toString()
    } else {
        // HALF_EVEN 四舍五入到 1 位小数, 去末尾 0
        val scaled = (value * 10.0).roundToLong()
        val intPart = scaled / 10
        val fracPart = (scaled % 10).toInt().let { if (it < 0) -it else it }
        val sign = if (value < 0 && intPart == 0L) "-" else ""
        if (fracPart == 0) {
            "$sign$intPart"
        } else {
            "$sign$intPart.$fracPart"
        }
    }
}
