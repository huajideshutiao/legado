package io.legado.app.help

import io.legado.app.help.http.SSLHelper
import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.ChineseUtils
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone

/**
 * JsExtensionsCommon 平台相关 actual (jvmAndAndroid)。
 *
 * 详见 commonMain/help/JsExtensionsPlatform.kt expect 注释。
 * 各 actual 委托原 JDK API, 与原 JsExtensionsCommon 内联实现行为一致。
 */
internal actual object JsExtensionsPlatform {

    actual fun urlEncode(str: String, charset: String): String =
        URLEncoder.encode(str, charset)

    actual fun strToBytes(str: String, charset: String): ByteArray =
        str.toByteArray(Charset.forName(charset))

    actual fun bytesToStr(bytes: ByteArray, charset: String): String =
        String(bytes, Charset.forName(charset))

    actual fun formatTimeUtc(time: Long, format: String, shiftHours: Int): String {
        val utc = SimpleTimeZone(shiftHours, "UTC")
        return SimpleDateFormat(format, Locale.getDefault()).run {
            timeZone = utc
            format(Date(time))
        }
    }

    actual fun base64DecodeStr(str: String?, charset: String): String? {
        str ?: return null
        return Base64Lenient.decodeStr(str, Charset.forName(charset))
    }

    actual fun chineseT2S(text: String): String = ChineseUtils.t2s(text)

    actual fun chineseS2T(text: String): String = ChineseUtils.s2t(text)

    actual fun sha256Hex(bytes: ByteArray): String {
        // 复刻原 queryTTF 中 MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        // (kotlin.ByteArray.toHexString 默认 lowercase, 与原实现一致)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    actual fun isMainThread(): Boolean {
        // jvmAndAndroidMain 共享 Android 与桌面 JVM:
        // - Android: 反射调用 Looper.getMainLooper().thread === Thread.currentThread()
        //   (行为对齐原 app 端 io.legado.app.utils.isMainThread: mainLooper.thread === currentThread)
        // - 桌面 JVM: 无 android.os.Looper, 反射失败兜底返回 false (无主线程概念)
        // 注: 直接 import android.os.Looper 会破坏 JVM 桌面端编译, 故走反射
        return try {
            val looperClass = Class.forName("android.os.Looper")
            val mainLooper = looperClass.getMethod("getMainLooper").invoke(null)
            val mainThread = mainLooper?.javaClass?.getMethod("getThread")?.invoke(mainLooper) as? Thread
            mainThread === Thread.currentThread()
        } catch (_: Throwable) {
            false
        }
    }

    actual fun unsafeSslContext(): Any? = SSLHelper.unsafeSslContext
}
