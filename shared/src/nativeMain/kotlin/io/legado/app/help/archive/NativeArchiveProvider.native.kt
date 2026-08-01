package io.legado.app.help.archive

import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.SecurityException
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.storage.NativeZipCodec
import io.legado.app.model.fileBook.RangedSource
import io.legado.app.model.fileBook.RemoteZipCore
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.File
import okio.Buffer
import okio.GzipSource

/**
 * native (iOS/鸿蒙) [ArchiveProvider]: 复用项目已有纯 Kotlin ZIP 能力
 * ([NativeZipCodec] 解压落盘 + [RemoteZipCore] 内存 zip 结构解析)。
 *
 * # 与 app 端 AndroidArchiveProvider (libarchive 全格式) 差异
 * - 支持 zip/cbz + tar/tar.gz/tgz/gz: tar 是纯格式无需压缩库, gzip 走 okio native
 *   [GzipSource] (okio 的 iosArm64/ohosArm64 klib 依赖 Kotlin/Native 自带的
 *   platform.zlib, 无需额外 cinterop)
 * - rar/7z/bz2/xz/lzma 无 native 解码器 (desktop 靠 JVM 的 commons-compress + junrar,
 *   两者均无 native 变体), deCompress 抛点名格式的异常, getByteArrayContent 返回 null
 * - deCompress 目标目录 `{cacheDir}/ArchiveTemp/{md516(basename)}`, 与
 *   JsExtensionsCommon.unArchiveFile 计算的相对目录 (tempFolderName/md516(name)) 对齐
 */
object NativeArchiveProvider : ArchiveProvider {

    /** 与 app 端 ArchiveUtils.TEMP_FOLDER_NAME 对齐。 */
    override val tempFolderName: String = "ArchiveTemp"

    override fun deCompress(archivePath: String): List<String> {
        val name = archivePath.substringAfterLast('/').substringAfterLast('\\')
        val destDir = FileUtilsCommon.getPath(
            FileUtilsCommon.getCachePath(), tempFolderName, MD5Utils.md5Encode16(name)
        )
        File(destDir).mkdirs()
        when {
            // tar 纯格式 (ustar), 无需压缩库
            name.endsWith(".tar", true) -> unTarToPath(File(archivePath), destDir)

            // tar.gz / .tgz: 先 gzip 解压成中间 tar, 解完删掉中间产物
            name.endsWith(".tar.gz", true) || name.endsWith(".tgz", true) -> {
                val tar = gzipToFile(File(archivePath))
                try {
                    unTarToPath(tar, destDir)
                } finally {
                    tar.delete()
                }
            }

            // 单文件 gzip: 直接解压成同 base 名文件
            name.endsWith(".gz", true) -> {
                val out = destDir + "/" + name.removeSuffix(".gz")
                File(out.substringBeforeLast('/')).mkdirs()
                File(out).writeBytes(gzipDecompress(File(archivePath).readBytes()))
            }

            // zip/cbz: 项目已有 NativeZipCodec
            name.endsWith(".zip", true) || name.endsWith(".cbz", true) ->
                NativeZipCodec.unZipToPath(archivePath, destDir)

            else -> throw NoStackTraceException(
                "iOS/鸿蒙端仅支持 zip/cbz/tar/tar.gz/tgz/gz 解压, 不支持 $name" +
                    " (rar/7z/bz2/xz/lzma 无 native 解码库)"
            )
        }
        // 返回解压出的文件绝对路径列表 (递归, 对齐 app 端 LibArchiveUtils.unArchive 返回 List<File>)
        val result = mutableListOf<String>()
        collectFiles(File(destDir), result)
        return result
    }

    /** gzip 解压到同目录临时 tar 文件 (okio native GzipSource)。 */
    private fun gzipToFile(src: File): File {
        val tmp = File(src.path + ".tar")
        tmp.writeBytes(gzipDecompress(src.readBytes()))
        return tmp
    }

