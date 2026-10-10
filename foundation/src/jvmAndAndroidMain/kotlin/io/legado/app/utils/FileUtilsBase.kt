package io.legado.app.utils

import androidx.annotation.IntDef
import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.printStackTraceOnDebug
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileFilter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import java.util.regex.Pattern

/**
 * FileUtils 的纯 JDK 部分下沉 (shared jvmAndAndroidMain)。
 *
 * app 端 [FileUtils] 的对应函数转发到本 object, 保持调用方 import 路径不变。
 * shared 内部 (如 FileDocIo androidMain actual) 直接调用本 object。
 */
object FileUtilsBase {

    fun createFileIfNotExist(root: File, vararg subDirFiles: String): File {
        val filePath = getPath(root, *subDirFiles)
        return createFileIfNotExist(filePath)
    }

    fun createFolderIfNotExist(root: File, vararg subDirs: String): File {
        val filePath = getPath(root, *subDirs)
        return createFolderIfNotExist(filePath)
    }

    fun createFolderIfNotExist(filePath: String): File {
        val file = File(filePath)
        //如果文件夹不存在，就创建它
        if (!file.exists()) {
            file.mkdirs()
        }
        return file
    }

    fun createFileIfNotExist(filePath: String): File {
        val file = File(filePath)
        try {
            if (!file.exists()) {
                //创建父类文件夹
                file.parent?.let {
                    createFolderIfNotExist(it)
                }
                //创建文件
                file.createNewFile()
            }
        } catch (e: IOException) {
            e.printStackTraceOnDebug()
        }
        return file
    }

    fun getPath(rootPath: String, vararg subDirFiles: String): String {
        val path = StringBuilder(rootPath)
        subDirFiles.forEach {
            if (it.isNotEmpty()) {
                if (!path.endsWith(File.separator)) {
                    path.append(File.separator)
                }
                path.append(it)
            }
        }
        return path.toString()
    }

    fun getPath(root: File, vararg subDirFiles: String): String {
        val path = StringBuilder(root.absolutePath)
        subDirFiles.forEach {
            if (it.isNotEmpty()) {
                path.append(File.separator).append(it)
            }
        }
        return path.toString()
    }

    /**
     * 判断文件或目录是否存在
     */
    fun exist(path: String): Boolean {
        val file = File(path)
        return file.exists()
    }

    /**
     * 删除文件或目录
     */
    @JvmOverloads
    fun delete(file: File, deleteRootDir: Boolean = false): Boolean {
        var result = false
        if (file.isFile) {
            //是文件
            result = deleteResolveEBUSY(file)
        } else {
            //是目录
            val files = file.listFiles() ?: return false
            if (files.isEmpty()) {
                result = deleteRootDir && deleteResolveEBUSY(file)
            } else {
                for (f in files) {
                    delete(f, deleteRootDir)
                    result = deleteResolveEBUSY(f)
                }
            }
            if (deleteRootDir) {
                result = deleteResolveEBUSY(file)
            }
        }
        return result
    }

    /**
     * bug: open failed: EBUSY (Device or resource busy)
     * fix: http://stackoverflow.com/questions/11539657/open-failed-ebusy-device-or-resource-busy
     */
    private fun deleteResolveEBUSY(file: File): Boolean {
        // Before you delete a Directory or File: rename it!
        val to = File(file.absolutePath + System.currentTimeMillis())

        file.renameTo(to)
        return to.delete()
    }

    /**
     * 删除文件或目录
     */
    @JvmOverloads
    fun delete(path: String, deleteRootDir: Boolean = true): Boolean {
        val file = File(path)

        return if (file.exists()) {
            delete(file, deleteRootDir)
        } else false
    }

    fun createFileWithReplace(filePath: String): File {
        val file = File(filePath)
        if (!file.exists()) {
            //创建父类文件夹
            file.parent?.let {
                createFolderIfNotExist(it)
            }
            //创建文件
            file.createNewFile()
        } else {
            file.delete()
            file.createNewFile()
        }
        return file
    }

    /**
     * 将目录分隔符统一为平台默认的分隔符，并为目录结尾添加分隔符
     */
    fun separator(path: String): String {
        var path1 = path
        val separator = File.separator
        path1 = path1.replace("\\", separator)
        if (!path1.endsWith(separator)) {
            path1 += separator
        }
        return path1
    }

    fun closeSilently(c: Closeable?) {
        if (c == null) {
            return
        }
        try {
            c.close()
        } catch (ignored: IOException) {
        }

    }

