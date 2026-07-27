package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book

/**
 * [ZipFileWrapperFactory] Android 实现 (app 端)。
 *
 * 包装 app 端原有 [ZipFileWrappers.create], 行为完全一致:
 * - WebDAV / HTTP: [RemoteZipWrapper] (远程范围读取)
 * - 本地 .cbz / .zip: [LocalZipWrapper] (文件路径) 或 [ContentZipWrapper] (content URI)
 * - 其他本地压缩包: [LocalArchiveWrapper] (PFD 解压)
 *
 * 下沉后 [CbzFile] (shared) 不再直接依赖 app 端 [ZipFileWrappers],
 * 改经 [ZipFileWrapperFactoryProviders] provider 间接调用本实现。
 *
 * 注册: [registerAndroidWebBookProviders] 中
 * `ZipFileWrapperFactoryProviders.register(ZipFileWrapperFactoryImpl)`。
 *
 * # PFD 传递
 * [ZipFileWrappers.CreateResult.fileDescriptor] (ParcelFileDescriptor?) 直接
 * 作为 [ZipFileWrapperFactory.CreateResult.closeable] (AutoCloseable?) 传递,
 * ParcelFileDescriptor 实现 Closeable, 兼容。
 */
object ZipFileWrapperFactoryImpl : ZipFileWrapperFactory {

    override fun create(book: Book): ZipFileWrapperFactory.CreateResult? {
        return ZipFileWrappers.create(book)?.let { result ->
            ZipFileWrapperFactory.CreateResult(
                wrapper = result.wrapper,
                closeable = result.fileDescriptor
            )
        }
    }
}
