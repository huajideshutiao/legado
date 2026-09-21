package io.legado.app.utils

import java.io.File

/**
 * FileDocIo 的纯 JDK 部分下沉 (shared jvmAndAndroidMain)。
 *
 * app 端 [FileDocIo] 的 file-scheme 分支转发到本 object,
 * SAF/content-scheme 分支仍留 app 端 (依赖 Uri/DocumentFile/PFD)。
 *
 * 入参统一使用 path:String (delete 除外, 因为其上游 `fileDoc.asFile()` 已是 File),
 * 与 app 端 file-scheme 分支取 `fileDoc.uri.path!!` 的方式对齐,
 * 复用 [FileUtilsBase] 完成具体实现, 行为与原 FileUtils 转发链路等价。
 */
object FileIoBase {

    /**
     * 写文本文件。对应原 file-scheme 分支:
     * `File(fileDoc.uri.path!!).writeText(text)`
     */
    fun writeText(path: String, text: String) {
        File(path).writeText(text)
    }

    /**
     * 判断路径是否存在。对应原 file-scheme 分支:
     * `FileUtils.exist(fileDoc.uri.path!!)`
     */
    fun exists(path: String): Boolean = FileUtilsBase.exist(path)

    /**
     * 判断 parentPath/subDirs/fileName 是否存在。对应原 file-scheme 分支:
     * `val path = FileUtils.getPath(fileDoc.uri.path!!, *subDirs) + File.separator + fileName`
     * `FileUtils.exist(path)`
     */
    fun exists(parentPath: String, fileName: String, vararg subDirs: String): Boolean {
        val path = FileUtilsBase.getPath(parentPath, *subDirs) + File.separator + fileName
        return FileUtilsBase.exist(path)
    }

    /**
     * 删除文件/目录。对应原 file-scheme 分支:
     * `fileDoc.asFile()?.let { FileUtils.delete(it, true) }`
     *
     * 入参保留 File, 与上游 `fileDoc.asFile()` 返回类型对齐, 避免反复 String↔File 转换。
     */
    fun delete(file: File, deleteRootDir: Boolean = true): Boolean =
        FileUtilsBase.delete(file, deleteRootDir)

    /**
     * 在 parentPath/subDirs 下创建文件 fileName。对应原 file-scheme 分支:
     * `val path = FileUtils.getPath(fileDoc.uri.path!!, *subDirs) + File.separator + fileName`
     * `val tmp = FileUtils.createFileIfNotExist(path)`
     */
    fun createFile(parentPath: String, fileName: String, vararg subDirs: String): File {
        val path = FileUtilsBase.getPath(parentPath, *subDirs) + File.separator + fileName
        return FileUtilsBase.createFileIfNotExist(path)
    }

    /**
     * 在 parentPath/subDirs 下创建文件夹。对应原 file-scheme 分支:
     * `val path = FileUtils.getPath(fileDoc.uri.path!!, *subDirs)`
     * `val tmp = FileUtils.createFolderIfNotExist(path)`
     */
    fun createFolder(parentPath: String, vararg subDirs: String): File {
        val path = FileUtilsBase.getPath(parentPath, *subDirs)
        return FileUtilsBase.createFolderIfNotExist(path)
    }
}
