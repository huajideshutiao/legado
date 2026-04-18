package io.legado.app.ui.book.import.local

import io.legado.app.data.appDb
import io.legado.app.utils.FileDoc

data class ImportBook(
    val file: FileDoc,
    val isUpDir: Boolean = false,
    val isFileManageMode: Boolean = false,
    var isOnBookShelf: Boolean = if (isFileManageMode || isUpDir || file.isDir) false else appDb.bookDao.hasFile(
        file.name
    )
) {
    val name get() = if (isUpDir) ".." else file.name
    val isDir get() = file.isDir
    val size get() = file.size
    val lastModified get() = file.lastModified
}
