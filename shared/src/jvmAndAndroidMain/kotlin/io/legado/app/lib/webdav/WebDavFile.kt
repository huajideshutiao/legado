package io.legado.app.lib.webdav

/**
 * webDavFile
 *
 * jvmAndAndroidMain actual 实现: 原逻辑零变化, 仅加 `actual` 修饰。
 * 详见 commonMain/kotlin/io/legado/app/lib/webdav/WebDavFile.kt expect 注释。
 */
@Suppress("unused")
actual class WebDavFile actual constructor(
    urlStr: String,
    authorization: Authorization,
    actual val displayName: String,
    actual val urlName: String,
    actual val size: Long,
    actual val contentType: String,
    actual val resourceType: String,
    actual val lastModify: Long
) : WebDav(urlStr, authorization) {

    actual val isDir by lazy {
        isDir(contentType, resourceType)
    }

    actual companion object {
        actual fun isDir(contentType: String, resourceType: String): Boolean {
            return contentType == "httpd/unix-directory"
                    || resourceType.lowercase().contains("collection")
        }
    }

}
