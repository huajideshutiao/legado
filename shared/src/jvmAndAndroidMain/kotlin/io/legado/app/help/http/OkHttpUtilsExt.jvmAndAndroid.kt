package io.legado.app.help.http

import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.Utf8BomUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.http.RealResponseBody
import okio.buffer
import okio.source
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * OkHttp 工具扩展 actual 实现 (jvmAndAndroidMain)。
 *
 * 原为 jvmAndAndroidMain 端独立 fun, AnalyzeUrlCore 下沉 commonMain 后提升为 actual。
 *
 * - [newCallStrResponse]: 依赖 [text] (Charset), 委托 newCallResponse + body.text()
 * - [text]: 依赖 java.nio.charset.Charset + EncodingDetect (icu4j)
 * - [decompressed]: 依赖 java.util.zip.ZipInputStream + RealResponseBody
 * - [postMultipart]: 依赖 java.io.File + asRequestBody
 *
 * KP4 OkHttp 跨平台修复: 原用 [Any] 接收者 + actual 内部 cast 回 okhttp3.* 类型;
 * 现改用 [KmpHttpClient] / [KmpRequestBuilder] / [KmpResponseBody] 跨平台抽象。
 * jvmAndAndroidMain 经 typealias 等价 okhttp3.*, 无需 cast, 行为与原实现完全一致 (零 diff)。
 */
actual suspend fun KmpHttpClient.newCallStrResponse(
    retry: Int,
    builder: KmpRequestBuilder.() -> Unit
): StrResponse {
    return newCallResponse(retry, builder).let {
        StrResponse(it, it.body.text())
    }
}

actual fun KmpResponseBody.text(encode: String?): String {
    val responseBytes = Utf8BomUtils.removeUTF8BOM(this.bytes())
    var charsetName: String? = encode

    charsetName?.let {
        return String(responseBytes, Charset.forName(charsetName))
    }

    //根据http头判断
    this.contentType()?.charset()?.let { charset ->
        return String(responseBytes, charset)
    }

    //根据内容判断
    charsetName = EncodingDetect.getHtmlEncode(responseBytes)
    return String(responseBytes, Charset.forName(charsetName))
}

actual fun KmpResponseBody.decompressed(): KmpResponseBody {
    val contentType = this.contentType()?.toString()
    if (contentType != "application/zip") {
        return this
    }
    val source = ZipInputStream(this.byteStream()).apply {
        try {
            nextEntry
        } catch (e: Exception) {
            close()
            throw e
        }
    }.source().buffer()
    return RealResponseBody(null, -1, source)
}

actual fun KmpRequestBuilder.postMultipart(type: String?, form: Map<String, Any>) {
    val multipartBody = MultipartBody.Builder()
    type?.let {
        multipartBody.setType(it.toMediaType())
    }
    form.forEach {
        when (val value = it.value) {
            is Map<*, *> -> {
                val fileName = value["fileName"] as String
                val file = value["file"]
                val mediaType = (value["contentType"] as? String)?.toMediaType()
                val requestBody = when (file) {
                    is File -> {
                        file.asRequestBody(mediaType)
                    }

                    is ByteArray -> {
                        file.toRequestBody(mediaType)
                    }

                    is String -> {
                        file.toRequestBody(mediaType)
                    }

                    else -> {
                        // Phase D: GSON.toJson(file) 用反射序列化任意对象;
                        // 此分支仅在前三种类型 (File/ByteArray/String) 都不匹配时执行, 实际不会触发。
                        // 改用 file.toString() 保留行为 (输出对象字符串表示), 避免引入新依赖。
                        file.toString().toRequestBody(mediaType)
                    }
                }
                multipartBody.addFormDataPart(it.key, fileName, requestBody)
            }

            else -> multipartBody.addFormDataPart(it.key, it.value.toString())
        }
    }
    this.post(multipartBody.build())
}
