package io.legado.app.utils

import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.App
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.utils.compress.LibArchiveUtils
import java.io.File

/**
 * 自动判断压缩文件后缀 然后再调用具体的实现
 *
 * # 下沉说明
 * 保持 app 端不下沉: Android SAF 专属 (Uri/DocumentFile/FileDoc/ParcelFileDescriptor) +
 * 委托 [LibArchiveUtils] (Android native libarchive)。跨平台解压能力已由
 * shared/commonMain 的 [io.legado.app.help.archive.ArchiveProvider] 接口覆盖——Android 端
 * [io.legado.app.help.archive.AndroidArchiveProvider] 在 App.onCreate 经
 * ArchiveProviders.register 注入, 内部即委托本类, 供 JsExtensions 压缩方法跨端调用;
 * 本类同时服务 app 端本地场景 (导入书籍/本地书籍解压/文件关联), 不在接口职责边界内。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
object ArchiveUtils {

    const val TEMP_FOLDER_NAME = "ArchiveTemp"

    // 临时目录 下次启动自动删除
    val TEMP_PATH: String by lazy {
        App.instance.externalCache.getFile(TEMP_FOLDER_NAME).createFolderReplace().absolutePath
    }

    fun deCompress(
        archiveUri: Uri,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(FileDoc.fromUri(archiveUri, false), path, filter)
    }

    fun deCompress(
        archivePath: String,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(archivePath.toUri(), path, filter)
    }

    fun deCompress(
        archiveFile: File,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(FileDoc.fromFile(archiveFile), path, filter)
    }

    fun deCompress(
        archiveDoc: DocumentFile,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(FileDoc.fromDocumentFile(archiveDoc), path, filter)
    }

    fun deCompress(
        archiveFileDoc: FileDoc,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        if (archiveFileDoc.isDir) throw IllegalArgumentException("Unexpected Folder input")
        checkArchive(archiveFileDoc.name)
        val workPathFileDoc = getCacheFolderFileDoc(archiveFileDoc, path)
        val workPath = workPathFileDoc.toString()

        return archiveFileDoc.openReadPfd().getOrThrow().use {
            LibArchiveUtils.unArchive(it, File(workPath), filter)
        }
    }

    /* 遍历目录获取文件名 */
    fun getArchiveFilesName(fileUri: Uri, filter: ((String) -> Boolean)? = null): List<String> =
        getArchiveFilesName(FileDoc.fromUri(fileUri, false), filter)


    fun getArchiveFilesName(
        fileDoc: FileDoc,
        filter: ((String) -> Boolean)? = null
    ): List<String> {
        checkArchive(fileDoc.name)

        return fileDoc.openReadPfd().getOrThrow().use {
            try {
                LibArchiveUtils.getFilesName(it, filter)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun isArchive(name: String): Boolean {
        return archiveFileRegex.matches(name)
    }

    private fun checkArchive(name: String) {
        if (!isArchive(name))
            throw IllegalArgumentException("Unexpected file suffix")
    }

    private fun getCacheFolderFileDoc(
        archiveFileDoc: FileDoc,
        workPath: String
    ): FileDoc {
        // 使用 URI 的 MD5 确保唯一性，避免不同路径下的同名文件冲突
        val folderName = MD5Utils.md5Encode16(archiveFileDoc.uri.toString())
        return FileDoc.fromUri(workPath.toUri(), true)
            .createFolderIfNotExist(folderName)
    }
}
