package io.legado.app.utils

import com.fleeksoft.ksoup.Ksoup

/**
 * 自动获取文件/字节的编码。
 *
 * 主体下沉 commonMain: [getHtmlEncode]/[getEncode](ByteArray) 均为纯 Kotlin + Ksoup
 * (KMP 库, commonMain 可见) + [detectCharsetName] expect/actual (底层 icu4j CharsetDetector 留 JVM)。
 *
 * File 重载 (依赖 java.io.File) 仍在 jvmAndAndroidMain 端以扩展函数形式提供
 * (见 EncodingDetectFileExt.kt), 跨模块同包名同签名扩展自动合并, 消费方 import 零改动。
 *
 * 包名/对象名不变, 调用方 `EncodingDetect.xxx(...)` 写法不变。
 */
object EncodingDetect {

    private val headTagRegex = "(?i)<head>[\\s\\S]*?</head>".toRegex()
    private val headOpenBytes = "<head>".toByteArray()
    private val headCloseBytes = "</head>".toByteArray()

    fun getHtmlEncode(bytes: ByteArray): String {
        try {
            var head: String? = null
            val startIndex = bytes.indexOf(headOpenBytes)
            if (startIndex > -1) {
                val endIndex = bytes.indexOf(headCloseBytes, startIndex)
                if (endIndex > -1) {
                    head = String(bytes.copyOfRange(startIndex, endIndex + headCloseBytes.size))
                }
            }
            val doc = Ksoup.parseBodyFragment(head ?: headTagRegex.find(String(bytes))!!.value)
            val metaTags = doc.getElementsByTag("meta")
            var charsetStr: String
            for (metaTag in metaTags) {
                charsetStr = metaTag.attr("charset")
                if (!charsetStr.isNullOrEmpty()) {
                    return charsetStr
                }
                val httpEquiv = metaTag.attr("http-equiv")
                if (httpEquiv.equals("content-type", true)) {
                    val content = metaTag.attr("content")
                    val idx = content.indexOf("charset=", ignoreCase = true)
                    charsetStr = if (idx > -1) {
                        content.substring(idx + "charset=".length)
                    } else {
                        content.substringAfter(";")
                    }
                    if (!charsetStr.isNullOrEmpty()) {
                        return charsetStr
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return getEncode(bytes)
    }

    fun getEncode(bytes: ByteArray): String {
        val match = detectCharsetName(bytes)
        return match ?: "UTF-8"
    }
}
