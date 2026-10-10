package io.legado.app.help.book

import io.legado.app.data.entities.Book
import kotlin.concurrent.Volatile

/**
 * 本地书文件定位跨平台抽象。
 *
 * 安卓端 [io.legado.app.help.book.BookHelp] 的 getLocalUri / getBookPFD 等方法
 * 重 Android 依赖 (ContentResolver / DocumentFile / StorageManager /
 * ParcelFileDescriptor), 留 app 端。本接口仅暴露本地书文件路径定位与基本文件
 * 操作方法, 供桌面 JVM / iOS / 鸿蒙 commonMain 业务调用。
 *
 * 实现注册:
 * - Android: app 端启动早期注入 [io.legado.app.help.book.AndroidLocalBookLocator]
 *   (ContentResolver / DocumentFile 实现)。
 * - 桌面 JVM: desktop 模块启动时注入 [io.legado.app.help.book.JvmLocalBookLocator]
 *   (java.nio.file.Path 实现)。
 * - iOS / 鸿蒙: 共用 nativeMain [NativeLocalBookLocator] (`kotlin.io.File` 实现);
 *   iOS 在它外面再包一层 security-scoped 授权 ([io.legado.app.help.book.IosLocalBookLocator])。
 *
 * 本接口只做路径定位与只读查询: 删文件动作只有统一入口
 * [io.legado.app.model.fileBook.FileBookAccessor] 一个 (各端授权在它外层挂点)。
 *
 * 模式参考 [BookStorageProviders] / [io.legado.app.help.book.BookHelpProviders]。
 */
interface LocalBookLocator {

    /**
     * 取本地书文件路径 (bookUrl 形如 `file:///C:/path/book.txt` 或本地绝对路径);
     * 非本地书 (网络书源) 返回 null。
     */
    fun getLocalPath(book: Book): String?

    /**
     * 取压缩包内子书路径 (book.origin 形如 `local_book::archive.rar`);
     * 非压缩包子书返回 null。
     */
    fun getArchivePath(book: Book): String?

    /**
     * 缓存本地书路径 (供书架批量操作场景减少重复解析)。
     */
    fun cacheLocalPath(book: Book, path: String)

    /**
     * 移除缓存的本地书路径 (供书籍删除场景清理)。
     */
    fun removeLocalPathCache(book: Book)

    /**
     * 取本地书文件最后修改时间 (epoch millis); 文件不存在返回 0。
     */
    fun getLastModified(book: Book): Long
}

/**
 * [LocalBookLocator] provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `LocalBookLocators.get().getLocalPath(...)` 取本地书路径 ——
 * `BookHelp.getBookPFD(...)` 一类 Android 专属方法不下沉 commonMain。
 */
object LocalBookLocators {
    @Volatile
    private var impl: LocalBookLocator? = null

    /** 宿主启动早期注册一次 (任何 BookContent 调用之前)。 */
    fun register(impl: LocalBookLocator) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): LocalBookLocator = impl ?: error("LocalBookLocators not registered")
}
