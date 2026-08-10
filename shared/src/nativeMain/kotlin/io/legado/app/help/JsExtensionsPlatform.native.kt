package io.legado.app.help

import io.legado.app.help.crypto.NativeDigestOps
import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.PercentCodec
import io.legado.app.utils.TextCharsetCodec
import io.legado.app.utils.encodeURI
import io.legado.app.utils.textCharsetCodec

/** 平台主线程判定 (iOS: NSThread / 鸿蒙: pthread_self 捕获比对, 见 iosMain/ohosMain actual)。 */
internal expect fun isMainThreadPlatform(): Boolean

/**
 * JsExtensionsCommon 平台相关 actual (iOS / 鸿蒙)。
 *
 * 详见 commonMain/help/JsExtensionsPlatform.kt expect 注释。
 * 纯 Kotlin 实现, 行为对齐 jvmAndAndroidMain:
 *
 * - [urlEncode]: PercentCodec.QUERY + 按 charset 字节化 (对齐 URLEncoder.encode(str, enc))
 *   (差异: encodeURI 空格保持 ' ' 而 URLEncoder 转为 '+', 业务可接受)
 * - [strToBytes] / [bytesToStr] / [base64DecodeStr]: 走 [textCharsetCodec]
 *   (UTF-8/UTF-16 系/UTF-32 系/ISO-8859-1/US-ASCII 内置, GBK 系/Big5 走平台 CJK 桥),
 *   对齐原版 `str.toByteArray(charset(name))` / `String(bytes, charset(name))`;
 *   平台无该 charset 的 codec 时退化 UTF-8 (功能受限, 不崩)
 * - [formatTimeUtc]: 纯 Kotlin 日期格式化 (epoch 毫秒 → UTC 时区字符串), 支持常见 SimpleDateFormat 模式字母
 *   (yyyy/MM/dd/HH/mm/ss/SSS 等), 时区偏移由 shiftHours 控制, 与 jvmAndAndroidMain SimpleDateFormat + SimpleTimeZone 行为一致
 * - [chineseT2S/chineseS2T]: 委托 commonMain 的 ChineseUtils (expect object, 由繁简子代理提供 actual)
 */
