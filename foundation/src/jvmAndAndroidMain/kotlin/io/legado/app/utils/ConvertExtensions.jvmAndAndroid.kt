package io.legado.app.utils

import java.text.DecimalFormat

/**
 * ConvertExtensions 平台 actual (jvmAndAndroid).
 *
 * 详见 commonMain/utils/ConvertExtensions.kt 的 expect 注释。
 * 委托 `java.text.DecimalFormat("#,##0.##")` 保留原 app 端格式化行为
 * (千位分隔符 + 最多 2 位小数, RoundingMode HALF_EVEN)。
 */
internal actual fun formatFileSizeDecimal(value: Double): String {
    return DecimalFormat("#,##0.##").format(value)
}
