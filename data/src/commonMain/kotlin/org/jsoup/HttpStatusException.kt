package org.jsoup

import okio.IOException

/** 与原版 jsoup 同名同语义:HTTP 状态码非 2xx/3xx 且未 ignoreHttpErrors 时抛出 */
class HttpStatusException(
    message: String,
    val statusCode: Int,
    val url: String,
) : IOException("$message. Status=$statusCode, URL=[$url]")
