package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book

/**
 * [ZipFileWrapper] 工厂跨平台接口 (jvmAndAndroidMain)。
 *
 * 替代 app 端 `ZipFileWrappers.create(book)`, 解耦 [CbzFile] 对 app 端
 * `BookHelp.getBookPFD` / `AppWebDav` / `WebDav` / `ContentZipWrapper` /
 * `LocalArchiveWrapper` 等重 Android 依赖的直接引用。
 *
 * # 实现注册
 * - **Android (app)**: `ZipFileWrapperFactoryImpl` 包装原 `ZipFileWrappers.create`,
 *   在 `App.onCreate` 经 [ZipFileWrapperFactoryProviders.register] 注入。
 * - **Desktop (desktop)**: `DesktopZipFileWrapperFactory` 用 [LocalZipWrapper]
 *   (本地 .cbz/.zip 文件) + [RemoteZipWrapper] (WebDAV 远程) 实现, 在 `Main.kt` 注入。
 * - **iOS / 鸿蒙**: 暂未实现 (无本地 cbz 导入需求)。
 *
 * 模式参考 [io.legado.app.help.book.BookHelpProviders] /
 * [io.legado.app.help.book.BookStorageProviders]。
 */
interface ZipFileWrapperFactory {

    /**
     * 工厂创建结果。
     *
     * @param wrapper 已打开的 [ZipFileWrapper] (调用方负责 close)
     * @param closeable 需随 wrapper 一起关闭的附加资源 (如 Android ParcelFileDescriptor),
     *   为 null 表示无附加资源。实现内部应保证 [Closeable.close] 幂等。
     */
    data class CreateResult(
        val wrapper: ZipFileWrapper,
        val closeable: AutoCloseable? = null
    )

    /**
     * 为 [book] 创建 [ZipFileWrapper]。
     *
     * 内部按 book.bookUrl 分发:
     * - WebDAV / HTTP: [RemoteZipWrapper] (远程范围读取)
     * - 本地 .cbz / .zip: [LocalZipWrapper]
     * - 其他本地压缩包: LocalArchiveWrapper (app 端专属, desktop 无)
     *
     * @return 创建成功返回 [CreateResult]; 失败 (文件不存在/格式不支持) 返回 null
     */
    fun create(book: Book): CreateResult?
}

/**
 * [ZipFileWrapperFactory] provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `ZipFileWrapperFactoryProviders.get().create(book)`
 * 替代原 `ZipFileWrappers.create(book)`, 行为完全一致, 仅多一层 provider 间接。
 */
object ZipFileWrapperFactoryProviders {
    @Volatile
    private var impl: ZipFileWrapperFactory? = null

    /** 宿主启动早期注册一次 (任何 CbzFile 解析调用之前)。 */
    fun register(impl: ZipFileWrapperFactory) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): ZipFileWrapperFactory = impl ?: error("ZipFileWrapperFactoryProviders not registered")
}
