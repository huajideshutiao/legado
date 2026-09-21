package io.legado.app.help.http

import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.http.promisesBody
import okio.BufferedSource
import okio.buffer
import okio.source
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * DecompressInterceptor actual (jvmAndAndroid)。
 *
 * 详见 commonMain/help/http/DecompressPlatform.kt expect 注释。
 * promisesBody 委托 okhttp3.internal.http.promisesBody (行为不变);
 * decompressBody 委托 java.util.zip GZIPInputStream / InflaterInputStream (行为不变)。
 *
 * KP4 OkHttp 跨平台修复: expect 接收者/参数改为 [Any] (commonMain 不引用 okhttp3.*),
 * actual 内部 cast 回 okhttp3.* / okio.* 类型, 行为与原实现完全一致 (零 diff)。
 * - [promisesBody]: this as Response
 * - [decompressBody]: body as ResponseBody, 返回 okio.BufferedSource (作为 Any? 返回)
 */
internal actual fun Any.promisesBody(): Boolean = (this as Response).promisesBody()

internal actual fun decompressBody(body: Any, encoding: String?): Any? {
    val responseBody = body as ResponseBody
    return when (encoding) {
        "gzip" -> GZIPInputStream(responseBody.byteStream()).source().buffer()
        "deflate" -> InflaterInputStream(responseBody.byteStream(), Inflater(true)).source().buffer()
        else -> null
    }
}
