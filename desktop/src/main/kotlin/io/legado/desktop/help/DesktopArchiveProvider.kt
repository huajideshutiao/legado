package io.legado.desktop.help

import io.legado.app.help.archive.ArchiveProvider
import io.legado.app.help.archive.ArchiveProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.MD5Utils
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * [ArchiveProvider] 桌面 JVM 实现。
 *
 * zip 走 JDK [ZipInputStream]; rar/7z 暂未实现 (无 native libarchive, 用 TODO 占位)。
 * 在 desktop Main.kt 经 [registerDesktopArchiveProvider] 注入, 供 JsExtensionsCommon 压缩方法跨端调用。
 *
 * 解压临时目录对齐 app 端 ArchiveUtils: `cacheDir/{tempFolderName}/{md5(basename)}`。
 */
object DesktopArchiveProvider : ArchiveProvider {

    override val tempFolderName: String = "ArchiveTemp"

    override fun deCompress(archivePath: String): List<String> {
        // basename 提取: 兼容 '/' 与 '\' 分隔符, 与 JsExtensionsCommon.unArchiveFile 一致
        val name = archivePath.substringAfterLast('/').substringAfterLast('\\')
        val cachePath = AppFilesDirs.get().cacheDir
        val workDir = File(cachePath, tempFolderName).resolve(MD5Utils.md5Encode16(name))
        workDir.mkdirs()

        val ext = archivePath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "zip" -> unzipTo(archivePath, workDir)
            "rar", "7z" -> TODO("rar/7z 暂未实现, 需引入外部库 (libarchive / commons-compress)")
            else -> throw IllegalArgumentException("Unexpected file suffix: $ext")
        }
    }

    override fun getByteArrayContent(bytes: ByteArray, path: String): ByteArray? {
        // 桌面端无 libarchive, 仅尝试 zip 解析; 非 zip (rar/7z) 返回 null 由调用方回退
        return runCatching {
            ByteArrayInputStream(bytes).use { input ->
                ZipInputStream(input).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name == path) {
                            return@use zis.readBytes()
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    null
                }
            }
        }.getOrNull()
    }

    // 解压 zip 到 workDir, 防 Zip Slip (entry.name 含 ../ 跳出 workDir)
    private fun unzipTo(archivePath: String, workDir: File): List<String> {
        val extracted = mutableListOf<String>()
        val canonicalWorkDir = workDir.canonicalPath
        FileInputStream(archivePath).use { fis ->
            ZipInputStream(fis).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val outFile = File(workDir, entry.name)
                        // Zip Slip 防护: 解压目标必须仍在 workDir 内
                        if (!outFile.canonicalPath.startsWith(canonicalWorkDir + File.separator) &&
                            outFile.canonicalPath != canonicalWorkDir
                        ) {
                            throw SecurityException("Zip slip detected: ${entry.name}")
                        }
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        extracted.add(outFile.absolutePath)
                    }
                    zis.closeEntry()
                }
            }
        }
        return extracted
    }
}

/** 桌面端 main 入口注册 [ArchiveProvider], 须在任何 JsExtensions 压缩方法调用之前。 */
fun registerDesktopArchiveProvider() {
    ArchiveProviders.register(DesktopArchiveProvider)
}
