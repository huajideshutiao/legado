package io.legado.app.lib.webdav

/**
 * WebDav 文件元数据 expect 声明。
 *
 * - jvmAndAndroidMain actual: 纯 Kotlin 原实现 (继承 [WebDav]), 行为零变化
 * - nativeMain actual (iosMain/ohosMain 共用): 纯 Kotlin 实现 (继承 [WebDav] nativeMain actual)
 *
 * **KMP 化说明**: 继承 [WebDav] (expect class), 故本身也需 expect/actual。
 * 实现体无 JVM 专属 API, actual 仅原样加 `actual` 修饰。
 */
expect class WebDavFile(
    urlStr: String,
    authorization: Authorization,
    displayName: String,
    urlName: String,
    size: Long,
    contentType: String,
    resourceType: String,
    lastModify: Long
) : WebDav {

    val displayName: String
    val urlName: String
    val size: Long
    val contentType: String
    val resourceType: String
    val lastModify: Long

    /** 是否为目录 */
    val isDir: Boolean

    companion object {
        /**
         * 根据 contentType 和 resourceType 判断是否为目录
         */
        fun isDir(contentType: String, resourceType: String): Boolean
    }
}
