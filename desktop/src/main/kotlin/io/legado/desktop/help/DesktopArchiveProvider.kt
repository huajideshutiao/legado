package io.legado.desktop.help

import io.legado.app.help.archive.ArchiveProvider
import io.legado.app.help.archive.ArchiveProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.MD5Utils
import io.legado.desktop.help.archive.DesktopArchiveCodec
import java.io.File

/**
 * [ArchiveProvider] 桌面 JVM 实现。
 *
 * 解压委托 [DesktopArchiveCodec] (commons-compress + junrar), 覆盖 zip/7z/tar/gz/bz2/xz/lzma
 * 与 rar4/5/7。在 desktop Main.kt 经 [registerDesktopArchiveProvider] 注入,
 * 供 JsExtensionsCommon 压缩方法跨端调用。
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
        return DesktopArchiveCodec.unArchive(File(archivePath), workDir, null)
            .map { it.absolutePath }
    }

    override fun getByteArrayContent(bytes: ByteArray, path: String): ByteArray? {
        // 格式由魔数嗅探, 解析失败返回 null 由调用方回退 (对齐原 zip-only 实现的容错语义)
        return runCatching { DesktopArchiveCodec.getByteArrayContent(bytes, path) }.getOrNull()
    }
}

/** 桌面端 main 入口注册 [ArchiveProvider], 须在任何 JsExtensions 压缩方法调用之前。 */
fun registerDesktopArchiveProvider() {
    ArchiveProviders.register(DesktopArchiveProvider)
}
