package io.legado.app.utils

import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import io.legado.app.constant.AppConst
import io.legado.app.constant.fileNameFormat
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.regex.Pattern

object FileUtils {

    fun createFileIfNotExist(root: File, vararg subDirFiles: String): File =
        FileUtilsBase.createFileIfNotExist(root, *subDirFiles)

    fun createFolderIfNotExist(root: File, vararg subDirs: String): File =
        FileUtilsBase.createFolderIfNotExist(root, *subDirs)

    fun createFolderIfNotExist(filePath: String): File =
        FileUtilsBase.createFolderIfNotExist(filePath)

    @Synchronized
    fun createFileIfNotExist(filePath: String): File =
        FileUtilsBase.createFileIfNotExist(filePath)

    fun createFileWithReplace(filePath: String): File =
        FileUtilsBase.createFileWithReplace(filePath)

    fun getPath(rootPath: String, vararg subDirFiles: String): String =
        FileUtilsBase.getPath(rootPath, *subDirFiles)

    fun getPath(root: File, vararg subDirFiles: String): String =
        FileUtilsBase.getPath(root, *subDirFiles)

    /**
     * 将目录分隔符统一为平台默认的分隔符，并为目录结尾添加分隔符
     */
    fun separator(path: String): String = FileUtilsBase.separator(path)

    fun closeSilently(c: Closeable?) {
        FileUtilsBase.closeSilently(c)
    }

    /**
     * 列出指定目录下的所有子目录
     */
    @JvmOverloads
    fun listDirs(
        startDirPath: String,
        excludeDirs: Array<String>? = null,
        @FileUtilsBase.SortType sortType: Int = FileUtilsBase.BY_NAME_ASC
    ): Array<File> = FileUtilsBase.listDirs(startDirPath, excludeDirs, sortType)

    /**
     * 列出指定目录下的所有子目录及所有文件
     */
    @JvmOverloads
    fun listDirsAndFiles(
        startDirPath: String,
        allowExtensions: Array<String>? = null
    ): Array<File>? {
        val files: Array<File>? = if (allowExtensions == null) {
            listFiles(startDirPath)
        } else {
            listFiles(startDirPath, allowExtensions)
        }
        val dirs = listDirs(startDirPath)
        if (files == null) {
            return null
        }
        return dirs + files
    }

    /**
     * 列出指定目录下的所有文件
     */
    @JvmOverloads
    fun listFiles(
        startDirPath: String,
        filterPattern: Pattern? = null,
        @FileUtilsBase.SortType sortType: Int = FileUtilsBase.BY_NAME_ASC
    ): Array<File> = FileUtilsBase.listFiles(startDirPath, filterPattern, sortType)

    /**
     * 列出指定目录下的所有文件
     */
    fun listFiles(startDirPath: String, allowExtensions: Array<String>?): Array<File>? =
        FileUtilsBase.listFiles(startDirPath, allowExtensions)

    /**
     * 列出指定目录下的所有文件
     */
    fun listFiles(startDirPath: String, allowExtension: String?): Array<File>? =
        FileUtilsBase.listFiles(startDirPath, allowExtension)

    /**
     * 判断文件或目录是否存在
     */
    fun exist(path: String): Boolean = FileUtilsBase.exist(path)

    /**
     * 删除文件或目录
     */
    @JvmOverloads
    fun delete(file: File, deleteRootDir: Boolean = false): Boolean =
        FileUtilsBase.delete(file, deleteRootDir)

    /**
     * 删除文件或目录
     */
    @JvmOverloads
    fun delete(path: String, deleteRootDir: Boolean = true): Boolean =
        FileUtilsBase.delete(path, deleteRootDir)

    /**
     * 复制文件为另一个文件，或复制某目录下的所有文件及目录到另一个目录下
     */
    fun copy(src: String, tar: String): Boolean = FileUtilsBase.copy(src, tar)

    /**
     * 复制文件或目录
     */
    fun copy(src: File, tar: File): Boolean = FileUtilsBase.copy(src, tar)

