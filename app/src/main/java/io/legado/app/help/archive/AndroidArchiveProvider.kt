package io.legado.app.help.archive

import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.compress.LibArchiveUtils
import java.io.ByteArrayInputStream

/**
 * [ArchiveProvider] Android 实现, 委托 [ArchiveUtils]/[LibArchiveUtils] (libarchive 全格式 zip/rar/7z/tar/...)。
 *
 * 在 App.onCreate 经 [ArchiveProviders.register] 注入, 供 JsExtensions 压缩方法跨端调用。
 */
object AndroidArchiveProvider : ArchiveProvider {

    override val tempFolderName: String = ArchiveUtils.TEMP_FOLDER_NAME

    override fun deCompress(archivePath: String): List<String> =
        ArchiveUtils.deCompress(archivePath).map { it.absolutePath }

    override fun getByteArrayContent(bytes: ByteArray, path: String): ByteArray? =
        ByteArrayInputStream(bytes).use { LibArchiveUtils.getByteArrayContent(it, path) }
}
