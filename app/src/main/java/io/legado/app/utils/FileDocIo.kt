package io.legado.app.utils

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.App
import io.legado.app.downloadManager
import io.legado.app.exception.NoStackTraceException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * FileDoc 的平台 I/O 原语。安卓端实现 = ContentResolver/DocumentFile/PFD/DownloadManager。
 * FileDoc 公开 API 委托到这里；纯逻辑(find 递归/checkWrite/工厂解析)留在 FileDocExtensions。
 * KJ3 期本对象整体下沉 expect/actual，故此处集中一切 Android 存储类型渗漏。
 */
internal object FileDocIo {

    private val projection by lazy {
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
    }

    fun fromUri(uri: Uri, isDir: Boolean): FileDoc {
        if (uri.isContentScheme()) {
            val doc = if (isDir) {
                DocumentFile.fromTreeUri(App.instance, uri)!!
            } else if (uri.host == "downloads") {
                val query = DownloadManager.Query()
                query.setFilterById(uri.lastPathSegment!!.toLong())
                downloadManager.query(query).use {
                    if (it.moveToFirst()) {
                        val lUriColum = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        val lUri = it.getString(lUriColum)
                        DocumentFile.fromSingleUri(App.instance, lUri.toUri())!!
                    } else {
                        DocumentFile.fromSingleUri(App.instance, uri)!!
                    }
                }
            } else {
                DocumentFile.fromSingleUri(App.instance, uri)!!
            }
            return FileDoc(doc.name ?: "", isDir, doc.length(), doc.lastModified(), doc.uri)
        }
        val file = File(uri.path!!)
        return FileDoc(file.name, isDir, file.length(), file.lastModified(), uri)
    }

    fun asDocumentFile(fileDoc: FileDoc): DocumentFile? {
        if (!fileDoc.isContentScheme) {
            return null
        }
        return if (fileDoc.isDir) {
            DocumentFile.fromTreeUri(App.instance, fileDoc.uri)
        } else {
            DocumentFile.fromSingleUri(App.instance, fileDoc.uri)
        }
    }

    /**
     * 返回子文件列表,如果不是文件夹则返回null
     */
    fun list(fileDoc: FileDoc, filter: FileDocFilter?): ArrayList<FileDoc>? {
        if (fileDoc.isDir) {
            val uri = fileDoc.uri
            if (uri.isContentScheme()) {
                /**
                 * DocumentFile 的 listFiles() 非常的慢,所以这里直接从数据库查询
                 */
                val childrenUri = DocumentsContract
                    .buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
                val docList = arrayListOf<FileDoc>()
                var cursor: Cursor? = null
                try {
                    cursor = App.instance.contentResolver.query(
                        childrenUri,
                        projection,
                        null,
                        null,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    )
                    cursor?.let {
                        val ici = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nci = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val sci = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        val mci = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val dci = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (cursor.moveToFirst()) {
                            do {
                                val item = FileDoc(
                                    name = cursor.getString(nci),
                                    isDir = cursor.getString(mci) ==
                                            DocumentsContract.Document.MIME_TYPE_DIR,
                                    size = cursor.getLong(sci),
                                    lastModified = cursor.getLong(dci),
                                    uri = DocumentsContract.buildDocumentUriUsingTree(
                                        uri,
                                        cursor.getString(ici)
                                    )
                                )
                                if (filter == null || filter.invoke(item)) {
                                    docList.add(item)
                                }
                            } while (cursor.moveToNext())
                        }
                    }
                } finally {
                    cursor?.close()
                }
                return docList
            } else {
                return File(uri.path!!).listFileDocs(filter)
            }
        }
        return null
    }

    fun createFile(
        fileDoc: FileDoc,
        fileName: String,
        vararg subDirs: String,
        mimeType: String
    ): FileDoc {
        return if (fileDoc.uri.isContentScheme()) {
            val documentFile = asDocumentFile(fileDoc)!!
            val tmp =
                DocumentUtils.createFileIfNotExist(documentFile, fileName, *subDirs, mimeType = mimeType)!!
            FileDoc.fromDocumentFile(tmp)
        } else {
            val tmp = FileIoBase.createFile(fileDoc.uri.path!!, fileName, *subDirs)
            FileDoc.fromFile(tmp)
        }
    }

