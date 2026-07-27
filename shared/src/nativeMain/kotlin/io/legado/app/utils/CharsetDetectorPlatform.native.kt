package io.legado.app.utils

/**
 * 字符集检测 actual (iOS/鸿蒙简化版, nativeMain 中间源集共用)。
 *
 * iOS/鸿蒙两端 actual 实现完全一致, 下沉到 nativeMain 共用。
 *
 * 实现 BOM 检测 + 简单字节频率分析, 覆盖常见编码:
 * - BOM: UTF-8 (EF BB BF), UTF-16LE (FF FE), UTF-16BE (FE FF), UTF-32LE/BE
 * - 无 BOM: UTF-8 严格校验 + GBK/Big5 启发式 (高字节频率)
 * - fallback: UTF-8 (与 jvmAndAndroidMain 行为一致)
 */
internal actual fun detectCharsetName(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return null
    // 1. BOM 检测
    detectBom(bytes)?.let { return it }
    // 2. UTF-8 严格校验
    if (isStrictUtf8(bytes)) return "UTF-8"
    // 3. 简单启发式: 高字节分布
    return detectByFrequency(bytes)
}

private fun detectBom(bytes: ByteArray): String? {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return "UTF-8"
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        if (bytes.size >= 4 && bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()) return "UTF-32LE"
        return "UTF-16LE"
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        if (bytes.size >= 4 && bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()) return "UTF-32BE"
        return "UTF-16BE"
    }
    return null
}

private fun isStrictUtf8(bytes: ByteArray): Boolean {
    var i = 0
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        when {
            b < 0x80 -> i++ // ASCII
            b in 0x80..0xBF -> return false // 孤立 continuation byte
            b in 0xC0..0xDF -> { // 2-byte
                if (i + 1 >= bytes.size || (bytes[i+1].toInt() and 0xC0) != 0x80) return false
                i += 2
            }
            b in 0xE0..0xEF -> { // 3-byte
                if (i + 2 >= bytes.size) return false
                for (j in 1..2) if ((bytes[i+j].toInt() and 0xC0) != 0x80) return false
                i += 3
            }
            b in 0xF0..0xF7 -> { // 4-byte
                if (i + 3 >= bytes.size) return false
                for (j in 1..3) if ((bytes[i+j].toInt() and 0xC0) != 0x80) return false
                i += 4
            }
            else -> return false
        }
    }
    return true
}

private fun detectByFrequency(bytes: ByteArray): String? {
    // 简化启发式: 统计高字节分布
    // GBK: 双字节, 第一字节 0x81-0xFE, 第二字节 0x40-0xFE
    // Big5: 双字节, 第一字节 0x81-0xFE, 第二字节 0x40-0x7E + 0xA1-0xFE
    // 这里只做粗略判断, fallback 返回 UTF-8 (jvm 端 icu4j 也会 fallback)
    var gbkLike = 0
    var big5Like = 0
    var i = 0
    val sampleSize = minOf(bytes.size, 4096)
    while (i < sampleSize - 1) {
        val b1 = bytes[i].toInt() and 0xFF
        if (b1 in 0x81..0xFE) {
            val b2 = bytes[i+1].toInt() and 0xFF
            if (b2 in 0x40..0x7E) {
                // GBK 和 Big5 都可能
                gbkLike++; big5Like++
            } else if (b2 in 0xA1..0xFE) {
                // GBK 和 Big5 都可能, 略偏 Big5
                gbkLike++; big5Like += 2
            } else if (b2 in 0x80..0xFE) {
                gbkLike++
            }
            i += 2
        } else {
            i++
        }
    }
    // 启发式阈值: 至少 10% 双字节序列, 否则视为 UTF-8
    val totalPairs = gbkLike + big5Like
    if (totalPairs < sampleSize / 20) return "UTF-8"
    return if (big5Like > gbkLike * 2) "Big5" else "GBK"
}