    /**
     * 复制文件为另一个文件，或复制某目录下的所有文件及目录到另一个目录下
     */
    fun copy(src: String, tar: String): Boolean {
        val srcFile = File(src)
        return srcFile.exists() && copy(srcFile, File(tar))
    }

    /**
     * 复制文件或目录
     */
    fun copy(src: File, tar: File): Boolean {
        try {
            if (src.isFile) {
                val inputStream = FileInputStream(src)
                val outputStream = FileOutputStream(tar)
                inputStream.use {
                    outputStream.use {
                        inputStream.copyTo(outputStream)
                        outputStream.flush()
                    }
                }
            } else if (src.isDirectory) {
                tar.mkdirs()
                src.listFiles()?.forEach { file ->
                    copy(file.absoluteFile, File(tar.absoluteFile, file.name))
                }
            }
            return true
        } catch (e: Exception) {
            return false
        }

    }

    /**
     * 移动文件或目录
     */
    fun move(src: String, tar: String): Boolean {
        return move(File(src), File(tar))
    }

    /**
     * 移动文件或目录
     */
    fun move(src: File, tar: File): Boolean {
        return rename(src, tar)
    }

    /**
     * 文件重命名
     */
    fun rename(oldPath: String, newPath: String): Boolean {
        return rename(File(oldPath), File(newPath))
    }

    /**
     * 文件重命名
     */
    fun rename(src: File, tar: File): Boolean {
        return src.renameTo(tar)
    }

    /**
     * 原子替换时原文件让位用的后缀: 目标名让给新文件, 原件原样留在这份备份里。
     * 替换被打断在"让位与重试改名之间"时它会残留在目标目录, [deleteWithReplaceBackup] 按这个名字清掉。
     */
    const val REPLACE_BACKUP_SUFFIX = ".bak"

    /**
     * 用 [src] 整份替换 [dst], 任一步失败都保持 [dst] 已有的内容可用。
     *
     * 流程: 直接改名 (同卷上是纯元数据操作, 安卓/Linux/macOS 原子覆盖目标) → 改名被拒 (Windows
     * 上目标已存在时改名不成功) 时原文件退到 [REPLACE_BACKUP_SUFFIX] 备份名让出目标名后重试 →
     * 重试成功才清掉备份, 重试失败把原文件改回原位。全程不删原文件。
     *
     * 进来时发现有上次替换中断留下的备份: 目标还在 (备份已被新件取代) 就清掉; 目标不在就先留着,
     * 这次替换成功后才清 (这次失败时它是原文件仅存的一份)。
     *
     * [src] 与 [dst] 必须同卷 (调用方的临时文件都落在目标目录或同一应用数据分区内): 跨卷只剩
     * 整份复制一条路, 复制中断会留下半截目标。
     *
     * [src] 的内容校验由调用方在调用前完成 —— 改名不复制字节, 校验过的临时文件原样成为目标。
     *
     * 失败抛 [IOException] (消息给出失败环节与原文件去向), [src] 保持原样由调用方清理。
     */
    fun replaceFileAtomically(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        val backup = File(dst.parentFile, dst.name + REPLACE_BACKUP_SUFFIX)
        if (backup.isFile && dst.exists()) {
            backup.delete()
        }
        if (!src.renameTo(dst)) {
            if (!dst.exists()) {
                throw IOException("替换落盘失败: ${dst.path}")
            }
            if (!dst.renameTo(backup)) {
                throw IOException("替换落盘失败, 原文件被占用: ${dst.path}")
            }
            if (!src.renameTo(dst)) {
                if (!backup.renameTo(dst)) {
                    throw IOException("替换落盘失败, 原文件在 ${backup.path}")
                }
                throw IOException("替换落盘失败, 原文件未动: ${dst.path}")
            }
        }
        backup.delete()
    }

    /**
     * 删除 [target] 及其原子替换备份件。
     *
     * [REPLACE_BACKUP_SUFFIX] 是 [replaceFileAtomically] 的中间件: 替换被强杀在"让位与重试改名
     * 之间"会残留, 同路径的下一次替换才会清掉它。删除替换目标的地方走本方法, 该件才随目标消失。
     *
     * @return [target] 本身是否删除成功 (备份件是否清掉不影响返回值)
     */
    fun deleteWithReplaceBackup(target: File): Boolean {
        val deleted = target.delete()
        File(target.parentFile, target.name + REPLACE_BACKUP_SUFFIX).delete()
        return deleted
    }