internal actual object JsExtensionsPlatform {

    actual fun urlEncode(str: String, charset: String): String {
        // PercentCodec.QUERY + 按 charset 字节化 (原版 URLEncoder.encode(str, enc) 同为按 charset 取字节)
        // 已知差异: URLEncoder 空格 -> "+", 这里 -> "%20" (encodeURI 既有行为, 业务可接受)
        val codec = textCharsetCodecOrNull(charset) ?: return str.encodeURI()
        return runCatching { PercentCodec.QUERY.encode(str) { codec.encode(it) } }
            .getOrElse { str.encodeURI() }
    }

    actual fun strToBytes(str: String, charset: String): ByteArray {
        // 对齐原版 str.toByteArray(charset(charset))
        val codec = textCharsetCodecOrNull(charset) ?: return str.encodeToByteArray()
        return runCatching { codec.encode(str) }.getOrElse { str.encodeToByteArray() }
    }

    actual fun bytesToStr(bytes: ByteArray, charset: String): String {
        // 对齐原版 String(bytes, charset(charset))
        val codec = textCharsetCodecOrNull(charset) ?: return bytes.decodeToString()
        return runCatching { codec.decode(bytes) }.getOrElse { bytes.decodeToString() }
    }

    actual fun formatTimeUtc(time: Long, format: String, shiftHours: Int): String {
        val shiftedMillis = time + shiftHours * 3_600_000L
        val (y, mo, d) = io.legado.app.utils.yearMonthDayFromMillis(shiftedMillis)
        // 计算时分秒 (UTC 当天)
        val dayMillis = shiftedMillis.mod(86_400_000L).toInt()
        val totalSeconds = dayMillis / 1000
        val hour = totalSeconds / 3600
        val minute = (totalSeconds % 3600) / 60
        val second = totalSeconds % 60
        val millis = dayMillis % 1000
        return formatSimpleDate(format, y, mo, d, hour, minute, second, millis)
    }

    actual fun base64DecodeStr(str: String?, charset: String): String? {
        // 对齐原版 Base64.decodeStr(str, charset(charset)): 先解 base64 再按 charset 解码
        str ?: return null
        val codec = textCharsetCodecOrNull(charset) ?: return Base64Lenient.decodeStr(str)
        return runCatching { codec.decode(Base64Lenient.decode(str)) }
            .getOrElse { Base64Lenient.decodeStr(str) }
    }

    actual fun chineseT2S(text: String): String = ChineseUtils.t2s(text)

    actual fun chineseS2T(text: String): String = ChineseUtils.s2t(text)

    actual fun sha256Hex(bytes: ByteArray): String {
        // NativeDigestOps.digest 返回原始摘要字节, 转 lowercase hex
        // (与 jvmAndAndroid MessageDigest.getInstance("SHA-256") 输出一致)
        // 纯 Kotlin 等价 "%02x".format(byte) (Native 无 String.format): 必须先 and 0xFF —
        // JVM Formatter 对负 Byte 按无符号处理 (-1 → "ff"), 直接 toString(16) 会得 "-1"
        return NativeDigestOps.digest("SHA-256", bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    actual fun isMainThread(): Boolean = isMainThreadPlatform()

    actual fun unsafeSslContext(): Any? = null // native 无 unsafe SSL 模式, 系统信任库 (见 expect 注释)
}

/** 按名取 codec; 平台不支持该 charset 时返回 null 让调用方退化 UTF-8 (原版是抛异常, 此处不崩)。 */
private fun textCharsetCodecOrNull(charset: String): TextCharsetCodec? =
    if (charset.isBlank()) null else runCatching { textCharsetCodec(charset) }.getOrNull()

/**
 * 平台主线程判定 (nativeMain 无法统一实现):
 * - iOS: `NSThread.isMainThread` (platform.Foundation)
 * - 鸿蒙: 启动期 (主线程) 捕获 pthread_self 后比对 (ohosMain, 见 OhosMainThreadDetector)
 *
 * 注: `kotlin.native.Platform` 无 isMainThread 属性 (已核实 Kotlin 2.3.20 stdlib 元数据),
 * 只能走平台 API。
 */

/**
 * 纯 Kotlin SimpleDateFormat 子集实现 (UTC 时区)。
 *
 * 支持模式字母: y (年), M (月), d (日), H (时, 0-23), m (分), s (秒), S (毫秒)。
 * 其他字母 (E/a/Z 等) 原样输出 (功能受限, 不崩)。
 *
 * 行为对齐 java.text.SimpleDateFormat 的常见 pattern:
 * - yyyy → 4 位年, yy → 2 位年
 * - MM → 2 位月, M → 不补 0
 * - dd → 2 位日, d → 不补 0
 * - HH/mm/ss → 2 位, H/m/s → 不补 0
 * - SSS → 3 位毫秒, S → 不补 0
 */
private fun formatSimpleDate(
    pattern: String,
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
    millis: Int,
): String {
    val sb = StringBuilder(pattern.length + 8)
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when (c) {
            'y' -> {
                val n = countRepeat(pattern, i, 'y')
                if (n >= 4) sb.append(year.toString().padStart(4, '0'))
                else sb.append((year % 100).toString().padStart(2, '0'))
                i += n
            }
            'M' -> {
                val n = countRepeat(pattern, i, 'M')
                sb.append(if (n >= 2) month.toString().padStart(2, '0') else month.toString())
                i += n
            }
            'd' -> {
                val n = countRepeat(pattern, i, 'd')
                sb.append(if (n >= 2) day.toString().padStart(2, '0') else day.toString())
                i += n
            }
            'H' -> {
                val n = countRepeat(pattern, i, 'H')
                sb.append(if (n >= 2) hour.toString().padStart(2, '0') else hour.toString())
                i += n
            }
            'm' -> {
                val n = countRepeat(pattern, i, 'm')
                sb.append(if (n >= 2) minute.toString().padStart(2, '0') else minute.toString())
                i += n
            }
            's' -> {
                val n = countRepeat(pattern, i, 's')
                sb.append(if (n >= 2) second.toString().padStart(2, '0') else second.toString())
                i += n
            }
            'S' -> {
                val n = countRepeat(pattern, i, 'S')
                if (n >= 3) sb.append(millis.toString().padStart(3, '0'))
                else sb.append(millis.toString())
                i += n
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

private fun countRepeat(s: String, start: Int, c: Char): Int {
    var n = 0
    var i = start
    while (i < s.length && s[i] == c) {
        n++
        i++
    }
    return n
}
