package io.legado.desktop.help.book

import io.legado.app.data.entities.Book
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.model.fileBook.LocalZipWrapper
import io.legado.app.model.fileBook.ZipFileWrapperFactory

/**
 * [ZipFileWrapperFactory] 桌面 JVM 实现。
 *
 * 用 [LocalZipWrapper] 打开本地 .cbz / .zip 文件, 替代 app 端
 * `ZipFileWrappers.create` (后者还处理 content URI / PFD / WebDAV 远程,
 * desktop 无这些场景)。
 *
 * # bookUrl 解析
 * 经 [LocalBookLocators.get().getLocalPath] 解析 bookUrl 为本地路径:
 * - `file:///C:/path/book.cbz` → `C:\path\book.cbz`
 * - `/path/book.cbz` → 直接当本地路径
 * - `http(s)://...` → null (desktop 暂不支持远程 cbz, 由调用方 runCatching 兜底)
 *
 * 注册: desktop `Main.kt` 中
 * `ZipFileWrapperFactoryProviders.register(DesktopZipFileWrapperFactory)`。
 */
object DesktopZipFileWrapperFactory : ZipFileWrapperFactory {

    override fun create(book: Book): ZipFileWrapperFactory.CreateResult? {
        return runCatching {
            val path = LocalBookLocators.get().getLocalPath(book) ?: return null
            val lowerPath = path.lowercase()
            if (lowerPath.endsWith(".cbz") || lowerPath.endsWith(".zip")) {
                ZipFileWrapperFactory.CreateResult(LocalZipWrapper(java.io.File(path)))
            } else {
                null
            }
        }.getOrNull()
    }
}
