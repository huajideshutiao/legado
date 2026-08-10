package io.legado.app.help.http

import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.Utf8BomUtils
import io.legado.app.utils.textCharsetCodec
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * OkHttp 工具扩展 nativeMain Actual 实现 (基于 KmpHttpTypes.native.kt 真实 HTTP 层)。
 *
 * 由 iosMain / ohosMain 共用 (nativeMain 中间源集下沉, 原 iosMain/ohosMain actual 完全一致,
 * 仅 Ios/Ohos 类前缀与 boundary 字符串差异, 统一改为 Native 前缀 + legado-native-boundary-)。
 *
 * 详见 commonMain/kotlin/io/legado/app/help/http/OkHttpUtilsExt.kt expect 注释。
 *
 * ## 实现方式
 * - [newCallStrResponse]: 委托 commonMain 的 [newCallResponse] (suspend) + [text] (charset 检测)
 *   与 jvmAndAndroidMain 行为一致, 仅多一层 Ktor 替代 OkHttp 的间接;
 * - [text]: UTF-8 BOM 移除 + contentType charset + EncodingDetect 三级回退, 与 jvm 行为对齐;
 * - [decompressed]: nativeMain 端无 java.util.zip, 不支持 application/zip 解压, **返回原 body 不解压**
 *   (与 jvmAndAndroidMain 行为不同; 调用方在 iOS/鸿蒙端不依赖此功能, AnalyzeUrlCore 解压链走 stub)
 * - [postMultipart]: 手动构造 multipart/form-data body (boundary + 字段分隔符), 与 OkHttp MultipartBody 字节级对齐
 *
 * ## 与 jvmAndAndroidMain 行为差异
 * - decompressed: jvm 端用 ZipInputStream 解 application/zip 响应, nativeMain 端无 zip 库, 直接返回原 body;
 *   调用方在 iOS/鸿蒙端不依赖此功能 (DecompressPlatform.native.kt 是 stub, decompressBody 返回 null)
 * - postMultipart: jvm 端用 okhttp3.MultipartBody.Builder, nativeMain 端手动拼字节 (功能等价)
 * - charset 解析: jvm 端用 `Charset.forName(name)` 支持任意 JVM charset, nativeMain 端走
 *   commonMain [textCharsetCodec] 门面 (UTF-8/UTF-16 系/UTF-32 系/ISO-8859-1/US-ASCII + GBK/Big5 分端下沉),
 *   其余 charset 降级为 UTF-8
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

    // 1. 显式指定的 encode 优先 (调用方传入); 不支持的字符集降级 UTF-8
    encode?.let {
        return decodeOrNull(responseBytes, it) ?: responseBytes.decodeToString()
    }

    // 2. 根据 HTTP 头 contentType 中的 charset 判断; 不支持时继续按内容判断
    this.contentType()?.charsetName()?.let { name ->
        decodeOrNull(responseBytes, name)?.let { return it }
    }

    // 3. 根据内容判断 (EncodingDetect 已下沉 commonMain, 用 Ksoup 解析 meta charset)
    val detected = EncodingDetect.getHtmlEncode(responseBytes)
    return decodeOrNull(responseBytes, detected) ?: responseBytes.decodeToString()
}

/**
 * nativeMain 端 [decompressed] 实现: 不支持 application/zip 解压 (无 java.util.zip)。
 *
 * 与 jvmAndAndroidMain 行为不同 (jvm 端用 ZipInputStream 解压),
 * nativeMain 端 DecompressPlatform.native.kt 是 stub (decompressBody 返回 null),
 * AnalyzeUrlCore 解压链不会调用此函数, 这里返回原 body 兜底。
 *
 * 返回 this (不解压) 而非抛异常, 避免 commonMain 调用链在 iOS/鸿蒙端意外崩溃。
 */
actual fun KmpResponseBody.decompressed(): KmpResponseBody {
    // 检查 contentType 是否是 application/zip (与 jvmAndAndroidMain 判断一致)
    val contentType = this.contentType()?.toString()
    if (contentType != "application/zip") {
        return this
    }
    // nativeMain 端无 zip 解压能力, 直接返回原 body (与 jvmAndAndroidMain 行为不同, 但功能降级)
    // 调用方在 iOS/鸿蒙端不依赖此功能 (DecompressInterceptor stub 不会触发解压链)
    return this
}

/**
 * nativeMain 端 [postMultipart] 实现: 手动构造 multipart/form-data body。
 *
 * 与 okhttp3.MultipartBody.Builder 字节级对齐:
 * - boundary: "legado-native-boundary-${systemCurrentTimeMillis()}-${hashCode}" (避免与正文冲突)
 * - 普通字段: `--boundary\r\nContent-Disposition: form-data; name="key"\r\n\r\nvalue\r\n`
 * - 文件字段 (Map<*, *>): `--boundary\r\nContent-Disposition: form-data; name="key"; filename="fileName"\r\n
 *   Content-Type: contentType\r\n\r\n<bytes>\r\n`
 * - 结束: `--boundary--\r\n`
 *
 * type 入参对应 OkHttp MultipartBody 的整体 MediaType (如 "multipart/form-data"),
 * 实际不生效 (Ktor setBody 时统一用 multipart/form-data; boundary=...); 与 jvm 行为基本一致。
 */
