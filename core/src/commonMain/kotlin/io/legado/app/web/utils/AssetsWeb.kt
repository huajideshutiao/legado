package io.legado.app.web.utils

/**
 * 静态资源读取结果 (mime + bytes), 平台无关。
 *
 * 各端壳 (NanoHTTPD / Ktor) 把本对象转成原生响应。
 */
data class WebAssetResponse(
    val bytes: ByteArray,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebAssetResponse) return false
        return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
}

/**
 * 静态资源回写壳: path -> [WebAssetSource] -> [WebAssetResponse] (bytes + mime)。
 *
 * 返回平台无关的 [WebAssetResponse], 各端壳自行转成原生响应:
 * - NanoHTTPD (jvmAndAndroidMain): newChunkedResponse(OK, mime, ByteArrayInputStream(bytes))
 * - Ktor (nativeMain): call.respondBytes(bytes, ContentType.parse(mime))
 *
 * # 单一数据源
 * web 资源唯一数据源在 `ui/src/commonMain/composeResources/files/web/`, 四端
 * (Android/桌面 JVM/iOS/鸿蒙) 均经 :ui 的 ComposeResourceWebAssetSource 读取, 无平台端副本。
 */
class AssetsWeb(rootPath: String = "web") {
    private var rootPath = "web"

    init {
        if (rootPath.isNotEmpty()) {
            this.rootPath = rootPath
        }
    }

    suspend fun getResponse(path: String): WebAssetResponse {
        // 统一用正斜杠: composeResources 资源路径均用 / 分隔
        val fullPath = (rootPath + path).replace("/+".toRegex(), "/")
        val bytes = WebAssetSources.get().read(fullPath)
        return WebAssetResponse(bytes, getMimeType(fullPath))
    }

    private fun getMimeType(path: String): String {
        val suffix = path.substring(path.lastIndexOf("."))
        return when {
            suffix.equals(".html", ignoreCase = true)
                    || suffix.equals(".htm", ignoreCase = true) -> "text/html"
            suffix.equals(".js", ignoreCase = true) -> "text/javascript"
            suffix.equals(".css", ignoreCase = true) -> "text/css"
            suffix.equals(".ico", ignoreCase = true) -> "image/x-icon"
            suffix.equals(".jpg", ignoreCase = true) -> "image/jpg"
            else -> "text/html"
        }
    }
}
