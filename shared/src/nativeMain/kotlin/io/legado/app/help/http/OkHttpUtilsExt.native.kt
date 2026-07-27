package io.legado.app.help.http

import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.Utf8BomUtils
import kotlin.text.Charset

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
 * - charset 解析: jvm 端用 `Charset.forName(name)` 支持任意 charset, nativeMain 端 Kotlin/Native 仅支持
 *   Kotlin 标准库内置 charset (UTF-8/UTF-16/ISO-8859-1/US-ASCII), 其他 charset 降级为 UTF-8
 *   (与 SymmetricCrypto.native.kt 降级策略一致)
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

    // 1. 显式指定的 encode 优先 (调用方传入)
    charsetName?.let {
        val charset = charsetByName(charsetName) ?: Charsets.UTF_8
        return String(responseBytes, charset)
    }

    // 2. 根据 HTTP 头 contentType 中的 charset 判断
    this.contentType()?.charset()?.let { charset ->
        return String(responseBytes, charset)
    }

    // 3. 根据内容判断 (EncodingDetect 已下沉 commonMain, 用 Ksoup 解析 meta charset)
    charsetName = EncodingDetect.getHtmlEncode(responseBytes)
    val charset = charsetByName(charsetName) ?: Charsets.UTF_8
    return String(responseBytes, charset)
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
 * - boundary: "legado-native-boundary-${getTimeMillis()}-${hashCode}" (避免与正文冲突)
 * - 普通字段: `--boundary\r\nContent-Disposition: form-data; name="key"\r\n\r\nvalue\r\n`
 * - 文件字段 (Map<*, *>): `--boundary\r\nContent-Disposition: form-data; name="key"; filename="fileName"\r\n
 *   Content-Type: contentType\r\n\r\n<bytes>\r\n`
 * - 结束: `--boundary--\r\n`
 *
 * type 入参对应 OkHttp MultipartBody 的整体 MediaType (如 "multipart/form-data"),
 * 实际不生效 (Ktor setBody 时统一用 multipart/form-data; boundary=...); 与 jvm 行为基本一致。
 */
actual fun KmpRequestBuilder.postMultipart(type: String?, form: Map<String, Any>) {
    // 用 kotlin.system.getTimeMillis() (Kotlin 1.9+ commonMain API) 替代 jvm 的 System.currentTimeMillis()
    val boundary = "legado-native-boundary-${kotlin.system.getTimeMillis()}-${(form.hashCode() and 0xFFFFFF)}"
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
                val headerBytes = sb.toString().toByteArray(Charsets.UTF_8)
                sb.setLength(0)
                val contentBytes: ByteArray = when (file) {
                    is ByteArray -> file
                    is String -> file.toByteArray(Charsets.UTF_8)
                    else -> file?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                }
                // header + content + CRLF 一起作为一个 part
                byteParts.add(headerBytes + contentBytes + CRLF.toByteArray(Charsets.UTF_8))
            }
            else -> {
                sb.append("Content-Disposition: form-data; name=\"").append(key).append("\"").append(CRLF)
                sb.append(CRLF)
                sb.append(value.toString()).append(CRLF)
                byteParts.add(sb.toString().toByteArray(Charsets.UTF_8))
                sb.setLength(0)
            }
        }
    }
    // 结束 boundary
    sb.append("--").append(boundary).append("--").append(CRLF)
    val endBytes = sb.toString().toByteArray(Charsets.UTF_8)

    // 拼接所有 part: 顺序合并 byteParts + endBytes
    val totalLength = byteParts.sumOf { it.size } + endBytes.size
    val bodyBytes = ByteArray(totalLength)
    var offset = 0
    for (part in byteParts) {
        System.arraycopy(part, 0, bodyBytes, offset, part.size)
        offset += part.size
    }
    System.arraycopy(endBytes, 0, bodyBytes, offset, endBytes.size)

    // 用 commonMain 工厂方法构造 body (ios/ohos 各自 actual 内部用 NativeKmp*/OhosKmp* 包装),
    // contentType 设为 multipart/form-data; boundary=...
    val contentTypeStr = (type ?: "multipart/form-data") + "; boundary=$boundary"
    val requestBody = bodyBytes.toKmpRequestBody(contentTypeStr.toKmpMediaType())
    post(requestBody)
}

// region 内部辅助扩展函数
/**
 * 从 [KmpMediaType] 中解析 charset (与 okhttp3.MediaType.charset() 行为对齐)。
 *
 * 输入示例: "text/html; charset=UTF-8" -> Charsets.UTF_8
 * 无 charset 返回 null。
 */
private fun KmpMediaType.charset(): Charset? {
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
    return charsetByName(charsetStr)
}

/**
 * 按 charset 名解析为 Kotlin 标准库 [Charset]。
 *
 * Kotlin/Native 不支持 `Charset.forName(name)` (JVM-only), 仅支持 kotlin.text.Charsets
 * 内置 charset: UTF-8 / UTF-16 / UTF-16BE / UTF-16LE / ISO-8859-1 / US-ASCII。
 *
 * 与 jvmAndAndroidMain `Charset.forName(name)` 行为差异:
 * - jvm 端支持任意 JVM charset (GBK / Big5 / EUC-JP 等, 由 icu4j 提供)
 * - nativeMain 端仅支持上述 6 种内置 charset, 其他返回 null (调用方降级为 UTF-8)
 *
 * 对常见 web charset (UTF-8 / ISO-8859-1) 行为等价, GBK 等亚洲 charset 场景为已知降级。
 */
private fun charsetByName(name: String): Charset? {
    return when (name.trim().uppercase().replace("-", "")) {
        "UTF8" -> Charsets.UTF_8
        "UTF16" -> Charsets.UTF_16
        "UTF16BE" -> Charsets.UTF_16BE
        "UTF16LE" -> Charsets.UTF_16LE
        "ISO88591" -> Charsets.ISO_8859_1
        "USASCII", "ASCII" -> Charsets.US_ASCII
        else -> null
    }
}

// endregion
