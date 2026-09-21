package io.legado.app.utils

/**
 * JVM 专属类型的 jvmAndAndroidMain actual 实现。
 *
 * 详见 commonMain/utils/JvmPlatformTypes.kt expect 注释。
 * 各 actual typealias 到 JDK 类型, 与原 jvmAndAndroidMain 直接实现行为一致。
 *
 * [toInputStream]: 委托 kotlin.io JVM 扩展, 返回 java.io.InputStream
 * (与 expect InputStream actual typealias 等价)。
 */
actual typealias URL = java.net.URL

actual fun URL.urlQuery(): String? = query

actual typealias InputStream = java.io.InputStream

actual typealias File = java.io.File

actual typealias Closeable = java.io.Closeable

actual fun ByteArray.toInputStream(): InputStream = inputStream()

actual fun Throwable.isSecurityException(): Boolean = this is SecurityException

actual fun String.platformIntern(): String = intern()
