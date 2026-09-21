package io.legado.app.utils

import io.legado.app.utils.InputStream

/**
 * KmpResponseBody → InputStream 转换 (data 层专用, 见 AnalyzeUrlCore.getInputStreamAwait)。
 *
 * 原实现挂在 foundation 的 JvmPlatformTypes expect/actual 上, 但 KmpResponseBody 属 data 层,
 * 切分后 expect/actual 同模块约束下无法跨模块; 改为本模块自身的 expect/actual:
 * - jvmAndAndroidMain: 委托 okhttp3.ResponseBody.byteStream() (okhttp3 在该层可用)
 * - nativeMain: 委托 [io.legado.app.help.http.KmpResponseBody.byteStream]
 *   (iOS/鸿蒙 actual 均在内存字节缓存上实现, 可多次读, 语义同 OkHttp)
 */
expect fun Any.byteStreamAsInput(): InputStream