    /**
     * 读取文本文件, 失败将返回空串
     */
    @JvmOverloads
    fun readText(filepath: String, charset: String = "utf-8"): String {
        try {
            val data = readBytes(filepath)
            if (data != null) {
                return String(data, Charset.forName(charset)).trim()
            }
        } catch (ignored: UnsupportedEncodingException) {
        }

        return ""
    }

    /**
     * 读取文件内容, 失败将返回空串
     */
    fun readBytes(filepath: String): ByteArray? {
        var fis: FileInputStream? = null
        try {
            fis = FileInputStream(filepath)
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val len = fis.read(buffer, 0, buffer.size)
                if (len == -1) {
                    break
                } else {
                    outputStream.write(buffer, 0, len)
                }
            }
            val data = outputStream.toByteArray()
            outputStream.close()
            return data
        } catch (e: IOException) {
            return null
        } finally {
            closeSilently(fis)
        }
    }

    /**
     * 保存文本内容
     */
    @JvmOverloads
    fun writeText(filepath: String, content: String, charset: String = "utf-8"): Boolean {
        return try {
            writeBytes(filepath, content.toByteArray(charset(charset)))
        } catch (e: UnsupportedEncodingException) {
            false
        }

    }

    /**
     * 保存文件内容
     */
    fun writeBytes(filepath: String, data: ByteArray): Boolean {
        val file = File(filepath)
        var fos: FileOutputStream? = null
        return try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            fos = FileOutputStream(filepath)
            fos.write(data)
            true
        } catch (e: IOException) {
            false
        } finally {
            closeSilently(fos)
        }
    }

    /**
     * 保存文件内容
     */
    fun writeInputStream(filepath: String, data: InputStream): Boolean {
        val file = File(filepath)
        return writeInputStream(file, data)
    }

    /**
     * 保存文件内容
     */
    fun writeInputStream(file: File, data: InputStream): Boolean {
        return try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            data.use {
                FileOutputStream(file).use { fos ->
                    data.copyTo(fos)
                    fos.flush()
                }
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * 追加文本内容
     */
    fun appendText(path: String, content: String): Boolean {
        val file = File(path)
        var writer: FileWriter? = null
        return try {
            if (!file.exists()) {
                file.createNewFile()
            }
            writer = FileWriter(file, true)
            writer.write(content)
            true
        } catch (e: IOException) {
            false
        } finally {
            closeSilently(writer)
        }
    }

    /**
     * 获取文件大小
     */
    fun getLength(path: String): Long {
        val file = File(path)
        return if (!file.isFile || !file.exists()) {
            0
        } else file.length()
    }

    /**
     * 获取文件或网址的名称（包括后缀）
     */
    fun getName(path: String?): String {
        if (path == null) {
            return ""
        }
        val pos = path.lastIndexOf(File.separator)
        return if (0 <= pos) {
            path.substring(pos + 1)
        } else {
            path
        }
    }

    /**
     * 获取文件名（不包括扩展名）
     */
    fun getNameExcludeExtension(path: String): String {
        return try {
            var fileName = File(path).name
            val lastIndexOf = fileName.lastIndexOf(".")
            if (lastIndexOf != -1) {
                fileName = fileName.take(lastIndexOf)
            }
            fileName
        } catch (e: Exception) {
            ""
        }

    }

    /**
     * 获取文件后缀,不包括“.”
     */
    fun getExtension(pathOrUrl: String): String {
        val dotPos = pathOrUrl.lastIndexOf('.')
        return if (0 <= dotPos) {
            pathOrUrl.substring(dotPos + 1)
        } else {
            "ext"
        }
    }

    /**
     * 通过文件头魔数获取图片后缀名（含“.”）
     */
    fun getImageExtension(file: File): String {
        return FileInputStream(file).use { input ->
            val header = ByteArray(12)
            val read = input.read(header)
            if (read < 3) return ".jpg"
            when {
                header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() -> ".gif"
                header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() -> ".png"
                header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> ".jpg"
                read >= 12 && header[0] == 0x52.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() && header[3] == 0x46.toByte() && header[8] == 0x57.toByte() && header[9] == 0x45.toByte() && header[10] == 0x42.toByte() && header[11] == 0x50.toByte() -> ".webp"
                header[0] == 0x42.toByte() && header[1] == 0x4D.toByte() -> ".bmp"
                else -> ".jpg"
            }
        }
    }

    /**
     * 按文件头魔数校正图片扩展名: 已匹配则不动, 否则 renameTo 追加真实扩展名
     * (判据与 app 端 FileUtils.getImageExtension 同一条)。
     *
     * 改名失败时 [dest] 内容不变、原名保留, 返回 [dest] 本身并留一条日志: 返回的路径始终指向本次
     * 写入的文件。
     *
     * @return 最终文件 (未改名或改名失败时即原文件)
     */
    fun fixImageExtension(dest: File): File {
        val ext = getImageExtension(dest)
        if (dest.name.endsWith(ext, ignoreCase = true)) return dest
        val renamed = File(dest.parentFile, dest.nameWithoutExtension + ext)
        if (!dest.renameTo(renamed)) {
            AppLog.put("图片扩展名校正失败, 保留原名: ${dest.path}")
            return dest
        }
        return renamed
    }

    /**
     * 获取格式化后的文件/目录创建或最后修改时间
     */
    @JvmOverloads
    fun getDateTime(path: String, format: String = "yyyy年MM月dd日HH:mm"): String {
        val file = File(path)
        return getDateTime(file, format)
    }

    /**
     * 获取格式化后的文件/目录创建或最后修改时间
     */
    fun getDateTime(file: File, format: String): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = file.lastModified()
        return SimpleDateFormat(format, Locale.PRC).format(cal.time)
    }

    /**
     * 比较两个文件的最后修改时间
     */
    fun compareLastModified(path1: String, path2: String): Int {
        val stamp1 = File(path1).lastModified()
        val stamp2 = File(path2).lastModified()
        return when {
            stamp1 > stamp2 -> 1
            stamp1 < stamp2 -> -1
            else -> 0
        }
    }

    /**
     * 创建多级别的目录
     */
    fun makeDirs(path: String): Boolean {
        return makeDirs(File(path))
    }

    /**
     * 创建多级别的目录
     */
    fun makeDirs(file: File): Boolean {
        return file.mkdirs()
    }

    const val BY_NAME_ASC = 0
    const val BY_NAME_DESC = 1
    const val BY_TIME_ASC = 2
    const val BY_TIME_DESC = 3
    const val BY_SIZE_ASC = 4
    const val BY_SIZE_DESC = 5
    const val BY_EXTENSION_ASC = 6
    const val BY_EXTENSION_DESC = 7

    @IntDef(value = [BY_NAME_ASC, BY_NAME_DESC, BY_TIME_ASC, BY_TIME_DESC, BY_SIZE_ASC, BY_SIZE_DESC, BY_EXTENSION_ASC, BY_EXTENSION_DESC])
    @Retention(AnnotationRetention.SOURCE)
    annotation class SortType

    /**
     * 列出指定目录下的所有子目录
     */
    @JvmOverloads
    fun listDirs(
        startDirPath: String,
        excludeDirs: Array<String>? = null, @SortType sortType: Int = BY_NAME_ASC
    ): Array<File> {
        var excludeDirs1 = excludeDirs
        val dirList = ArrayList<File>()
        val startDir = File(startDirPath)
        if (!startDir.isDirectory) {
            return arrayOf()
        }
        val dirs = startDir.listFiles(FileFilter { f ->
            if (f == null) {
                return@FileFilter false
            }
            f.isDirectory
        }) ?: return arrayOf()
        if (excludeDirs1 == null) {
            excludeDirs1 = arrayOf()
        }
        for (dir in dirs) {
            val file = dir.absoluteFile
            if (!excludeDirs1.contentDeepToString().contains(file.name)) {
                dirList.add(file)
            }
        }
        when (sortType) {
            BY_NAME_ASC -> Collections.sort(dirList, SortByName())
            BY_NAME_DESC -> {
                Collections.sort(dirList, SortByName())
                dirList.reverse()
            }
            BY_TIME_ASC -> Collections.sort(dirList, SortByTime())
            BY_TIME_DESC -> {
                Collections.sort(dirList, SortByTime())
                dirList.reverse()
            }
            BY_SIZE_ASC -> Collections.sort(dirList, SortBySize())
            BY_SIZE_DESC -> {
                Collections.sort(dirList, SortBySize())
                dirList.reverse()
            }
            BY_EXTENSION_ASC -> Collections.sort(dirList, SortByExtension())
            BY_EXTENSION_DESC -> {
                Collections.sort(dirList, SortByExtension())
                dirList.reverse()
            }
        }
        return dirList.toTypedArray()
    }

    /**
     * 列出指定目录下的所有文件
     */
    @JvmOverloads
    fun listFiles(
        startDirPath: String,
        filterPattern: Pattern? = null, @SortType sortType: Int = BY_NAME_ASC
    ): Array<File> {
        val fileList = ArrayList<File>()
        val f = File(startDirPath)
        if (!f.isDirectory) {
            return arrayOf()
        }
        val files = f.listFiles(FileFilter { file ->
            if (file == null) {
                return@FileFilter false
            }
            if (file.isDirectory) {
                return@FileFilter false
            }

            filterPattern?.matcher(file.name)?.find() ?: true
        })
            ?: return arrayOf()
        for (file in files) {
            fileList.add(file.absoluteFile)
        }
        when (sortType) {
            BY_NAME_ASC -> Collections.sort(fileList, SortByName())
            BY_NAME_DESC -> {
                Collections.sort(fileList, SortByName())
                fileList.reverse()
            }
            BY_TIME_ASC -> Collections.sort(fileList, SortByTime())
            BY_TIME_DESC -> {
                Collections.sort(fileList, SortByTime())
                fileList.reverse()
            }
            BY_SIZE_ASC -> Collections.sort(fileList, SortBySize())
            BY_SIZE_DESC -> {
                Collections.sort(fileList, SortBySize())
                fileList.reverse()
            }
            BY_EXTENSION_ASC -> Collections.sort(fileList, SortByExtension())
            BY_EXTENSION_DESC -> {
                Collections.sort(fileList, SortByExtension())
                fileList.reverse()
            }
        }
        return fileList.toTypedArray()
    }

    /**
     * 列出指定目录下的所有文件
     */
    fun listFiles(startDirPath: String, allowExtensions: Array<String>?): Array<File>? {
        val file = File(startDirPath)
        return file.listFiles { _, name ->
            //返回当前目录所有以某些扩展名结尾的文件
            val extension = getExtension(name)
            allowExtensions?.contentDeepToString()?.contains(extension) == true
                    || allowExtensions == null
        }
    }

    /**
     * 列出指定目录下的所有文件
     */
    fun listFiles(startDirPath: String, allowExtension: String?): Array<File>? {
        return if (allowExtension == null)
            listFiles(startDirPath, allowExtension = null)
        else
            listFiles(startDirPath, arrayOf(allowExtension))
    }

    class SortByExtension : Comparator<File> {

        override fun compare(f1: File?, f2: File?): Int {
            return if (f1 == null || f2 == null) {
                if (f1 == null) -1 else 1
            } else {
                if (f1.isDirectory && f2.isFile) {
                    -1
                } else if (f1.isFile && f2.isDirectory) {
                    1
                } else {
                    f1.name.compareTo(f2.name, ignoreCase = true)
                }
            }
        }

    }

    class SortByName : Comparator<File> {
        private var caseSensitive: Boolean = false

        constructor(caseSensitive: Boolean) {
            this.caseSensitive = caseSensitive
        }

        constructor() {
            this.caseSensitive = false
        }

        override fun compare(f1: File?, f2: File?): Int {
            if (f1 == null || f2 == null) {
                return if (f1 == null) {
                    -1
                } else {
                    1
                }
            } else {
                return if (f1.isDirectory && f2.isFile) {
                    -1
                } else if (f1.isFile && f2.isDirectory) {
                    1
                } else {
                    val s1 = f1.name
                    val s2 = f2.name
                    if (caseSensitive) {
                        // 复用已下沉的 String.cnCompare (commonMain expect/actual, 同包免 import)
                        s1.cnCompare(s2)
                    } else {
                        s1.compareTo(s2, ignoreCase = true)
                    }
                }
            }
        }

    }

    class SortBySize : Comparator<File> {

        override fun compare(f1: File?, f2: File?): Int {
            return if (f1 == null || f2 == null) {
                if (f1 == null) {
                    -1
                } else {
                    1
                }
            } else {
                if (f1.isDirectory && f2.isFile) {
                    -1
                } else if (f1.isFile && f2.isDirectory) {
                    1
                } else {
                    // 相等返回 0, 否则违反 Comparator 契约 (TimSort 会抛异常)
                    f1.length().compareTo(f2.length())
                }
            }
        }

    }

    class SortByTime : Comparator<File> {

        override fun compare(f1: File?, f2: File?): Int {
            return if (f1 == null || f2 == null) {
                if (f1 == null) {
                    -1
                } else {
                    1
                }
            } else {
                if (f1.isDirectory && f2.isFile) {
                    -1
                } else if (f1.isFile && f2.isDirectory) {
                    1
                } else {
                    // 相等返回 0, 保持降序方向不变 (时间新在前)
                    f2.lastModified().compareTo(f1.lastModified())
                }
            }
        }

    }
}
