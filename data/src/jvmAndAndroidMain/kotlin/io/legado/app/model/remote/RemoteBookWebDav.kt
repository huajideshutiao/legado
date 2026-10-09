package io.legado.app.model.remote

import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDavShared
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.fileBook.FileBookProviders
import io.legado.app.utils.isNetworkAvailable
import kotlinx.coroutines.runBlocking

/**
 * WebDav 远程书籍管理 (下沉自 app 端 `model/remote/RemoteBookWebDav.kt`)。
 *
 * Android 专属依赖已换成 KMP 抽象, 行为不变:
 * `FileBook.saveBookFile` → [FileBookProviders]; `book.update()` → [AppDbProviders];
 * `bookUrl.toUri()` + content scheme 分支 → [uploadLocalBook] expect/actual。
 *
 * # 已上移出本类的部分
 * [getRemoteBook] / [downloadRemoteBook] 的实现现置于共享层 [AppWebDavShared] (四端共用),
 * 本类仅保留 [RemoteBookManager] 的实现位并转发。
 * [upload] 暂留本类: 它走 [uploadLocalBook] 的 content scheme 分支 (读不到就报错),
 * 而共享上传件在取不到文件系统路径时回退 [FileBookProviders.getBookInputStream] (会转去
 * “重新解压/重新下载”), 两者在失败路径上不同; 合并会改变 Android 的失败行为, 故不并。
 */
class RemoteBookWebDav(
    val rootBookUrl: String,
    val authorization: Authorization,
    val serverID: Long? = null
) : RemoteBookManager() {

    init {
        runBlocking {
            WebDav(rootBookUrl, authorization).makeAsDir()
        }
    }


    private suspend fun <T> withNetworkCheck(block: suspend () -> T): T {
        if (!isNetworkAvailable()) throw NoStackTraceException("网络不可用")
        return block()
    }

    @Throws(Exception::class)
    override suspend fun getRemoteBookList(path: String): MutableList<RemoteBook> =
        withNetworkCheck {
            val remoteBooks = mutableListOf<RemoteBook>()
            //读取文件列表
            val remoteWebDavFileList: List<WebDavFile> =
                WebDav(path, authorization).listFiles()
            //转化远程文件信息到本地对象
            remoteWebDavFileList.forEach { webDavFile ->
                if (webDavFile.isDir
                    || bookFileRegex.matches(webDavFile.displayName)
                    || archiveFileRegex.matches(webDavFile.displayName)
                ) {
                    //扩展名符合阅读的格式则认为是书籍
                    remoteBooks.add(RemoteBook.create(webDavFile))
                }
            }
            remoteBooks
        }

    override suspend fun getRemoteBook(path: String): RemoteBook? =
        AppWebDavShared.getRemoteBook(path, authorization)

    /**
     * 下载远端书籍到本地保存位置。
     *
     * 实现已上移到共享层 [AppWebDavShared.downloadRemoteBook] (四端同一份: 保存位置前置检查
     * + 网络检查 + 经 [FileBookProviders] 落盘), 本类只保留 [RemoteBookManager] 的实现位
     * 并把本实例的凭据传过去 (自定义服务器不能用共享层默认配置)。
     */
    override suspend fun downloadRemoteBook(remoteBook: RemoteBook): String =
        AppWebDavShared.downloadRemoteBook(remoteBook, authorization)

    override suspend fun upload(book: Book) = withNetworkCheck {
        val putUrl = "$rootBookUrl${book.originName}"
        WebDav(putUrl, authorization).uploadLocalBook(book.bookUrl)
        book.origin = BookType.webDavTag + CustomUrl(putUrl)
            .putAttribute("serverID", serverID)
            .toString()
        AppDbProviders.get().bookDao.update(book)
    }

    override suspend fun delete(remoteBookUrl: String) = withNetworkCheck {
        WebDav(remoteBookUrl, authorization).delete()
        Unit
    }

    fun getWebDav(path: String): WebDav {
        return WebDav(path, authorization)
    }

}
