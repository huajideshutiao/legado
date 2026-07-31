package io.legado.app.model.remote

import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.PreferenceProviders
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
 * `AppConfig.defaultBookTreeUri` → [PreferenceProviders]; `FileBook.saveBookFile` →
 * [FileBookProviders]; `book.update()` → [AppDbProviders]; `bookUrl.toUri()` +
 * content scheme 分支 → [uploadLocalBook] expect/actual。
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

    override suspend fun getRemoteBook(path: String): RemoteBook? = withNetworkCheck {
        val webDavFile = WebDav(path, authorization).getWebDavFile()
            ?: return@withNetworkCheck null
        RemoteBook.create(webDavFile)
    }

    override suspend fun downloadRemoteBook(remoteBook: RemoteBook): String {
        // 对照原版 AppConfig.defaultBookTreeUri (stringPrefClearOnEmpty, 空即未设置)
        if (PreferenceProviders.get().getString(PreferKey.defaultBookTreeUri).isEmpty()) {
            throw NoStackTraceException("没有设置书籍保存位置!")
        }
        return withNetworkCheck {
            val webdav = WebDav(remoteBook.path, authorization)
            webdav.downloadInputStream().let { inputStream ->
                FileBookProviders.get().saveBookFile(inputStream, remoteBook.filename)
            }
        }
    }

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
