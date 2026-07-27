package io.legado.app.utils

import io.legado.app.exception.NoStackTraceException

/**
 * [TextCharsetCodec] nativeMain actual (iOS/鸿蒙共用)。
 *
 * 纯 Kotlin 实现: UTF-8 / UTF-16LE / UTF-16BE / UTF-16(BOM) / UTF-32 系 / ISO-8859-1 / US-ASCII;
 * GBK/GB2312/GB18030/Big5 经 [platformDecodeCjk]/[platformEncodeCjk] 分端下沉平台 API
 * (iOS: NSString GB18030/Big5; 鸿蒙: @ohos.util TextDecoder/TextEncoder napi 桥),
 * 平台能力缺失 (鸿蒙桥未注册) 时保持"暂不支持请转码"报错而非乱码。
 *
 * 与 JVM 宽松解码的已知降级差异: UTF-16 系不校验孤立代理项 (JVM 替换为 U+FFFD, 此处原样保留),
 * 仅影响损坏文件的显示字符, 章节偏移按字节计不受影响。
 */
internal actual fun textCharsetCodec(name: String): TextCharsetCodec {
    return when (name.trim().uppercase().replace("_", "-")) {
        "UTF-8", "UTF8" -> Utf8Codec
        "UTF-16LE", "UTF16LE", "X-UTF-16LE", "UNICODELITTLEUNMARKED" -> Utf16Codec(true, "UTF-16LE")
        "UTF-16BE", "UTF16BE", "X-UTF-16BE", "UNICODEBIGUNMARKED" -> Utf16Codec(false, "UTF-16BE")
        "UTF-16", "UTF16", "UNICODE" -> Utf16BomCodec
        "UTF-32LE", "UTF32LE", "X-UTF-32LE" -> Utf32Codec(true, "UTF-32LE")
        "UTF-32BE", "UTF32BE", "X-UTF-32BE" -> Utf32Codec(false, "UTF-32BE")
        "UTF-32", "UTF32" -> Utf32BomCodec
        "ISO-8859-1", "ISO8859-1", "LATIN1" -> Latin1Codec
        "US-ASCII", "ASCII" -> AsciiCodec
        // GBK 系一律走 GB18030 (GBK/GB2312 的超集, 双字节区映射一致, 字节长度不变;
        // 解码/编码同用 GB18030 保证 TextFileCore 的 decode→encode 往返字节数与原文件一致)
        "GBK", "X-GBK" -> CjkCodec("GBK", PlatformCjkCharset.GB18030)
        "GB2312", "GB-2312", "EUC-CN", "X-EUC-CN" -> CjkCodec("GB2312", PlatformCjkCharset.GB18030)
        "GB18030", "GB-18030" -> CjkCodec("GB18030", PlatformCjkCharset.GB18030)
        "BIG5", "BIG-5" -> CjkCodec("Big5", PlatformCjkCharset.BIG5)
        else -> throw NoStackTraceException(
            "iOS/鸿蒙端暂不支持 $name 编码的 TXT 解析, 请先将文件转码为 UTF-8"
        )
    }
}

/** CJK 平台解码目标 ([transportName] 为鸿蒙 @ohos.util TextDecoder/TextEncoder 的 encoding 名)。 */
internal enum class PlatformCjkCharset(val transportName: String) {
    GB18030("gb18030"),
    BIG5("big5"),
}

/**
 * 平台 CJK 解码 (iosMain: NSString+CoreFoundation; ohosMain: @ohos.util.TextDecoder napi 桥)。
 *
 * @return 解码结果; null = 平台能力缺失 (鸿蒙桥未注册/超时), 由调用方抛"暂不支持请转码";
 *   平台有能力但字节流坏到无法解码时由 actual 自行抛异常 (含明确文件损坏提示)。
 */
internal expect fun platformDecodeCjk(
    bytes: ByteArray, offset: Int, length: Int, charset: PlatformCjkCharset
): String?

/** 平台 CJK 编码, 语义同 [platformDecodeCjk] (null = 平台能力缺失)。 */
internal expect fun platformEncodeCjk(str: String, charset: PlatformCjkCharset): ByteArray?