    /**
     * tar (ustar) 解包: 512 字节块头, 后接文件内容 (按 size 对齐到 512),
     * 以两个全零块结束。
     */
    private fun unTarToPath(tar: File, destDir: String) {
        val data = tar.readBytes()
        var pos = 0
        while (pos + 512 <= data.size) {
            val header = data.copyOfRange(pos, pos + 512)
            // 全零块 = 归档结束
            if (header.all { it == 0.toByte() }) break
            val name = decodeTarName(header)
            // size 字段是八进制 ASCII (POSIX ustar)
            val size = header.copyOfRange(124, 136).trimZero().toAscii()
                .trim().ifEmpty { "0" }.toLongOrNull(8) ?: 0L
            // typeflag: '0'/NUL/空格 普通文件, '5' 目录, 其余 (符号链接/GNU 扩展头等) 跳过内容
            val typeFlag = header[156].toInt() and 0xFF
            val contentStart = pos + 512
            val contentEnd = contentStart + size.toInt()
            if (contentEnd > data.size) {
                throw NoStackTraceException("tar 内容越界: $name")
            }
            // 路径穿越防护 (对齐 NativeZipCodec.forEachEntry)
            if (name.contains("../") || name.startsWith("/")) {
                throw SecurityException("tar entry path unsafe: $name")
            }
            when {
                typeFlag == TYPE_DIR || name.endsWith("/") ->
                    File((destDir + "/" + name).trimEnd('/')).mkdirs()

                typeFlag in REGULAR_TYPES && name.isNotEmpty() -> {
                    val target = destDir + "/" + name
                    File(target.substringBeforeLast('/')).mkdirs()
                    File(target).writeBytes(data.copyOfRange(contentStart, contentEnd))
                }
            }
            // 块对齐: 内容按 512 取整 (保持循环单调推进)
            pos = contentEnd + ((512 - size % 512) % 512).toInt()
        }
    }

    /** tar 头名字: 优先 ustar 前缀字段, 否则基础 name 字段 (0..99)。 */
    private fun decodeTarName(header: ByteArray): String {
        val prefix = header.copyOfRange(345, 345 + 155).trimZero().toAscii()
        val name = header.copyOfRange(0, 100).trimZero().toAscii()
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    /** ASCII 解码 (tar 头字段均为 US-ASCII, 与 UTF-8 在 0..127 等价)。 */
    private fun ByteArray.toAscii(): String = decodeToString()

    /** 去尾部 NUL 填充。 */
    private fun ByteArray.trimZero(): ByteArray {
        var end = size
        while (end > 0 && this[end - 1] == 0.toByte()) end--
        return copyOf(end)
    }

    /** 全量解压 gzip 字节 (okio native GzipSource, 底层 platform.zlib; 语义同 jvm GZIPInputStream)。 */
    private fun gzipDecompress(bytes: ByteArray): ByteArray {
        val gz = GzipSource(Buffer().write(bytes))
        try {
            val out = Buffer()
            while (gz.read(out, DEFAULT_CHUNK) != -1L) Unit
            return out.readByteArray()
        } finally {
            gz.close()
        }
    }

    override fun getByteArrayContent(bytes: ByteArray, path: String): ByteArray? {
        return runCatching {
            val zip = RemoteZipCore(bytesRangedSource(bytes), "bytes", bytes.size.toLong())
            try {
                zip.ensureMeta()[path]?.let { zip.readDecompressed(it) }
            } finally {
                zip.close()
            }
        }.getOrNull()
    }

    private fun collectFiles(dir: File, out: MutableList<String>) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) collectFiles(f, out) else out.add(f.absolutePath)
        }
    }

    private fun bytesRangedSource(bytes: ByteArray) = RangedSource { offset, length, _ ->
        val start = offset.toInt().coerceAtLeast(0)
        val end = (start + length).coerceAtMost(bytes.size)
        if (start >= end) ByteArray(0) else bytes.copyOfRange(start, end)
    }

    // tar typeflag 字节值 (POSIX ustar): '5' 目录; 普通文件历史上有 '0'/NUL/空格 三种写法
    private const val TYPE_DIR = 0x35
    private val REGULAR_TYPES = intArrayOf(0x30, 0x00, 0x20)

    /** gzip 流式解压的单次读取上限。 */
    private const val DEFAULT_CHUNK = 64 * 1024L
}

/** iOS/鸿蒙宿主启动早期注册一次 (AppFilesDirs 之后、任何 JsExtensions 压缩方法调用之前)。 */
fun registerNativeArchiveProvider() {
    ArchiveProviders.register(NativeArchiveProvider)
}
