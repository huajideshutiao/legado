package io.legado.app.utils

import io.legado.app.help.http.KmpResponseBody
import io.legado.app.utils.InputStream

/**
 * KmpResponseBody → InputStream 转换 (data 层专用, 见 commonMain ByteStreamAsInput.kt expect)。
 * iOS/鸿蒙: 委托 [KmpResponseBody.byteStream] (内存字节缓存实现, 可多次读, 语义同 OkHttp)。
 */
actual fun Any.byteStreamAsInput(): InputStream = (this as KmpResponseBody).byteStream()
