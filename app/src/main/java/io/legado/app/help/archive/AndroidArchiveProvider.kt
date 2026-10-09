package io.legado.app.help.archive

import androidx.core.net.toUri
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

    /**
     * 列举压缩包内条目名 (委托 [ArchiveUtils.getArchiveFilesName])。
     *
     * `ArchiveUtils.getArchiveFilesName` 自带后缀校验与“读失败返回空列表”语义,
     * 与共享接口注释描述的行为完全一致, 故直接转发不做二次包装。
     */
    override fun getFilesName(archivePath: String, filter: ((String) -> Boolean)?): List<String> =
        ArchiveUtils.getArchiveFilesName(archivePath.toUri(), filter)

    override fun getByteArrayContent(bytes: ByteArray, path: String): ByteArray? =
        ByteArrayInputStream(bytes).use { LibArchiveUtils.getByteArrayContent(it, path) }
}
