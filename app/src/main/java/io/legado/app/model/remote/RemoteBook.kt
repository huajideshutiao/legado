package io.legado.app.model.remote

import androidx.annotation.Keep
import io.legado.app.data.appDb
import io.legado.app.lib.webdav.WebDavFile

@Keep
data class RemoteBook(
    val filename: String,
    val path: String,
    val size: Long,
    val lastModify: Long,
    var contentType: String = "folder",
    var isOnBookShelf: Boolean = false,
    val isUpDir: Boolean = false
) {

    val isDir get() = contentType == "folder" && !isUpDir
    val name get() = if (isUpDir) ".." else filename

    constructor(webDavFile: WebDavFile) : this(
        webDavFile.displayName,
        webDavFile.path,
        webDavFile.size,
        webDavFile.lastModify
    ) {
        if (!webDavFile.isDir) {
            contentType = webDavFile.displayName.substringAfterLast(".")
            isOnBookShelf = appDb.bookDao.hasFile(webDavFile.displayName)
        }
    }

}