package io.legado.app.utils

/**
 * URL 保留字符转义 (原 jvmAndAndroidMain `UrlUtil.replaceReservedChar` 下沉)。
 * 纯字符串替换无平台依赖, 下沉后 commonMain 的 AppWebDavShared 进度文件名可复用。
 */
fun replaceReservedChar(text: String): String {
    return text.replace("%", "%25")
        .replace(" ", "%20")
        .replace("\"", "%22")
        .replace("#", "%23")
        .replace("&", "%26")
        .replace("(", "%28")
        .replace(")", "%29")
        .replace("+", "%2B")
        .replace(",", "%2C")
        .replace("/", "%2F")
        .replace(":", "%3A")
        .replace(";", "%3B")
        .replace("<", "%3C")
        .replace("=", "%3D")
        .replace(">", "%3E")
        .replace("?", "%3F")
        .replace("@", "%40")
        .replace("\\", "%5C")
        .replace("|", "%7C")
}
