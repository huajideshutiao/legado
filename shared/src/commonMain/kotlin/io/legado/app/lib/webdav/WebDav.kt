package io.legado.app.lib.webdav

import io.legado.app.utils.InputStream

/**
 * WebDav 客户端 expect 声明。
 *
 * - jvmAndAndroidMain actual: 基于 OkHttp 的原实现, 行为零变化
 * - nativeMain actual (iosMain/ohosMain 共用): 基于 Ktor CIO 的真实实现,
 *   PROPFIND/MKCOL/PUT/DELETE/GET/Range 全可用
 *
 * **KMP 化说明**:
 * - `upload(file: java.io.File, ...)` 重载仅在 jvmAndAndroidMain actual 中存在
 *   (File 非 KMP 类型), commonMain 调用方需走 [upload] (localPath) 或 [upload] (ByteArray)。
 * - [downloadInputStream] 返回类型用 `io.legado.app.utils.InputStream` (项目 expect
 *   abstract class, jvmAndAndroidMain actual typealias 到 java.io.InputStream)。
 * - `DEFAULT_CONTENT_TYPE` 提升为 top-level `const val`, 供 expect 默认参数引用。
 */
expect open class WebDav(
    path: String,
    authorization: Authorization
) {

    val path: String
    val authorization: Authorization

    /** 转 http/https 后的 URL 字符串, 解析失败为 null */
    val httpUrl: String?

    /**
     * 获取当前 url 文件信息
     */
    @Throws(WebDavException::class)
    suspend fun getWebDavFile(): WebDavFile?

    /**
     * 列出当前路径下的文件
     * @return 文件列表
     */
    @Throws(WebDavException::class)
    suspend fun listFiles(): List<WebDavFile>

    /**
     * 文件是否存在
     */
    suspend fun exists(): Boolean

    /**
     * 检查用户名密码是否有效
     */
    suspend fun check(): Boolean

    /**
     * 根据自己的URL，在远程处创建对应的文件夹
     * @return 是否创建成功
     */
    suspend fun makeAsDir(): Boolean

    /**
     * 下载到本地
     * @param savedPath       本地的完整路径，包括最后的文件名
     * @param replaceExisting 是否替换本地的同名文件
     */
    @Throws(WebDavException::class)
    suspend fun downloadTo(savedPath: String, replaceExisting: Boolean)

    /**
     * 下载文件,返回ByteArray
     */
    @Throws(WebDavException::class)
    suspend fun download(): ByteArray

    /**
     * 上传文件(本地路径)
     */
    @Throws(WebDavException::class)
    suspend fun upload(localPath: String, contentType: String = DEFAULT_CONTENT_TYPE)

    /**
     * 上传文件(字节数组)
     */
    @Throws(WebDavException::class)
    suspend fun upload(byteArray: ByteArray, contentType: String = DEFAULT_CONTENT_TYPE)

    /**
     * 下载文件输入流
     */
    @Throws(WebDavException::class)
    suspend fun downloadInputStream(): InputStream

    /**
     * 移除文件/文件夹
     */
    suspend fun delete(): Boolean

    /**
     * 按 Range 读取远程文件片段
     */
    fun readRange(offset: Long, length: Int, fileSize: Long = -1): ByteArray

    companion object {
        /**
         * 由路径构造 WebDav, 自动从 serverID 解析认证
         */
        fun fromPath(path: String): WebDav
    }
}

/** WebDav 上传默认 Content-Type; top-level const 以便 expect 默认参数引用 */
const val DEFAULT_CONTENT_TYPE: String = "application/octet-stream"
