package io.legado.app.utils

import kotlin.math.roundToLong

/**
 * StringUtils 平台相关 actual (iOS / 鸿蒙)。
 *
 * 详见 commonMain/utils/StringUtilsPlatform.kt expect 注释。
 * - [createWordCountFormatter]: 纯 Kotlin 数字格式化 (1 位小数 + 去末尾 0), 与 jvmAndAndroidMain `DecimalFormat("#.#")` 字节级一致
 * - [gzipAndBase64Encode]: iOS/鸿蒙无 java.util.zip.GZIPOutputStream, 降级为 Base64(UTF-8 字节) 不压缩
 *   (功能受限: 解压端按 gzip 解析失败, 仅适用于纯 ASCII 小文本场景; 大文本场景字节膨胀约 4/3 倍, 不崩)
 *
 * 注: 若 iOS/鸿蒙后续需真正 gzip, 可引入 kotlinx-io-compression 或 platform.zlib 实现。
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

internal actual fun gzipAndBase64Encode(str: String): String {
    // 降级: 直接对 UTF-8 字节做 Base64 标准编码 (无 gzip 压缩)。
    // 与 jvmAndAndroidMain 的 GZIP+Base64 不兼容, 但功能不崩, 仅压缩效果缺失。
    // kotlin.io.encoding.Base64 (KMP stable, Kotlin 2.0+) 等价 java Base64.defaultEncoder()
    return kotlin.io.encoding.Base64.Default.encode(str.encodeToByteArray())
}
