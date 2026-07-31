package io.legado.desktop.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.model.fileBook.LocalZipWrapper
import io.legado.app.model.fileBook.RangedSource
import io.legado.app.model.fileBook.RemoteZipWrapper
import io.legado.app.model.fileBook.ZipFileWrapperFactory

/**
 * [ZipFileWrapperFactory] 桌面 JVM 实现。
 *
 * 分派对齐 app 端 `ZipFileWrappers.create`:
 * - `webdav::` / `http(s)://` → [RemoteZipWrapper] (WebDAV 范围读取, 不全量下载)
 * - 本地 .cbz / .zip → [LocalZipWrapper]
 * - 其他本地压缩包 (rar/7z/cbr) → null (desktop 无 libarchive, app 端走 LocalArchiveWrapper)
 *
 * # bookUrl 解析
 * 本地路径经 [LocalBookLocators.get().getLocalPath] 解析:
 * - `file:///C:/path/book.cbz` → `C:\path\book.cbz`
 * - `/path/book.cbz` → 直接当本地路径
 *
 * 注册: desktop `Main.kt` 中
 * `ZipFileWrapperFactoryProviders.register(DesktopZipFileWrapperFactory)`。
 */
object DesktopZipFileWrapperFactory : ZipFileWrapperFactory {

    override fun create(book: Book): ZipFileWrapperFactory.CreateResult? {
        return runCatching {
            if (book.bookUrl.startsWith(BookType.webDavTag) || book.bookUrl.startsWith("http")) {
                return@runCatching createRemote(book)
            }
            val path = LocalBookLocators.get().getLocalPath(book) ?: return null
            val lowerPath = path.lowercase()
            if (lowerPath.endsWith(".cbz") || lowerPath.endsWith(".zip")) {
                ZipFileWrapperFactory.CreateResult(LocalZipWrapper(java.io.File(path)))
            } else {
                null
            }
        }.onFailure { AppLog.put("读取Cbz文件失败\n${it.localizedMessage}", it) }.getOrNull()
    }

    /** 远程 cbz: 从 book.variable("cbz:") + wordCount 恢复 zip 元数据, 避免每次重新探测中央目录。 */
    private fun createRemote(book: Book): ZipFileWrapperFactory.CreateResult {
        val url = book.getRemoteUrl() ?: book.bookUrl
        val webdav = runCatching { WebDav.fromPath(url) }.getOrElse {
            AppWebDavShared.authorization?.let { WebDav(url, it) }
                ?: throw WebDavException("No Auth")
        }
        var eocd = 0L
        var central = 0L
        var size = 0L
        book.variable?.takeIf { it.startsWith("cbz:") }?.substring(4)?.split(",")?.let { p ->
            eocd = p.getOrNull(0)?.toLongOrNull() ?: 0L
            central = p.getOrNull(1)?.toLongOrNull() ?: 0L
            size = p.getOrNull(2)?.toLongOrNull() ?: 0L
        }
        val count = book.wordCount?.replace("页", "")?.toIntOrNull() ?: 0
        val source = RangedSource { offset, length, fileSize ->
            webdav.readRange(offset, length, fileSize)
        }
        val wrapper = if (eocd > 0 && central > 0 && count > 0) {
            RemoteZipWrapper(source, book.originName, size, eocd, central, count)
        } else {
            RemoteZipWrapper(source, book.originName, size)
        }
        return ZipFileWrapperFactory.CreateResult(wrapper)
    }
}
