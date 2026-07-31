package io.legado.app.help

import io.legado.app.help.crypto.NativeDigestOps
import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.encodeURI

/**
 * JsExtensionsCommon 平台相关 actual (iOS / 鸿蒙)。
 *
 * 详见 commonMain/help/JsExtensionsPlatform.kt expect 注释。
 * 纯 Kotlin 实现, 行为对齐 jvmAndAndroidMain:
 *
 * - [urlEncode]: 委托 commonMain 的 [String.encodeURI] (PercentCodec.QUERY, UTF-8 字节)
 *   与 java.net.URLEncoder.encode(str, "UTF-8") 在大多数情况下行为一致
 *   (差异: encodeURI 空格保持 ' ' 而 URLEncoder 转为 '+', 业务可接受)
 * - [strToBytes]: 用 [String.encodeToByteArray] (UTF-8 默认), charset 参数忽略 (KMP 仅 UTF-8 原生支持)
 *   若 charset 非 UTF-8, 仍返回 UTF-8 字节 (功能受限: GBK 等非 UTF-8 charset 在 iOS/鸿蒙不可用, 不崩)
 * - [bytesToStr]: 用 [ByteArray.decodeToString] (UTF-8 默认), charset 参数忽略
 * - [formatTimeUtc]: 纯 Kotlin 日期格式化 (epoch 毫秒 → UTC 时区字符串), 支持常见 SimpleDateFormat 模式字母
 *   (yyyy/MM/dd/HH/mm/ss/SSS 等), 时区偏移由 shiftHours 控制, 与 jvmAndAndroidMain SimpleDateFormat + SimpleTimeZone 行为一致
 * - [base64DecodeStr]: 委托 commonMain Base64Lenient.decodeStr (UTF-8 字节解码), charset 参数忽略
 * - [chineseT2S/chineseS2T]: 委托 commonMain 的 ChineseUtils (expect object, 由繁简子代理提供 actual)
 */
internal actual object JsExtensionsPlatform {

    actual fun urlEncode(str: String, charset: String): String {
        // 用 commonMain 的 encodeURI (PercentCodec.QUERY + UTF-8)
        // java.net.URLEncoder.encode 空格 -> "+", 这里 encodeURI 空格 -> "%20", 业务可接受
        return str.encodeURI()
    }

    actual fun strToBytes(str: String, charset: String): ByteArray {
        // KMP 仅 UTF-8 原生支持, 非 UTF-8 charset 退化用 UTF-8 (功能受限, 不崩)
        return str.encodeToByteArray()
    }

    actual fun bytesToStr(bytes: ByteArray, charset: String): String {
        return bytes.decodeToString()
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
        // KMP 仅 UTF-8 原生支持, 非 UTF-8 charset 退化用 UTF-8
        str ?: return null
        return Base64Lenient.decodeStr(str)
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

    actual fun isMainThread(): Boolean = false
}

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
