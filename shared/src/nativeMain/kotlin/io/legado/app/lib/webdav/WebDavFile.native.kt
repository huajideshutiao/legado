package io.legado.app.lib.webdav

/**
 * WebDav 文件元数据 nativeMain actual 实现。
 *
 * 由 iosMain / ohosMain 共用 (nativeMain 中间源集下沉, 原 iosMain/ohosMain actual 完全一致,
 * 仅 `actual constructor` 关键字与注释语境差异, 统一为 nativeMain)。
 *
 * 详见 commonMain/kotlin/io/legado/app/lib/webdav/WebDavFile.kt expect 注释。
 *
 * - 纯 Kotlin 实现 (继承 [WebDav] nativeMain actual, 无 JVM 专属 API)
 * - 与 jvmAndAndroidMain actual 行为一致: `isDir` 用 `by lazy` 缓存,
 *   `isDir(contentType, resourceType)` 静态方法判断目录
 *
 * 注: nativeMain 不依赖任何平台专属 API, 纯 Kotlin 实现, iOS/鸿蒙共用。
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
