package io.legado.app.ui.book.import.local

import io.legado.app.data.appDb
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.utils.FileDoc
import kotlinx.coroutines.runBlocking

/**
 * 导入本地书籍条目模型。
 *
 * 实现 shared 端 [ImportFileItem] 接口供 [ImportBookScreen] 泛型复用:
 * - [tag] 取文件名后缀 (对照原 ImportBookScreen 调用点 `item.name.substringAfterLast(".")`)
 * - [itemKey] 取 `file.toString()` (对照原 items() key 参数 `it.file.toString()`)
 * - [lastModified] 桥接 `file.lastModified` (RemoteBook 用 lastModify, 此处接口统一)
 */
data class ImportBook(
    val file: FileDoc,
    override val isUpDir: Boolean = false,
    val isFileManageMode: Boolean = false,
    override var isOnBookShelf: Boolean = if (isFileManageMode || isUpDir || file.isDir) false else runBlocking {
        appDb.bookDao.hasFile(file.name)
    }
) : ImportFileItem {
    override val name get() = if (isUpDir) ".." else file.name
    override val isDir get() = file.isDir
    override val size get() = file.size
    override val lastModified get() = file.lastModified
    override val tag get() = name.substringAfterLast(".")
    override val itemKey get() = file.toString()
}
