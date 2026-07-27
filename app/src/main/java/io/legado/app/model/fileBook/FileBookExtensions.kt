package io.legado.app.model.fileBook

import android.net.Uri
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.remote.RemoteBook
import io.legado.app.utils.FileDoc
import java.io.InputStream

/**
 * [FileBook] 的 Android 平台 Uri / FileDoc / RemoteBook 重载扩展 (app 端)。
 *
 * 原 app 端 `object FileBook` 直接以 [Uri] / [FileDoc] / [RemoteBook] 为参数;
 * 下沉到 shared/commonMain 后, [FileBook] (shared) 改用 String (Uri.toString())
 * 与拍平的基本类型参数, 由 [FileBookAccessor] 桥接。本文件提供保留原签名的
 * 重载扩展, 让 app 端调用方零改动 (调用语法与下沉前完全一致)。
 *
 * # 重载清单
 * - [importLocalFile] (Uri) / [importLocalFile][importLocalFileFileDoc] (FileDoc)
 * - [importFromArchive] (Uri, ...)
 * - [saveBookFile] (str, fileName, source) 返回 Uri /
 *   [saveBookFile][saveBookFileStream] (InputStream, fileName) 返回 Uri
 * - [importRemoteBook] (webDav, serverID, RemoteBook, downloadFile)
 *
 * 内部均委托 [FileBookAccessor] (经 [FileBookProviders.get]), 由
 * [FileBookAccessorImpl] 执行原 Android 逻辑, Uri↔String 转换在 accessor 内完成。
 *
 * 模式参考 [BaseFileBookExt] (getBookInputStream / getLastModified 扩展)。
 */

// ---------- importLocalFile ----------

/** 导入本地文件 (Uri 重载, 对应原 `FileBook.importLocalFile(uri: Uri)`)。 */
fun FileBook.importLocalFile(uri: Uri): Book =
    FileBookProviders.get().importLocalFile(uri.toString())

/** 导入本地文件 (FileDoc 重载, 对应原 `FileBook.importLocalFile(fileDoc: FileDoc)`)。 */
fun FileBook.importLocalFile(fileDoc: FileDoc): Book =
    FileBookProviders.get().importLocalFile(fileDoc.uri.toString())

// ---------- importFromArchive ----------

/** 导入压缩包内的书籍 (Uri 重载, 对应原 `FileBook.importFromArchive(archiveFileUri: Uri, ...)`)。 */
fun FileBook.importFromArchive(
    archiveFileUri: Uri,
    saveFileName: String? = null,
    filter: ((String) -> Boolean)? = null
): List<Book> =
    FileBookProviders.get().importFromArchive(archiveFileUri.toString(), saveFileName, filter)

// ---------- saveBookFile ----------

/**
 * 下载并保存在线文件 (返回 Uri 重载, 对应原 `FileBook.saveBookFile(str, fileName, source): Uri`)。
 *
 * accessor 返回 String (uri.toString()), 本扩展 [Uri.parse] 回 Uri, 保持原签名。
 */
suspend fun FileBook.saveBookFile(
    str: String,
    fileName: String,
    source: BaseSource? = null
): Uri = Uri.parse(FileBookProviders.get().saveBookFile(str, fileName, source))

/**
 * 保存输入流到文件 (返回 Uri 重载, 对应原 `FileBook.saveBookFile(inputStream, fileName): Uri`)。
 *
 * accessor 返回 String (uri.toString()), 本扩展 [Uri.parse] 回 Uri, 保持原签名。
 */
fun FileBook.saveBookFile(
    inputStream: InputStream,
    fileName: String
): Uri = Uri.parse(FileBookProviders.get().saveBookFile(inputStream, fileName))

// ---------- importRemoteBook ----------

/**
 * 导入远程书籍 (RemoteBook 重载, 对应原 `FileBook.importRemoteBook(webDav, serverID, remoteBook, downloadFile)`)。
 *
 * 原 RemoteBook 参数在本扩展拍平为 name(filename)/path/size/lastModify 基本类型,
 * 委托 accessor (commonMain FileBook.importRemoteBook 同签名), 行为一致。
 */
suspend fun FileBook.importRemoteBook(
    webDav: WebDav,
    serverID: Long?,
    remoteBook: RemoteBook,
    downloadFile: Boolean = false
): Book = FileBookProviders.get().importRemoteBook(
    webDav, serverID,
    remoteBook.filename, remoteBook.path, remoteBook.size, remoteBook.lastModify,
    downloadFile
)
