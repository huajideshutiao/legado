package io.legado.app.help.image

import io.legado.app.help.archive.ArchiveProviders
import io.legado.app.utils.File

/**
 * cbz:// 图片 URL 的 native (iOS/鸿蒙) 条目字节抽取 (封面/漫画页共用)。
 *
 * 支持两种形式:
 * - `cbz://{archivePath}#{entry}`: 自含压缩包路径 (封面等无 Book 上下文场景)
 * - `cbz://{entry}`: 仅条目名, 压缩包路径取 [bookUrl] (对齐 desktop loadMangaImage /
 *   JvmImageBitmapLoader 的 CbzFile.getImage(book, entryName) 约定)
 *
 * 解压走 [ArchiveProviders] (native 端注册 NativeArchiveProvider, RemoteZipCore 内存 zip 解析),
 * 解码由调用方完成 (鸿蒙: ohosDecodeImageBytes; iOS: Skia makeFromEncoded)。
 *
 * 每次调用整包读入内存再取条目 (NativeArchiveProvider.getByteArrayContent 签名如此);
 * 封面为一次性调用, 漫画页调用方自带内存 LRU, 不在此层加缓存。
 */
internal fun loadCbzEntryBytes(url: String, bookUrl: String?): ByteArray? {
    if (!url.startsWith("cbz://")) return null
    val raw = url.removePrefix("cbz://")
    // 自含形式判定: # 前缀存在且指向真实文件才成立, 否则整段按条目名处理 (条目名含 # 不误判)
    val hashIdx = raw.lastIndexOf('#')
    if (hashIdx > 0) {
        val selfPath = stripFileScheme(raw.substring(0, hashIdx))
        if (File(selfPath).exists()) {
            return readCbzEntry(selfPath, raw.substring(hashIdx + 1))
        }
    }
    val archivePath = bookUrl?.let(::stripFileScheme) ?: return null
    return readCbzEntry(archivePath, raw)
}

/** 从 [archivePath] 压缩包中读取 [entry] 条目字节; 文件不存在/条目未命中/解压失败返回 null。 */
private fun readCbzEntry(archivePath: String, entry: String): ByteArray? {
    if (entry.isEmpty()) return null
    return runCatching {
        val file = File(archivePath)
        if (!file.exists()) return@runCatching null
        ArchiveProviders.get().getByteArrayContent(file.readBytes(), entry)
    }.getOrNull()
}

/** 剥离 file:// scheme 为本地路径 (与 NativeFileBookAccessor.resolveLocalFile 规则一致)。 */
private fun stripFileScheme(pathOrUrl: String): String {
    if (!pathOrUrl.startsWith("file:")) return pathOrUrl
    val afterScheme = pathOrUrl.substringAfter("file://")
    val slashIdx = afterScheme.indexOf('/')
    return if (slashIdx > 0) afterScheme.substring(slashIdx) else afterScheme
}
