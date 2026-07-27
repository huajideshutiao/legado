package io.legado.app.utils

import okhttp3.ResponseBody

/**
 * JVM 专属类型的 jvmAndAndroidMain actual 实现。
 *
 * 详见 commonMain/utils/JvmPlatformTypes.kt expect 注释。
 * 各 actual typealias 到 JDK 类型, 与原 jvmAndAndroidMain 直接实现行为一致。
 *
 * [toInputStream] / [byteStreamAsInput]: 委托 kotlin.io / okhttp3 JVM 扩展,
 * 返回 java.io.InputStream (与 expect InputStream actual typealias 等价)。
 *
 * KP4 OkHttp 跨平台修复: [byteStreamAsInput] 接收者改为 [Any] (commonMain expect 不引用 okhttp3.*),
 * actual 内部 cast 回 okhttp3.ResponseBody, 行为与原 `ResponseBody.byteStream()` 完全一致。
 */
actual typealias URL = java.net.URL

actual typealias InputStream = java.io.InputStream

actual typealias File = java.io.File

actual typealias Closeable = java.io.Closeable

internal actual fun ByteArray.toInputStream(): InputStream = inputStream()

internal actual fun Any.byteStreamAsInput(): InputStream = (this as ResponseBody).byteStream()

actual fun Throwable.isSecurityException(): Boolean = this is SecurityException

actual fun String.platformIntern(): String = intern()
