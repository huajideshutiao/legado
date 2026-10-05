package com.github.catvod.utils

import android.os.Environment
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import com.github.catvod.Init
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Arrays
import java.util.logging.Logger

/**
 * TVBox 壳文件路径工具: 方法面/语义逐字对齐 FongMi catvod 模块的 utils.Path
 * (jar 内常以 Path.jar/Path.local/Path.read/Path.create 等直调, FQCN 与方法签名必须一致)。
 *
 * 与 FongMi 的唯一差异: Logger 输出换为 JUL; root()/cache()/files() 依赖的
 * Environment/Init.context() 在桌面端由 :data jvmMain 的 android.* stub 落到 AppFilesDirs
 * (Android 端走真实框架, 行为不变)。
 */
object Path {

    private val logger = Logger.getLogger("CatvodPath")

    private fun mkdir(file: File?): File? {
        if (file == null || file.exists()) return file
        if (file.mkdirs()) logger.fine("Created dir:$file")
        return file
    }

    @JvmStatic
    fun exists(path: String?): Boolean = File(path!!.replace("file://", "")).exists()

    @JvmStatic
    fun exists(file: File?): Boolean = file != null && file.exists() && file.length() > 0

    @JvmStatic
    fun root(): File = Environment.getExternalStorageDirectory()

    @JvmStatic
    fun cache(): File = Init.context()!!.getCacheDir()

    @JvmStatic
    fun files(): File = Init.context()!!.getFilesDir()

    @JvmStatic
    fun rootPath(): String = root().absolutePath

    @JvmStatic
    fun tv(): File = mkdir(File(root(), "TV"))!!

    @JvmStatic
    fun backup(): File = mkdir(File(tv(), "backup"))!!

    @JvmStatic
    fun font(): File = mkdir(File(tv(), "fonts"))!!

    @JvmStatic
    fun wall(index: Int): File = files("wallpaper_$index")

    @JvmStatic
    fun wallCache(): File = files("wallpaper_cache")

    @JvmStatic
    fun so(): File = mkdir(File(files(), "so"))!!

    @JvmStatic
    fun js(): File = mkdir(File(cache(), "js"))!!

    @JvmStatic
    fun py(): File = mkdir(File(cache(), "py"))!!

    @JvmStatic
    fun jar(): File = mkdir(File(cache(), "jar"))!!

    @JvmStatic
    fun exoCache(): File = mkdir(File(cache(), "exo"))!!

    @JvmStatic
    fun mpvCache(): File = mkdir(File(cache(), "mpv"))!!

    @JvmStatic
    fun mpv(): File = mkdir(File(tv(), "mpv"))!!

    @JvmStatic
    fun epg(): File = mkdir(File(cache(), "epg"))!!

    @JvmStatic
    fun jpa(): File = mkdir(File(cache(), "jpa"))!!

    @JvmStatic
    fun thunder(): File = mkdir(File(cache(), "thunder"))!!

    @JvmStatic
    fun root(name: String): File = File(root(), name)

    @JvmStatic
    fun root(child: String, name: String): File = File(mkdir(File(root(), child))!!, name)

    @JvmStatic
    fun cache(name: String): File = File(cache(), name)

    @JvmStatic
    fun files(name: String): File = File(files(), name)

    @JvmStatic
    fun mpv(name: String): File = File(mpv(), name)

    @JvmStatic
    fun epg(name: String): File = File(epg(), name)

    @JvmStatic
    fun js(name: String): File = File(js(), name)

    @JvmStatic
    fun py(name: String): File = File(py(), name)

    @JvmStatic
    fun jar(name: String): File = File(jar(), Crypto.md5(name) + ".jar")

    @JvmStatic
    fun thunder(name: String): File = mkdir(File(thunder(), name))!!

    @JvmStatic
    fun local(path: String): File {
        val cleaned = path.replace("file:/", "")
        val file = File(root(), cleaned)
        return if (file.exists()) file else File(cleaned)
    }

    @JvmStatic
    fun read(file: File?): String = try {
        String(readToByte(file), StandardCharsets.UTF_8)
    } catch (e: Exception) {
        ""
    }

