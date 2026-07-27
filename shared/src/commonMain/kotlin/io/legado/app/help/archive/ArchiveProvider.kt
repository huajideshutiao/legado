package io.legado.app.help.archive

import kotlin.concurrent.Volatile

/**
 * 压缩文件解压跨平台接口 (commonMain)。
 *
 * 供 JsExtensions 的压缩方法 (unzipFile/un7zFile/unrarFile/unArchiveFile/
 * getZip/Rar/7z StringContent/ByteArrayContent) 使用, 解耦对 app 端 ArchiveUtils/LibArchiveUtils
 * (依赖 Android native libarchive + ParcelFileDescriptor) 的直接依赖。
 *
 * # 实现注册
 * - **Android (app)**: AndroidArchiveProvider 委托 ArchiveUtils/LibArchiveUtils (libarchive 全格式),
 *   在 App.onCreate 经 [ArchiveProviders.register] 注入。
 * - **Desktop**: `DesktopArchiveProvider` (zip 走 JDK ZipInputStream; rar/7z 无 native 库仍缺)。
 * - **iOS / 鸿蒙**: 共用 nativeMain `NativeArchiveProvider` (zip/cbz 走 NativeZipCodec;
 *   rar/7z 抛明确异常)。
 *
 * 模式参考 [io.legado.app.model.fileBook.BitmapProviders]。
 */
interface ArchiveProvider {

    /** 临时解压目录名 (相对缓存目录), 与 app 端 ArchiveUtils.TEMP_FOLDER_NAME 对齐。 */
    val tempFolderName: String

    /**
     * 解压 [archivePath] 到临时目录, 返回解压出的文件相对路径列表。
     *
     * 对应 app 端 [io.legado.app.utils.ArchiveUtils.deCompress]。
     *
     * @param archivePath 压缩文件绝对路径
     * @return 解压出的文件路径列表 (实现内部决定绝对/相对, 调用方 JsExtensions 只用副作用+tempFolderName 拼相对目录)
     */
    fun deCompress(archivePath: String): List<String>

    /**
     * 从 [bytes] (压缩文件字节内容) 中读取指定 [path] 条目的字节。
     *
     * 对应 app 端 LibArchiveUtils.getByteArrayContent (libarchive 统一处理 zip/rar/7z)。
     * JsExtensions 的 getZip/Rar/7zByteArrayContent 统一走本方法 (zip 不特殊化, 用户决策)。
     *
     * @param bytes 压缩文件字节内容
     * @param path 压缩包内条目路径
     * @return 条目字节内容; null 未找到或解码失败
     */
    fun getByteArrayContent(bytes: ByteArray, path: String): ByteArray?
}

/**
 * [ArchiveProvider] provider 容器。宿主启动早期注册一次。
 */
object ArchiveProviders {
    @Volatile
    private var impl: ArchiveProvider? = null

    /** 宿主启动早期注册一次 (任何 JsExtensions 压缩方法调用之前)。 */
    fun register(impl: ArchiveProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): ArchiveProvider = impl ?: error("ArchiveProviders not registered")
}