    /**
     * 移动文件或目录
     */
    fun move(src: String, tar: String): Boolean = FileUtilsBase.move(src, tar)

    /**
     * 移动文件或目录
     */
    fun move(src: File, tar: File): Boolean = FileUtilsBase.move(src, tar)

    /**
     * 文件重命名
     */
    fun rename(oldPath: String, newPath: String): Boolean =
        FileUtilsBase.rename(oldPath, newPath)

    /**
     * 文件重命名
     */
    fun rename(src: File, tar: File): Boolean = FileUtilsBase.rename(src, tar)

    /**
     * 读取文本文件, 失败将返回空串
     */
    @JvmOverloads
    fun readText(filepath: String, charset: String = "utf-8"): String =
        FileUtilsBase.readText(filepath, charset)

    /**
     * 读取文件内容, 失败将返回空串
     */
    fun readBytes(filepath: String): ByteArray? = FileUtilsBase.readBytes(filepath)

    /**
     * 保存文本内容
     */
    @JvmOverloads
    fun writeText(filepath: String, content: String, charset: String = "utf-8"): Boolean =
        FileUtilsBase.writeText(filepath, content, charset)

    /**
     * 保存文件内容
     */
    fun writeBytes(filepath: String, data: ByteArray): Boolean =
        FileUtilsBase.writeBytes(filepath, data)

    /**
     * 保存文件内容
     */
    fun writeInputStream(filepath: String, data: InputStream): Boolean =
        FileUtilsBase.writeInputStream(filepath, data)

    /**
     * 保存文件内容
     */
    fun writeInputStream(file: File, data: InputStream): Boolean =
        FileUtilsBase.writeInputStream(file, data)

    /**
     * 追加文本内容
     */
    fun appendText(path: String, content: String): Boolean =
        FileUtilsBase.appendText(path, content)

    /**
     * 获取文件大小
     */
    fun getLength(path: String): Long = FileUtilsBase.getLength(path)

    /**
     * 获取文件或网址的名称（包括后缀）
     */
    fun getName(path: String?): String = FileUtilsBase.getName(path)

    /**
     * 获取文件名（不包括扩展名）
     */
    fun getNameExcludeExtension(path: String): String =
        FileUtilsBase.getNameExcludeExtension(path)

    /**
     * 获取格式化后的文件大小
     */
    fun getSize(path: String): String {
        val fileSize = getLength(path)
        return ConvertUtils.formatFileSize(fileSize)
    }

    /**
     * 获取文件后缀,不包括“.”
     */
    fun getExtension(pathOrUrl: String): String = FileUtilsBase.getExtension(pathOrUrl)

    /**
     * 通过文件头魔数获取图片后缀名（含“.”）
     */
    fun getImageExtension(file: File): String = FileUtilsBase.getImageExtension(file)

