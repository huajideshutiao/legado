package io.legado.app.utils

/**
 * 字符集检测 actual (iOS/鸿蒙简化版, nativeMain 中间源集共用)。
 *
 * iOS/鸿蒙两端 actual 实现完全一致, 下沉到 nativeMain 共用。
 *
 * 实现 BOM 检测 + 字节合法性启发式, 覆盖常见编码:
 * - BOM: UTF-8 (EF BB BF), UTF-16LE (FF FE), UTF-16BE (FE FF), UTF-32LE/BE
 * - 无 BOM: UTF-8 严格校验 + GB18030/GBK/Big5 字节范围合法性判定
 *   (简化 icu4j gb18030/big5 recognizer, 无频率表: GB 专属尾字节/4 字节序列直接判定 GBK,
 *   Big5 尾字节是 GB 子集时用低尾字节占比消歧)
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
    // 合法性启发式 (简化 icu4j gb18030/big5 recognizer 的字节范围判定, 无频率表):
    // - GB18030/GBK 双字节: 首字节 0x81-0xFE, 尾字节 0x40-0xFE (不含 0x7F);
    //   4 字节扩展: 首 0x81-0xFE, 次 0x30-0x39, 三 0x81-0xFE, 四 0x30-0x39
    // - Big5 双字节: 首字节 0x81-0xFE, 尾字节 0x40-0x7E 或 0xA1-0xFE
    // 判定:
    //   出现 GB 专属尾字节 (0x80/0xFF) 或合法 4 字节序列 → GBK (Big5 不可能)
    //   双字节合法比例 < 60% → 非 GB/Big5 → 回退 UTF-8
    //   两者均可能时用低尾字节 (0x40-0x7E) 占比消歧: Big5 常用字尾字节多落在低区, GBK 偏均匀
    val sampleSize = minOf(bytes.size, 8192)
    var pairs = 0          // 高字节首字节双字节对总数
    var gbValid = 0        // 合法 GB18030 双字节 (含 4 字节序列)
    var big5Valid = 0      // 合法 Big5 双字节
    var gbOnlyTrail = 0    // GB 专属尾字节 (0x80/0xFF)
    var gb4Seq = 0         // 合法 GB18030 4 字节序列
    var lowTrail = 0       // 尾字节落在 0x40-0x7E (Big5 常用字特征)
    var i = 0
    while (i < sampleSize) {
        val b1 = bytes[i].toInt() and 0xFF
        if (b1 < 0x80) {
            i++
            continue
        }
        if (b1 !in 0x81..0xFE) {
            // 孤立高字节 (0x80, 0xA0 等) 不是合法双字节编码首字节
            i++
            continue
        }
        if (i + 1 >= sampleSize) break
        val b2 = bytes[i + 1].toInt() and 0xFF
        // GB18030 4 字节扩展: 第二字节 0x30-0x39
        if (b2 in 0x30..0x39) {
            if (i + 3 < sampleSize) {
                val b3 = bytes[i + 2].toInt() and 0xFF
                val b4 = bytes[i + 3].toInt() and 0xFF
                if (b3 in 0x81..0xFE && b4 in 0x30..0x39) {
                    pairs++
                    gbValid++
                    gb4Seq++
                    i += 4
                    continue
                }
            }
            // 4 字节序列不完整/非法: b2(0x30-0x39) 不是 GB/Big5 合法尾字节, 按无效对处理 —
            // pairs 与 valid 口径一致 (无效对分子分母均不计), 避免含 GB18030 扩展的文本被拉低 GB 命中率
            i += 2
            continue
        }
        pairs++
        val gbOk = b2 in 0x40..0xFE && b2 != 0x7F
        val big5Ok = b2 in 0x40..0x7E || b2 in 0xA1..0xFE
        if (gbOk) {
            gbValid++
            if (b2 == 0x80 || b2 == 0xFF) gbOnlyTrail++
        }
        if (big5Ok) {
            big5Valid++
            if (b2 in 0x40..0x7E) lowTrail++
        }
        i += 2
    }
    if (pairs < sampleSize / 20) return "UTF-8" // 高字节占比过低, 视为纯文本/UTF-8
    // GB 专属特征 → 只能是 GBK
    if (gbOnlyTrail > 0 || gb4Seq > 0) {
        return if (gbValid.toDouble() / pairs >= 0.6) "GBK" else "UTF-8"
    }
    val gbRate = gbValid.toDouble() / pairs
    val big5Rate = big5Valid.toDouble() / pairs
    if (gbRate >= 0.6 && big5Rate >= 0.6) {
        // 两者均可能 (Big5 尾字节是 GB 子集): 低尾字节 (0x40-0x7E) 占比 > 25% 偏 Big5
        // (实测: GBK 文本低尾占比 0-7%, Big5 文本 40-67%)
        return if (lowTrail.toDouble() / pairs > 0.25) "Big5" else "GBK"
    }
    return when {
        gbRate >= 0.6 -> "GBK"
        big5Rate >= 0.6 -> "Big5"
        else -> "UTF-8"
    }
}
