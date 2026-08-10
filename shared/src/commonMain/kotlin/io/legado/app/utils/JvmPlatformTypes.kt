package io.legado.app.utils

/**
 * JVM 专属类型的 commonMain expect 门面。
 *
 * 用于 AnalyzeUrlCore/AnalyzeRuleCore 下沉 commonMain:
 * - [URL] 对应 java.net.URL, AnalyzeRuleCore.setRedirectUrl 返回类型 + redirectUrl 字段类型
 *   (方法签名零 diff: KSP 分派表通过继承链扫描, 返回类型必须保留 URL?)
 * - [InputStream] 对应 java.io.InputStream, AnalyzeUrlCore.getInputStream/getInputStreamAwait 返回类型
 * - [Closeable] 对应 java.io.Closeable, AnalyzeRuleCore 父类实现 Closeable
 *   (声明 close() 以便 AnalyzeRuleCore override; java.io.Closeable 同签名, actual typealias 兼容)
 *
 * jvmAndAndroidMain actual typealias 到 JDK 类型, 行为不变;
 * commonMain 中 URL/InputStream 仅作类型签名占位, 不暴露方法 (构造函数最小集)。
 *
 * [toInputStream] / [byteStreamAsInput]: 包装 ByteArray.inputStream() / ResponseBody.byteStream()
 * 这两个 JVM-only 扩展, 使 AnalyzeUrlCore.getInputStreamAwait 可在 commonMain 调用。
 *
 * KP4 OkHttp 跨平台修复: [byteStreamAsInput] 原签名引用 okhttp3.ResponseBody,
 * 但 OkHttp 5.3.2 不发布 iosArm64/linuxArm64 变体, iOS/鸿蒙 target 编译会失败。
 * 现改用 [Any] 类型擦除: jvmAndAndroidMain actual 内部 cast 回 okhttp3.ResponseBody;
 * iOS/鸿蒙 actual 恒抛 UnsupportedOperationException (KmpResponseBody 的 byteStream()
 * 尚未在此委托) — AnalyzeUrlCore 经 byteStreamAsInput 的流式路径在 iOS/鸿蒙暂不可用。
 */
expect class URL(url: String) {
    /** URL 外部形式 (jvm: java.net.URL.toExternalForm(); native: 原字符串)。 */
    fun toExternalForm(): String

    override fun toString(): String
}

/**
 * URL 的 query 部分 (不含 '?')。
 *
 * 不能声明为 expect class 成员: jvm actual 是 typealias 到 java.net.URL,
 * 而 Kotlin 不把 Java 合成属性 (getQuery()) 计入 expect/actual 成员匹配。
 */
internal expect fun URL.urlQuery(): String?

// java.io.InputStream 是 abstract class, expect class 默认 final 会与 actual typealias 冲突,
// 需用 abstract 修饰 (build.gradle 已配置 -Xexpect-actual-classes).
// TextFileCore 下沉 commonMain 后补声明 read/skip/available/close 成员 (签名对齐 java.io.InputStream,
// jvm 端 typealias 自动匹配; native actual 补默认实现, ByteArrayInputStream 按 JVM 语义 override)。
expect abstract class InputStream {
    open fun read(b: ByteArray): Int
    open fun read(b: ByteArray, off: Int, len: Int): Int
    open fun skip(n: Long): Long
    open fun available(): Int
    open fun close()
}

// java.io.File 桥接: commonMain 仅作类型签名占位 (如 BitmapProvider.decodeStreamAndCompressToJpeg
// 的 outFile 参数), 不在 commonMain 构造或调用 File 方法。显式声明 (path: String) 构造器以匹配
// java.io.File(String) (java.io.File 无无参构造器, 隐式 no-arg expect 会与 typealias 冲突)。
// jvmAndAndroidMain actual typealias 到 java.io.File (带出全部构造器); iOS/鸿蒙 actual 仅持有
// path 字段 (Ios/OhosBitmapProvider 经 path 转 kotlin.io.File 做真实文件写入, 此类型不需文件操作)。
expect class File(path: String)

expect interface Closeable {
    fun close()
}

/**
 * ByteArray → InputStream 转换 (JVM-only 扩展包装)。
 * actual: `ByteArray.inputStream()` (kotlin.io 标准库 JVM 扩展, commonMain 不可见)。
 */
internal expect fun ByteArray.toInputStream(): InputStream

/**
 * ResponseBody → InputStream 转换 (JVM-only API 包装)。
 * actual: `ResponseBody.byteStream()` (okhttp3 commonJvmAndroid, 返回 java.io.InputStream)。
 *
 * KP4: 接收者改为 [Any] 以避免 commonMain 引用 okhttp3.ResponseBody;
 * jvmAndAndroidMain actual 内部 cast 回 okhttp3.ResponseBody。
 */
internal expect fun Any.byteStreamAsInput(): InputStream

/** java.lang.SecurityException 判定 (native 无此类型, actual 恒 false)。 */
expect fun Throwable.isSecurityException(): Boolean

/** String.intern() 门面 (JVM 驻留常量池省内存; native actual 原样返回)。 */
expect fun String.platformIntern(): String