    @JvmStatic
    fun read(stream: InputStream?): String = try {
        String(readToByte(stream!!), StandardCharsets.UTF_8)
    } catch (e: IOException) {
        ""
    }

    @JvmStatic
    fun readToByte(file: File?): ByteArray = try {
        FileInputStream(file).use { readToByte(it) }
    } catch (e: IOException) {
        ByteArray(0)
    }

    @Throws(IOException::class)
    private fun readToByte(stream: InputStream): ByteArray {
        stream.use { input ->
            val bos = ByteArrayOutputStream()
            var read: Int
            val buffer = ByteArray(16384)
            while (input.read(buffer).also { read = it } != -1) bos.write(buffer, 0, read)
            return bos.toByteArray()
        }
    }

    @JvmStatic
    fun write(file: File, stream: InputStream?): File = try {
        stream!!.use { input ->
            FileOutputStream(create(file)).use { output ->
                var read: Int
                val buffer = ByteArray(16384)
                while (input.read(buffer).also { read = it } != -1) output.write(buffer, 0, read)
            }
        }
        file
    } catch (e: IOException) {
        file
    }

    @JvmStatic
    fun write(file: File, data: ByteArray?): File = try {
        FileOutputStream(create(file)).use { fos ->
            fos.write(data)
            fos.flush()
        }
        file
    } catch (e: IOException) {
        file
    }

    @JvmStatic
    fun copy(source: File?, target: File) {
        try {
            copyOrThrow(source!!, target)
        } catch (ignored: IOException) {
        }
    }

    @JvmStatic
    fun copy(source: InputStream?, target: File) {
        try {
            copyOrThrow(source!!, target)
        } catch (ignored: IOException) {
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun move(source: File, target: File) {
        try {
            Os.rename(source.absolutePath, target.absolutePath)
        } catch (e: ErrnoException) {
            throw IOException("Unable to move file", e)
        }
    }

    @JvmStatic
    fun size(file: File?): Long {
        var total: Long = 0
        if (file == null) return total
        if (file.isDirectory) for (child in list(file)) total += size(child)
        else total = file.length()
        return total
    }

    @JvmStatic
    fun available(file: File?): Long = try {
        val stat = StatFs(file!!.absolutePath)
        stat.getAvailableBlocksLong() * stat.getBlockSizeLong()
    } catch (e: Exception) {
        0
    }

    @Throws(IOException::class)
    private fun copyOrThrow(source: File, target: File) {
        if (source.canonicalFile != target.canonicalFile) copyOrThrow(FileInputStream(source), target)
    }

    @Throws(IOException::class)
    private fun copyOrThrow(source: InputStream, target: File) {
        source.use { input ->
            FileOutputStream(create(target)).use { output ->
                var read: Int
                val buffer = ByteArray(16384)
                while (input.read(buffer).also { read = it } != -1) output.write(buffer, 0, read)
            }
        }
    }

    @JvmStatic
    fun sort(files: Array<File>) {
        Arrays.sort(files) { o1: File, o2: File ->
            when {
                o1.isDirectory && o2.isFile -> -1
                o1.isFile && o2.isDirectory -> 1
                else -> o1.getName().compareTo(o2.getName(), ignoreCase = true)
            }
        }
    }

    @JvmStatic
    fun list(dir: File?): List<File> {
        val files = dir?.listFiles()
        if (files != null) sort(files)
        return files?.toList() ?: ArrayList()
    }

    @JvmStatic
    fun clear(dir: File?) {
        if (dir == null) return
        if (dir.isDirectory) for (file in list(dir)) clear(file)
        if (dir.delete()) logger.fine("Deleted:$dir")
    }

    @JvmStatic
    fun create(file: File): File = try {
        val parent = file.parentFile
        if (parent != null) mkdir(parent)
        if (file.exists()) clear(file)
        if (file.createNewFile()) logger.fine("Create:$file")
        file.setReadable(true)
        file.setWritable(true)
        file.setExecutable(true)
        Shell.exec("chmod 777 $file")
        file
    } catch (e: IOException) {
        file
    }
}
