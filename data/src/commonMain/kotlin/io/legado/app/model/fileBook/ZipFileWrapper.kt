package io.legado.app.model.fileBook

import io.legado.app.utils.InputStream

/**
 * zip 容器统一读取面 (commonMain 下沉版, 四端共用)。
 *
 * 原契约在 jvmAndAndroidMain, 依赖 `java.io.InputStream` 与 `java.util.Enumeration`
 * 两个 JVM 类型, 故 iOS/鸿蒙无法实现, [CbzFile] 也就无法下沉 —— 表现为这两端
 * "本地 cbz 漫画" 一律抛 [io.legado.app.model.fileBook.NativeFileBookAccessor] 的
 * UnsupportedFileBook。本文件改用跨平台门面:
 * - `java.io.InputStream` → [io.legado.app.utils.InputStream] (expect/actual, 已有)
 * - `java.util.Enumeration<out ZipEntry>` → `List<ZipEntry>` (调用方本就 asSequence/toList)
 *
 * 实现由各端注册的 [ZipFileWrapperFactory] 创建:
 * - Android: ContentZipWrapper (content:// PFD) / LocalZipWrapper (file 路径) / LocalArchiveWrapper (libarchive)
 * - 桌面: DesktopZipFileWrapperFactory (LocalZipWrapper / RemoteZipWrapper)
 * - iOS / 鸿蒙: NativeZipFileWrapperFactory (RemoteZipCore 解析本地 cbz)
 */
interface ZipFileWrapper {

    /** 按条目名取条目元数据; 不存在返回 null。 */
    fun getEntry(name: String): ZipEntry?

    /** 打开条目内容流 (调用方负责 close); 条目缺失或解码失败返回 null。 */
    fun getInputStream(entry: ZipEntry): InputStream?

    /** 全部条目 (顺序即 zip 中央目录顺序)。 */
    fun entries(): List<ZipEntry>

    /** 释放底层句柄 (幂等)。 */
    fun close()
}