/**
 * GBK 系/Big5 编解码: 转发 [platformDecodeCjk]/[platformEncodeCjk] 分端实现。
 * 平台能力缺失 (返回 null) 时保持原"暂不支持请转码"文案。
 */
private class CjkCodec(
    override val name: String,
    private val charset: PlatformCjkCharset,
) : TextCharsetCodec {

    override fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        if (length <= 0) return ""
        return platformDecodeCjk(bytes, offset, length, charset) ?: throw NoStackTraceException(
            "iOS/鸿蒙端暂不支持 $name 编码的 TXT 解析, 请先将文件转码为 UTF-8"
        )
    }

    override fun encode(str: String): ByteArray {
        if (str.isEmpty()) return ByteArray(0)
        return platformEncodeCjk(str, charset) ?: throw NoStackTraceException(
            "iOS/鸿蒙端暂不支持 $name 编码的 TXT 解析, 请先将文件转码为 UTF-8"
        )
    }
}

private object Utf8Codec : TextCharsetCodec {
    override val name: String get() = "UTF-8"

    override fun decode(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.decodeToString(offset, offset + length)

    override fun encode(str: String): ByteArray = str.encodeToByteArray()
}

/** UTF-16 定字节序编解码 (2 字节/码元, 奇数尾字节替换为 U+FFFD, 对齐 JVM 宽松解码)。 */
private class Utf16Codec(
    private val littleEndian: Boolean,
    override val name: String,
) : TextCharsetCodec {

    override fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length / 2 + 1)
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val code = if (littleEndian) (b1 shl 8) or b0 else (b0 shl 8) or b1
            sb.append(code.toChar())
            i += 2
        }
        if (i < end) sb.append('�')
        return sb.toString()
    }

    override fun encode(str: String): ByteArray {
        val out = ByteArray(str.length * 2)
        for (i in str.indices) {
            val code = str[i].code
            if (littleEndian) {
                out[i * 2] = (code and 0xFF).toByte()
                out[i * 2 + 1] = (code ushr 8).toByte()
            } else {
                out[i * 2] = (code ushr 8).toByte()
                out[i * 2 + 1] = (code and 0xFF).toByte()
            }
        }
        return out
    }
}

/** 无标记 UTF-16: 解码按 BOM 判序 (无 BOM 默认 BE), 编码输出 BE BOM + BE 字节, 均对齐 JVM 行为。 */
private object Utf16BomCodec : TextCharsetCodec {
    override val name: String get() = "UTF-16"

    override fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        if (length >= 2) {
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) {
                return Utf16Codec(false, "UTF-16BE").decode(bytes, offset + 2, length - 2)
            }
            if (b0 == 0xFF && b1 == 0xFE) {
                return Utf16Codec(true, "UTF-16LE").decode(bytes, offset + 2, length - 2)
            }
        }
        return Utf16Codec(false, "UTF-16BE").decode(bytes, offset, length)
    }

    override fun encode(str: String): ByteArray {
        val body = Utf16Codec(false, "UTF-16BE").encode(str)
        val out = ByteArray(body.size + 2)
        out[0] = 0xFE.toByte()
        out[1] = 0xFF.toByte()
        body.copyInto(out, 2)
        return out
    }
}

/**
 * UTF-32 定字节序编解码 (4 字节/码点)。对齐 JVM 宽松解码: 无效码点 (>U+10FFFF/代理区) 与
 * 尾部残缺分组替换为 U+FFFD; 编码方向孤立代理项输出 U+FFFD 的 4 字节 (JVM UTF-32 replacement 同值)。
 */
