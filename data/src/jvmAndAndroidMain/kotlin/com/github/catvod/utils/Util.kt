package com.github.catvod.utils

import okhttp3.OkHttp
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * TVBox 壳杂项工具: 公开 API 面逐字对齐 FongMi catvod 模块的 utils.Util
 * (jar 内常以 Util.CHROME / Util.URL_SAFE / Util.base64 / Util.containOrMatch 直调)。
 *
 * 与 FongMi 的差异 (均不改变公开 API 签名):
 * - Base64 以 java.util.Base64 按 android flag 语义等价实现 (URL_SAFE/NO_WRAP/DEFAULT);
 * - getIp() 的 WifiManager 分支已删 —— 网卡枚举覆盖同一张 wlan 网卡, 观测结果等价,
 *   且宿主壳类不得依赖 android.net.wifi (桌面 JVM 无该框架类)。
 */
object Util {

    @JvmField
    val OKHTTP: String = "okhttp/" + OkHttp.VERSION

    const val CHROME =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"

    @JvmField
    val URL_SAFE: Int = 0 or 8 or 2

    /** android.util.Base64.DEFAULT 的 URL 字母表/无换行等价编码 (NO_WRAP 语义)。 */
    @JvmStatic
    fun base64(s: String): String = base64(s.toByteArray(), 0 or 2)

    @JvmStatic
    fun base64(bytes: ByteArray): String = base64(bytes, 0 or 2)

    @JvmStatic
    fun base64(s: String, flags: Int): String = base64(s.toByteArray(), flags)

    /** flags 语义对齐 android.util.Base64 (URL_SAFE=8 换 URL 字母表, NO_PADDING=1 不补位)。 */
    @JvmStatic
    fun base64(bytes: ByteArray, flags: Int): String {
        var encoder = if (flags and 8 != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and 1 != 0) encoder = encoder.withoutPadding()
        return encoder.encodeToString(bytes)
    }

    @JvmStatic
    fun decode(s: String): ByteArray = decode(s, 0 or 2)

    /** 解码宽容 (容忍空白/缺补位, 对齐 android Base64 解码器); URL_SAFE 输入先归一字母表。 */
    @JvmStatic
    fun decode(s: String, flags: Int): ByteArray {
        val normalized = if (flags and 8 != 0) s.replace('-', '+').replace('_', '/') else s
        return java.util.Base64.getMimeDecoder().decode(normalized)
    }

    @JvmStatic
    fun hex2byte(s: String): ByteArray {
        val bytes = ByteArray(s.length / 2)
        for (i in bytes.indices) bytes[i] = Integer.valueOf(s.substring(i * 2, i * 2 + 2), 16).toByte()
        return bytes
    }

    @JvmStatic
    fun containOrMatch(text: String?, regex: String?): Boolean = try {
        text!!.contains(regex!!) || text.matches(regex.toRegex())
    } catch (e: Exception) {
        false
    }

    @JvmStatic
    fun substring(text: String?): String? = substring(text, 1)

    @JvmStatic
    fun substring(text: String?, num: Int): String? {
        if (text != null && text.length > num) return text.substring(0, text.length - num)
        return text
    }

    @JvmStatic
    fun getIp(): String = try {
        var ip = getHostAddress("wlan")
        if (ip.isEmpty()) ip = getHostAddress("eth")
        if (ip.isEmpty()) ip = getHostAddress("")
        ip
    } catch (e: Exception) {
        ""
    }

    private fun getHostAddress(keyword: String): String = try {
        val en = NetworkInterface.getNetworkInterfaces()
        while (en.hasMoreElements()) {
            val nif = en.nextElement()
            if (keyword.isNotEmpty() && !nif.name.startsWith(keyword)) continue
            for (addresses in nif.inetAddresses) {
                val addr = addresses
                if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                return addr.hostAddress
            }
        }
        ""
    } catch (ignored: Exception) {
        ""
    }
}
