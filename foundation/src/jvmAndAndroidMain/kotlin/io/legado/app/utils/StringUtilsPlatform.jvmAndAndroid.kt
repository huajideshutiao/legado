package io.legado.app.utils

import java.text.DecimalFormat

/**
 * StringUtils 平台相关 actual (jvmAndAndroid)。
 *
 * 详见 commonMain/utils/StringUtilsPlatform.kt expect 注释。
 */
internal actual fun createWordCountFormatter(): (Double) -> String {
    val df = DecimalFormat("#.#")
    return { value -> df.format(value) }
}
