package io.legado.app.help.book

import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.App
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileDoc
import io.legado.app.utils.exists
import io.legado.app.utils.find
import io.legado.app.utils.inputStream
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isUri
import io.legado.app.utils.parseToUri
import io.legado.app.utils.toastOnUi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [LocalBookLocator] 安卓端 actual 实现。
 *
 * 本地书路径解析规则:
 * - bookUrl 形如 `file:///storage/.../book.txt` → 取 Uri.path 转 File 绝对路径
 * - bookUrl 形如 `content://...` (SAF) → 返回 null (非文件系统路径, 走 DocumentFile 通道)
 * - bookUrl 形如 `/path/book.txt` 或 `C:\path\book.txt` → 直接当本地路径
 * - 网络书源 (`http(s)://...`) → 返回 null
 *
 * [getReadableFileRef] 在路径解析之外多做一层"引用失效时按文件名找回": bookUrl 打不开时在
 * 书籍保存目录与导入目录里找同名文件 (各查 5 层、最多 100 项), 只服务本次读取, 不回写 bookUrl。
 *
 * 缓存: 用 [ConcurrentHashMap] 缓存 bookUrl → 本地路径, 避免重复 URI 解析。
 *
 * 注册: app 端 [io.legado.app.App.onCreate] 早期通过 [LocalBookLocators.register] 注入。
 * 桌面 JVM 端对应 [io.legado.app.help.book.JvmLocalBookLocator] (java.nio.file.Path 实现)。
 *
 * 模式参考 [BookStorageProviders] / [BookHelpProviders]。
 */
class AndroidLocalBookLocator : LocalBookLocator {

    /** bookUrl → 本地路径缓存 (避免重复 URI 解析)。 */
    private val pathCache = ConcurrentHashMap<String, String>()

    override fun getLocalPath(book: Book): String? {
        if (!book.isLocal) return null
        pathCache[book.bookUrl]?.let { return it }
        val path = parseLocalPath(book.bookUrl) ?: return null
        pathCache[book.bookUrl] = path
        return path
    }

    /**
     * 取本地书当前可用的文件引用: bookUrl 指向的文件打不开时, 按文件名在"书籍保存目录"与
     * "导入目录"里找回同名文件 (原版 `Book.getLocalUri()` 的定位口径: 每个目录查 5 层、最多 100 项,
     * 两个目录是同一处时不重复查)。
     *
     * 找回结果只服务本次读取: 不回写 bookUrl、不落库、不写路径缓存。
     *
     * SAF 引用 (`content://`) 不是文件系统路径, [getLocalPath] 对它返回 null, 这里按引用原样
     * 交给调用方 (content scheme 开文件描述符)。
     */
    override fun getReadableFileRef(book: Book): String? {
        if (!book.isLocal) return null
        if (isRefReadable(book.bookUrl)) return book.bookUrl
        // 按名找回的候选同样要过"能当书打开"这一关 (含必须是普通文件), 不合格按找不到处理
        return findLocalBookFileDoc(book.originName)?.uri?.toString()?.takeIf(::isRefReadable)
    }

    /**
     * [ref] 能否当书打开: 必须是普通文件且能打开一次 (与桌面/iOS/鸿蒙的 `File.isFile` 同一判据)。
     *
     * 普通文件判定是必要的收口 —— 指向目录的引用能打开却不能读 (SAF 目录引用是目录文档,
     * 文件系统目录在 Linux 上同样打得开), 放行后要等阅读层取值才失败。
     *
     * 引用按"无 scheme 即当文件路径"构造 (同原版 `Book.getLocalUri()` 的
     * `if (bookUrl.isUri()) bookUrl.toUri() else Uri.fromFile(File(bookUrl))`): 文件名含 `#` / `?` 时
     * [Uri.parse] 会把它们当成片段 / 查询分隔符而截断路径, 得出"打不开"的误判; [parseToUri] 对
     * 无 scheme 引用走 [Uri.fromFile], 这两个字符被转义后再取回原样路径, 与要打开的文件一致。
     */
    private fun isRefReadable(ref: String): Boolean {
        val uri = ref.parseToUri()
        if (!isRegularFile(uri)) return false
        val stream = uri.inputStream(App.instance).getOrNull() ?: return false
        runCatching { stream.close() }
        return true
    }

    /**
     * [uri] 是否指向普通文件。
     *
     * - `content://` (SAF): 按文档类型判, 目录文档的 MIME 类型是目录, 不算普通文件;
     *   提供方不报文档类型的引用 (如下载管理器给的地址) 不据此拦下, 交给打开探测定成败
     * - 其余: 按文件系统路径判 ([File.isFile]), 路径取 [Uri.path]
     */
    private fun isRegularFile(uri: Uri): Boolean = runCatching {
        if (uri.isContentScheme()) {
            val doc = DocumentFile.fromSingleUri(App.instance, uri) ?: return@runCatching false
            !doc.isDirectory
        } else {
            val path = uri.path ?: return@runCatching false
            File(path).isFile
        }
    }.getOrDefault(false)

    override fun removeLocalPathCache(book: Book) {
        pathCache.remove(book.bookUrl)
    }

    /**
     * 解析 bookUrl 为本地文件路径。
     *
     * - `file:///storage/.../book.txt` → Uri.path 转 File 绝对路径
     * - `content://...` → null (SAF, 非文件系统路径)
     * - `http(s)://...` → null (网络书源, 非本地)
     * - 其他 (`/path` 或 `C:\path`) → 直接当本地路径
     */
    private fun parseLocalPath(bookUrl: String): String? {
        return try {
            when {
                bookUrl.startsWith("file:") -> {
                    val uri: Uri = bookUrl.toUri()
                    val path = uri.path ?: return null
                    File(path).absolutePath
                }
                bookUrl.startsWith("content:") -> {
                    // content scheme (SAF) 不是文件系统路径, 返回 null
                    null
                }
                bookUrl.startsWith("http:") || bookUrl.startsWith("https:") -> {
                    null
                }
                else -> {
                    File(bookUrl).absolutePath
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 在"书籍保存目录"与"导入目录"里按文件名找回本地书 (原版 `Book.getLocalUri()` 的定位口径:
 * 保存目录查 5 层、最多 100 项; 再查导入目录, 两个目录相同则不重复查)。只定位, 不写库。
 *
 * 保存目录已失效 (SAF 树引用打不开) 时提示用户重新设置 —— 与原版同一提示, 本函数因此会弹提示。
 *
 * 找不到返回 null。
 */
internal fun findLocalBookFileDoc(originName: String): FileDoc? {
    val defaultBookDir = AppConfig.defaultBookTreeUri
    if (!defaultBookDir.isNullOrBlank()) {
        val treeFileDoc = FileDoc.fromUri(defaultBookDir.toUri(), true)
        if (!treeFileDoc.exists()) {
            App.instance.toastOnUi("书籍保存目录失效，请重新设置！")
        } else {
            treeFileDoc.find(originName, 5, 100)?.let { return it }
        }
    }
    val importBookDir = AppConfig.importBookPath
    if (!importBookDir.isNullOrBlank() && defaultBookDir != importBookDir) {
        val treeUri =
            if (importBookDir.isUri()) importBookDir.toUri() else Uri.fromFile(File(importBookDir))
        FileDoc.fromUri(treeUri, true).find(originName, 5, 100)?.let { return it }
    }
    return null
}
