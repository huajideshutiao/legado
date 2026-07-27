package io.legado.app.help

/**
 * JsExtensionsCommon 平台相关 expect 门面。
 *
 * 原 JsExtensionsCommon (jvmAndAndroidMain) 直接依赖 JDK API:
 * - [java.net.URLEncoder]
 * - [kotlin.text.charset] / [String.toByteArray] / [String] (java.nio.charset.Charset)
 * - [java.text.SimpleDateFormat] + [java.util.SimpleTimeZone] + [java.util.Date] + [java.util.Locale]
 * - [io.legado.app.utils.Base64Lenient.decodeStr] (String?, java.nio.charset.Charset) 重载
 *   (jvmAndAndroid 附加成员, common 无 Charset)
 * - [io.legado.app.utils.ChineseUtils] (jvmAndAndroidMain, 依赖 jvm 字典)
 * - [java.security.MessageDigest] (queryTTF 缓存 key 计算 SHA-256 hex)
 *
 * JsExtensionsCommon 接口下沉 commonMain 后, 所有 JDK 调用走本 expect object 门面;
 * jvmAndAndroidMain actual 委托原 JDK 实现, 行为不变。
 *
 * 仅 JsExtensionsCommon 使用, 标记 internal 避免污染公开 API。
 */
internal expect object JsExtensionsPlatform {

    fun urlEncode(str: String, charset: String): String

    fun strToBytes(str: String, charset: String): ByteArray

    fun bytesToStr(bytes: ByteArray, charset: String): String

    /** UTC 时区时间格式化: shiftHours 为相对 UTC 的偏移小时数。 */
    fun formatTimeUtc(time: Long, format: String, shiftHours: Int): String

    /** 包装 Base64Lenient.decodeStr(source, Charset) jvmAndAndroid 重载, common 无 Charset。 */
    fun base64DecodeStr(str: String?, charset: String): String?

    fun chineseT2S(text: String): String

    fun chineseS2T(text: String): String

    /**
     * 计算 SHA-256 摘要并返回小写 hex 字符串。
     *
     * 替代 queryTTF 中 `MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()`
     * (java.security.MessageDigest 是 JVM 专属)。各端 actual 用本平台等价实现:
     * - jvmAndAndroid: java.security.MessageDigest
     * - iOS/鸿蒙: com.soywiz.krypto.SHA256
     *
     * 输出小写 hex (与 [kotlin.ByteArray.toHexString] 默认 lowercase 一致)。
     */
    fun sha256Hex(bytes: ByteArray): String

    /**
     * 判断当前是否在主线程。
     *
     * 下沉的 webView 系列方法需要拒绝在主线程调用 (会阻塞 UI)。
     * 各端 actual:
     * - jvmAndAndroid: Android 端反射调用 `Looper.getMainLooper().thread === Thread.currentThread()`
     *   (jvmAndAndroidMain 共享 JVM 桌面端, 桌面 JVM 无 android.os.Looper, 反射失败时返回 false);
     *   行为与原 app 端 `io.legado.app.utils.isMainThread` 一致
     * - iOS/鸿蒙: 返回 false (无主线程概念, webView 调用本就在后台线程)
     */
    fun isMainThread(): Boolean
}