    fun createFolder(fileDoc: FileDoc, vararg subDirs: String): FileDoc {
        return if (fileDoc.uri.isContentScheme()) {
            val documentFile = asDocumentFile(fileDoc)!!
            val tmp = DocumentUtils.createFolderIfNotExist(documentFile, *subDirs)!!
            FileDoc.fromDocumentFile(tmp)
        } else {
            val tmp = FileIoBase.createFolder(fileDoc.uri.path!!, *subDirs)
            FileDoc.fromFile(tmp)
        }
    }

    fun openInputStream(fileDoc: FileDoc): Result<InputStream> {
        return fileDoc.uri.inputStream(App.instance)
    }

    fun openOutputStream(fileDoc: FileDoc): Result<OutputStream> {
        return fileDoc.uri.outputStream(App.instance)
    }

    fun openReadPfd(fileDoc: FileDoc): Result<ParcelFileDescriptor> {
        return fileDoc.uri.toReadPfd(App.instance)
    }

    fun openWritePfd(fileDoc: FileDoc): Result<ParcelFileDescriptor> {
        return fileDoc.uri.toWritePfd(App.instance)
    }

    fun exists(fileDoc: FileDoc, fileName: String, vararg subDirs: String): Boolean {
        return if (fileDoc.uri.isContentScheme()) {
            DocumentUtils.exists(asDocumentFile(fileDoc)!!, fileName, *subDirs)
        } else {
            FileIoBase.exists(fileDoc.uri.path!!, fileName, *subDirs)
        }
    }

    fun exists(fileDoc: FileDoc): Boolean {
        return if (fileDoc.uri.isContentScheme()) {
            asDocumentFile(fileDoc)!!.exists()
        } else {
            FileIoBase.exists(fileDoc.uri.path!!)
        }
    }

    fun writeText(fileDoc: FileDoc, text: String) {
        if (fileDoc.uri.isContentScheme()) {
            fileDoc.uri.writeText(App.instance, text)
        } else {
            FileIoBase.writeText(fileDoc.uri.path!!, text)
        }
    }

    fun delete(fileDoc: FileDoc) {
        fileDoc.asFile()?.let {
            FileIoBase.delete(it, true)
        }
        asDocumentFile(fileDoc)?.delete()
    }
}

/**
 * DocumentFile 的 listFiles() 非常的慢,尽量不要使用
 */
fun DocumentFile.listFileDocs(filter: FileDocFilter? = null): ArrayList<FileDoc>? {
    return FileDoc.fromDocumentFile(this).list(filter)
}

@Throws(Exception::class)
fun DocumentFile.openInputStream(): InputStream? {
    return App.instance.contentResolver.openInputStream(uri)
}

@Throws(Exception::class)
fun DocumentFile.openOutputStream(): OutputStream? {
    return App.instance.contentResolver.openOutputStream(uri)
}

@Throws(Exception::class)
fun DocumentFile.writeText(context: Context, data: String, charset: Charset = Charsets.UTF_8) {
    uri.writeText(context, data, charset)
}

@Throws(Exception::class)
fun DocumentFile.writeBytes(context: Context, data: ByteArray) {
    uri.writeBytes(context, data)
}

@Throws(Exception::class)
fun DocumentFile.readText(context: Context): String {
    return String(readBytes(context))
}

@Throws(Exception::class)
fun DocumentFile.readBytes(context: Context): ByteArray {
    return context.contentResolver.openInputStream(uri)?.let {
        val len: Int = it.available()
        val buffer = ByteArray(len)
        it.read(buffer)
        it.close()
        return buffer
    } ?: throw NoStackTraceException("打开文件失败\n${uri}")
}

fun DocumentFile.checkWrite(): Boolean {
    var file: DocumentFile? = null
    return try {
        val filename = System.currentTimeMillis().toString()
        file = createFile(FileUtils.getMimeType(filename), filename)
        file?.openOutputStream()?.let { out ->
            out.bufferedWriter().use { it.write(filename) }
            file.openInputStream()?.let { input ->
                input.bufferedReader().use {
                    return it.readText() == filename
                }
            }
        }
        false
    } catch (e: Exception) {
        false
    } finally {
        file?.delete()
    }
}
