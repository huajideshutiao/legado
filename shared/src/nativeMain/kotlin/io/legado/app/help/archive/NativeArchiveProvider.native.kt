package io.legado.app.help.archive

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.storage.NativeZipCodec
import io.legado.app.model.fileBook.RangedSource
import io.legado.app.model.fileBook.RemoteZipCore
import io.legado.app.utils.MD5Utils
import kotlin.io.File

/**
 * native (iOS/鸿蒙) [ArchiveProvider]: 复用项目已有纯 Kotlin ZIP 能力
 * ([NativeZipCodec] 解压落盘 + [RemoteZipCore] 内存 zip 结构解析)。
 *
 * # 与 app 端 AndroidArchiveProvider (libarchive 全格式) 差异
 * - 仅支持 zip/cbz; rar/7z 无 native 库, deCompress 抛明确异常, getByteArrayContent 返回 null
 * - deCompress 目标目录 `{cacheDir}/ArchiveTemp/{md516(basename)}`, 与
 *   JsExtensionsCommon.unArchiveFile 计算的相对目录 (tempFolderName/md516(name)) 对齐
 */
object NativeArchiveProvider : ArchiveProvider {

    /** 与 app 端 ArchiveUtils.TEMP_FOLDER_NAME 对齐。 */
    override val tempFolderName: String = "ArchiveTemp"

    override fun deCompress(archivePath: String): List<String> {
        val name = archivePath.substringAfterLast('/').substringAfterLast('\\')
        if (!name.endsWith(".zip", true) && !name.endsWith(".cbz", true)) {
            throw NoStackTraceException("iOS/鸿蒙端仅支持 zip/cbz 解压, 不支持 $name")
        }
        val destDir = FileUtilsCommon.getPath(
            FileUtilsCommon.getCachePath(), tempFolderName, MD5Utils.md5Encode16(name)
        )
        File(destDir).mkdirs()
        NativeZipCodec.unZipToPath(archivePath, destDir)
        // 返回解压出的文件绝对路径列表 (递归, 对齐 app 端 LibArchiveUtils.unArchive 返回 List<File>)
        val result = mutableListOf<String>()
        collectFiles(File(destDir), result)
        return result
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
}

/** iOS/鸿蒙宿主启动早期注册一次 (AppFilesDirs 之后、任何 JsExtensions 压缩方法调用之前)。 */
fun registerNativeArchiveProvider() {
    ArchiveProviders.register(NativeArchiveProvider)
}