actual fun KmpRequestBuilder.postMultipart(type: String?, form: Map<String, Any>) {
    // 用 systemCurrentTimeMillis() (posix clock_gettime 包装) 替代 jvm 的 System.currentTimeMillis()
    val boundary = "legado-native-boundary-${systemCurrentTimeMillis()}-${(form.hashCode() and 0xFFFFFF)}"
    val CRLF = "\r\n"
    val sb = StringBuilder()
    val byteParts: MutableList<ByteArray> = mutableListOf()

    form.forEach { (key, value) ->
        sb.append("--").append(boundary).append(CRLF)
        when (value) {
            is Map<*, *> -> {
                val fileName = value["fileName"] as? String ?: "file"
                val file = value["file"]
                val contentTypeStr = value["contentType"] as? String
                sb.append("Content-Disposition: form-data; name=\"").append(key)
                    .append("\"; filename=\"").append(fileName).append("\"").append(CRLF)
                if (contentTypeStr != null) {
                    sb.append("Content-Type: ").append(contentTypeStr).append(CRLF)
                }
                sb.append(CRLF)
                // 字段头先写入 (sb), 字段内容追加到 byteParts (二进制安全)
                val headerBytes = sb.toString().encodeToByteArray()
                sb.setLength(0)
                val contentBytes: ByteArray = when (file) {
                    is ByteArray -> file
                    is String -> file.encodeToByteArray()
                    else -> file?.toString()?.encodeToByteArray() ?: ByteArray(0)
                }
                // header + content + CRLF 一起作为一个 part
                byteParts.add(headerBytes + contentBytes + CRLF.encodeToByteArray())
            }
            else -> {
                sb.append("Content-Disposition: form-data; name=\"").append(key).append("\"").append(CRLF)
                sb.append(CRLF)
                sb.append(value.toString()).append(CRLF)
                byteParts.add(sb.toString().encodeToByteArray())
                sb.setLength(0)
            }
        }
    }
    // 结束 boundary
    sb.append("--").append(boundary).append("--").append(CRLF)
    val endBytes = sb.toString().encodeToByteArray()

    // 拼接所有 part: 顺序合并 byteParts + endBytes
    val totalLength = byteParts.sumOf { it.size } + endBytes.size
    val bodyBytes = ByteArray(totalLength)
    var offset = 0
    for (part in byteParts) {
        // copyInto 逐参等价 System.arraycopy(part, 0, bodyBytes, offset, part.size)
        part.copyInto(bodyBytes, destinationOffset = offset)
        offset += part.size
    }
    endBytes.copyInto(bodyBytes, destinationOffset = offset)

    // 用 commonMain 工厂方法构造 body (ios/ohos 各自 actual 内部用 NativeKmp*/OhosKmp* 包装),
    // contentType 设为 multipart/form-data; boundary=...
    val contentTypeStr = (type ?: "multipart/form-data") + "; boundary=$boundary"
    val requestBody = bodyBytes.toKmpRequestBody(contentTypeStr.toKmpMediaType())
    post(requestBody)
}

// region 内部辅助扩展函数
/**
 * 从 [KmpMediaType] 中解析 charset 名 (与 okhttp3.MediaType.charset() 的名字解析部分对齐)。
 * 为 commonMain [KmpMediaType.charsetName] expect 的 nativeMain actual (iOS/鸿蒙共用)。
 *
 * 输入示例: "text/html; charset=UTF-8" -> "UTF-8"
 * 无 charset 返回 null。
 */
actual fun KmpMediaType.charsetName(): String? {
    val value = this.toString()
    val idx = value.indexOf("charset=", ignoreCase = true)
    if (idx < 0) return null
    var charsetStr = value.substring(idx + 8).trim()
    // 截到分号或字符串结尾
    val end = charsetStr.indexOf(';')
    if (end > 0) {
        charsetStr = charsetStr.substring(0, end).trim()
    }
    // 去除可能的引号
    if (charsetStr.startsWith("\"") && charsetStr.endsWith("\"")) {
        charsetStr = charsetStr.substring(1, charsetStr.length - 1)
    }
    return charsetStr.takeIf { it.isNotEmpty() }
}

/**
 * 按 charset 名解码字节 (委托 commonMain [textCharsetCodec] 门面)。
 *
 * Kotlin/Native 无 `Charset.forName(name)` (JVM-only); textCharsetCodec 覆盖
 * UTF-8/UTF-16 系/UTF-32 系/ISO-8859-1/US-ASCII, GBK/Big5 走分端平台实现。
 * 不支持的 charset 返回 null (调用方降级 UTF-8)。
 */
private fun decodeOrNull(bytes: ByteArray, charsetName: String): String? =
    runCatching { textCharsetCodec(charsetName).decode(bytes) }.getOrNull()

// endregion