private class Utf32Codec(
    private val littleEndian: Boolean,
    override val name: String,
) : TextCharsetCodec {

    override fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length / 4 + 1)
        var i = offset
        val end = offset + length
        while (i + 3 < end) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val b2 = bytes[i + 2].toInt() and 0xFF
            val b3 = bytes[i + 3].toInt() and 0xFF
            val cp = if (littleEndian) {
                (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
            when {
                cp in 0 until 0xD800 || cp in 0xE000..0xFFFF -> sb.append(cp.toChar())
                cp in 0x10000..0x10FFFF -> {
                    val v = cp - 0x10000
                    sb.append(((v shr 10) + 0xD800).toChar())
                    sb.append(((v and 0x3FF) + 0xDC00).toChar())
                }
                else -> sb.append('�') // 无效码点 (代理区/超范围) 替换, 对齐 JVM REPLACE
            }
            i += 4
        }
        if (i < end) sb.append('�') // 尾部残缺分组
        return sb.toString()
    }

    override fun encode(str: String): ByteArray {
        val out = ByteArray(codePointCount(str) * 4)
        var o = 0
        var i = 0
        while (i < str.length) {
            val c = str[i]
            val cp: Int
            if (c.isHighSurrogate() && i + 1 < str.length && str[i + 1].isLowSurrogate()) {
                cp = 0x10000 + ((c.code - 0xD800) shl 10) + (str[i + 1].code - 0xDC00)
                i += 2
            } else {
                // 孤立代理项无法用 UTF-32 表示, 替换为 U+FFFD (JVM UTF-32 encoder replacement 同值)
                cp = if (c.isSurrogate()) 0xFFFD else c.code
                i += 1
            }
            if (littleEndian) {
                out[o] = (cp and 0xFF).toByte()
                out[o + 1] = ((cp shr 8) and 0xFF).toByte()
                out[o + 2] = ((cp shr 16) and 0xFF).toByte()
                out[o + 3] = ((cp ushr 24) and 0xFF).toByte()
            } else {
                out[o] = ((cp ushr 24) and 0xFF).toByte()
                out[o + 1] = ((cp shr 16) and 0xFF).toByte()
                out[o + 2] = ((cp shr 8) and 0xFF).toByte()
                out[o + 3] = (cp and 0xFF).toByte()
            }
            o += 4
        }
        return out
    }

    /** 码点数 (代理对算 1): 编码输出恒为 codePointCount*4 字节 (孤立代理替换为 U+FFFD 仍占 1 码点)。 */
    private fun codePointCount(str: String): Int {
        var n = 0
        var i = 0
        while (i < str.length) {
            val c = str[i]
            i += if (c.isHighSurrogate() && i + 1 < str.length && str[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }
}

/** 无标记 UTF-32: 解码按 BOM 判序并剥离 (无 BOM 默认 BE), 编码输出 BE 无 BOM, 均对齐 JVM 行为。 */
private object Utf32BomCodec : TextCharsetCodec {
    override val name: String get() = "UTF-32"

    override fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        if (length >= 4) {
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            val b2 = bytes[offset + 2].toInt() and 0xFF
            val b3 = bytes[offset + 3].toInt() and 0xFF
            if (b0 == 0x00 && b1 == 0x00 && b2 == 0xFE && b3 == 0xFF) {
                return Utf32Codec(false, "UTF-32BE").decode(bytes, offset + 4, length - 4)
            }
            if (b0 == 0xFF && b1 == 0xFE && b2 == 0x00 && b3 == 0x00) {
                return Utf32Codec(true, "UTF-32LE").decode(bytes, offset + 4, length - 4)
            }
        }
        return Utf32Codec(false, "UTF-32BE").decode(bytes, offset, length)
    }

    override fun encode(str: String): ByteArray = Utf32Codec(false, "UTF-32BE").encode(str)
}

private object Latin1Codec : TextCharsetCodec {
    override val name: String get() = "ISO-8859-1"

    override fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length)
        for (i in offset until offset + length) {
            sb.append((bytes[i].toInt() and 0xFF).toChar())
        }
        return sb.toString()
    }

    override fun encode(str: String): ByteArray {
        // 超出 Latin-1 的字符替换为 '?' (对齐 JVM REPLACE 模式)
        return ByteArray(str.length) { i ->
            val code = str[i].code
            if (code <= 0xFF) code.toByte() else '?'.code.toByte()
        }
    }
}

private object AsciiCodec : TextCharsetCodec {
    override val name: String get() = "US-ASCII"

    override fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length)
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            sb.append(if (b < 0x80) b.toChar() else '�')
        }
        return sb.toString()
    }

    override fun encode(str: String): ByteArray {
        return ByteArray(str.length) { i ->
            val code = str[i].code
            if (code < 0x80) code.toByte() else '?'.code.toByte()
        }
    }
}