    /**
     * 获取文件的MIME类型
     */
    fun getMimeType(pathOrUrl: String): String {
        val ext = getExtension(pathOrUrl)
        val map = MimeTypeMap.getSingleton()
        return map.getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /**
     * 获取格式化后的文件/目录创建或最后修改时间
     */
    @JvmOverloads
    fun getDateTime(path: String, format: String = "yyyy年MM月dd日HH:mm"): String =
        FileUtilsBase.getDateTime(path, format)

    /**
     * 获取格式化后的文件/目录创建或最后修改时间
     */
    fun getDateTime(file: File, format: String): String =
        FileUtilsBase.getDateTime(file, format)

    /**
     * 比较两个文件的最后修改时间
     */
    fun compareLastModified(path1: String, path2: String): Int =
        FileUtilsBase.compareLastModified(path1, path2)

    /**
     * 创建多级别的目录
     */
    fun makeDirs(path: String): Boolean = FileUtilsBase.makeDirs(path)

    /**
     * 创建多级别的目录
     */
    fun makeDirs(file: File): Boolean = FileUtilsBase.makeDirs(file)

    /**
     * 将图片URL或Base64数据保存到指定目录
     * @param imageData 图片URL或Base64数据
     * @param dirUri 目标目录Uri (content:// 或 file://)
     * @param fileName 保存的文件名，为空时自动生成时间戳文件名
     * @return 保存成功返回true
     */
    fun saveImage(imageData: String, dirUri: Uri, fileName: String? = null): Boolean {
        val byteArray = urlOrBase64ToBytes(imageData) ?: return false
        val name = fileName ?: run {
            val ext = getExtension(imageData).let {
                if (it.length <= 5 && it.matches(Regex("[a-zA-Z0-9]+"))) ".$it" else ".jpg"
            }
            "${AppConst.fileNameFormat.format(System.currentTimeMillis())}$ext"
        }
        val fileDoc = FileDoc.fromDir(dirUri)
        val picFile = fileDoc.createFileIfNotExist(name)
        picFile.openOutputStream().getOrThrow().use {
            it.write(byteArray)
        }
        return true
    }

    /**
     * 将本地图片文件保存到指定目录
     * @param imageFile 本地图片文件
     * @param dirUri 目标目录URI（支持content://和file://）
     * @param fileName 保存的文件名，默认使用原文件名
     * @return 是否保存成功
     */
    fun saveImage(imageFile: File, dirUri: Uri, fileName: String? = null): Boolean {
        val ext = getImageExtension(imageFile)
        val name =
            fileName ?: "${AppConst.fileNameFormat.format(System.currentTimeMillis())}$ext"
        val fileDoc = FileDoc.fromDir(dirUri)
        val picFile = fileDoc.createFileIfNotExist(name)
        FileInputStream(imageFile).use { input ->
            picFile.openOutputStream().getOrThrow().use { output ->
                input.copyTo(output)
            }
        }
        return true
    }

    fun saveImage(inputStream: InputStream, dirUri: Uri, ext: String = ".jpg"): Boolean {
        val name = "${AppConst.fileNameFormat.format(System.currentTimeMillis())}$ext"
        val fileDoc = FileDoc.fromDir(dirUri)
        val picFile = fileDoc.createFileIfNotExist(name)
        inputStream.use { input ->
            picFile.openOutputStream().getOrThrow().use { output ->
                input.copyTo(output)
            }
        }
        return true
    }

    /**
     * 将图片URL或Base64数据转换为ByteArray
     * @param data 图片URL或Base64数据
     * @return 图片的ByteArray，失败返回null
     */
    fun urlOrBase64ToBytes(data: String): ByteArray? {
        return if (URLUtil.isValidUrl(data)) {
            try {
                val request = okhttp3.Request.Builder().url(data).build()
                io.legado.app.help.http.okHttpClient.newCall(request).execute().body.bytes()
            } catch (e: Exception) {
                null
            }
        } else {
            try {
                Base64.decode(data.split(",").toTypedArray()[1], Base64.DEFAULT)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 将数据导出到指定目录（不带上传）
     * @param dirUri 目标目录Uri (content:// 或 file://)
     * @param fileName 保存的文件名
     * @param data 数据，支持 File/ByteArray/String/其他对象(自动JSON序列化)
     * @return 保存后的文件Uri
     */
    fun exportFile(dirUri: Uri, fileName: String, data: Any): Uri {
        val bytes = when (data) {
            is File -> data.readBytes()
            is ByteArray -> data
            is String -> data.toByteArray()
            // GSON.toJson(data) 反射序列化 Any → toJsonElement().toString() 处理任意类型
            else -> data.toJsonElement().toString().toByteArray()
        }
        return if (dirUri.isContentScheme()) {
            val dirDoc = FileDoc.fromDir(dirUri)
            dirDoc.find(fileName)?.delete()
            val newDoc = dirDoc.createFileIfNotExist(fileName)
            newDoc.openOutputStream().getOrThrow().use {
                it.write(bytes)
            }
            newDoc.uri
        } else {
            val file = File(dirUri.path ?: dirUri.toString())
            val newFile = createFileIfNotExist(file, fileName)
            newFile.writeBytes(bytes)
            Uri.fromFile(newFile)
        }
    }
}
