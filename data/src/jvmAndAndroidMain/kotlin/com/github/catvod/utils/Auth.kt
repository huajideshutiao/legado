package com.github.catvod.utils

import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashMap
import java.util.UUID
import java.util.regex.Pattern

/**
 * TVBox 壳 HTTP 鉴权工具: FQCN / 方法签名逐字对齐 FongMi catvod 模块的 utils.Auth
 * (唯一调用方是 net.interceptor.AuthInterceptor 的 Basic / Digest 挑战应答)。
 * Base64 以 java.util.Base64 等价实现 (NO_WRAP 语义)。
 */
object Auth {

    private val DIGEST: Pattern = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s\"]+))")

    @JvmStatic
    fun basic(userInfo: String): String {
        var info = userInfo
        if (!info.contains(":")) info += ":"
        return "Basic " + java.util.Base64.getEncoder().encodeToString(info.toByteArray(StandardCharsets.UTF_8))
    }

    @JvmStatic
    fun digest(userInfo: String, header: String, request: Request): String {
        val params = parseDigest(header.substring(7))
        val credentials = userInfo.split(":", limit = 2)
        val username = credentials[0]
        val password = if (credentials.size > 1) credentials[1] else ""
        val realm = params.getOrDefault("realm", "")
        val nonce = params.getOrDefault("nonce", "")
        val opaque = params["opaque"]
        val uri = digestUri(request)
        val qop = selectQop(params["qop"])
        val nc = "00000001"
        val cnonce = newCnonce()
        val ha1 = Crypto.md5("$username:$realm:$password")
        val ha2 = Crypto.md5(request.method + ":" + uri)
        val response = digestResponse(ha1, ha2, nonce, nc, cnonce, qop)
        return buildHeader(username, realm, nonce, uri, nc, cnonce, qop, response, opaque)
    }

    private fun digestUri(request: Request): String {
        val query = request.url.encodedQuery
        val path = request.url.encodedPath
        return if (query != null) "$path?$query" else path
    }

    private fun digestResponse(ha1: String, ha2: String, nonce: String, nc: String, cnonce: String, qop: String): String =
        if (qop.isEmpty()) {
            Crypto.md5("$ha1:$nonce:$ha2")
        } else {
            Crypto.md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        }

    private fun buildHeader(
        username: String, realm: String, nonce: String, uri: String, nc: String,
        cnonce: String, qop: String, response: String, opaque: String?,
    ): String {
        val fields = ArrayList<String>()
        fields.add("username=\"$username\"")
        fields.add("realm=\"$realm\"")
        fields.add("nonce=\"$nonce\"")
        fields.add("uri=\"$uri\"")
        val hasQop = qop.isNotEmpty()
        if (hasQop) fields.add("cnonce=\"$cnonce\"")
        if (hasQop) fields.add("nc=$nc")
        if (hasQop) fields.add("qop=$qop")
        fields.add("response=\"$response\"")
        if (opaque != null) fields.add("opaque=\"$opaque\"")
        return "Digest " + fields.joinToString(", ")
    }

    private fun newCnonce(): String = UUID.randomUUID().toString().replace("-", "")

    private fun selectQop(qop: String?): String {
        if (qop.isNullOrEmpty()) return ""
        for (option in qop.split(",")) if ("auth".equals(option.trim(), ignoreCase = true)) return "auth"
        return ""
    }

    private fun parseDigest(header: String): Map<String, String> {
        val params = HashMap<String, String>()
        val matcher = DIGEST.matcher(header.trim())
        while (matcher.find()) {
            val key = matcher.group(1)
            val value = if (matcher.group(2) != null) matcher.group(2) else matcher.group(3)
            if (value != null) params[key] = value.trim()
        }
        return params
    }
}
