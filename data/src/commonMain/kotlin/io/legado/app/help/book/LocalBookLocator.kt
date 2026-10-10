package io.legado.app.help.book

import io.legado.app.data.entities.Book
import kotlin.concurrent.Volatile

/**
 * 本地书文件定位跨平台抽象。
 *
 * 安卓端 [io.legado.app.help.book.BookHelp] 的 getBookPFD 等方法
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
     * 取本地书当前可用的文件引用 (读本地书文件的入口解析 bookUrl 用)。
     *
     * 按 bookUrl 解析出的引用读不到时, 在"书籍保存位置" ([effectiveBookSaveDir]) 与导入目录
     * ([effectiveImportBookDir]) 里按 [Book.originName] 找回同名文件 —— 与原版
     * `Book.getLocalUri()` 的定位口径一致 (每个目录下探 5 层、最多列举 100 个目录; 两个目录同一处时
     * 只查一次)。
     *
     * 只定位: 按名找回的结果不回写 bookUrl、不落库, 也不写路径缓存 (缓存会让找回的路径
     * 成为删除等其它入口的目标)。bookUrl 自身的解析与其它定位入口一样经 [getLocalPath],
     * 会记入同一张路径缓存。
     *
     * @return 安卓端 SAF 引用为 `content://` URI 字符串, 其余端为本地绝对路径;
     *   非本地书或确实找不到时返回 null (调用方按失败处理, 不伪造一个打不开的引用)
     */
    fun getReadableFileRef(book: Book): String?

    /**
     * 取压缩包子书对应的原压缩包引用 (原压缩包被移走/改名后重新解压用)。
     *
     * 与 [getReadableFileRef] 同一套目录与同一套按名找回: 在"书籍保存位置"与导入目录里找
     * [Book.archiveName], 单目录内的查找同样交给各端覆写的按名找回实现 (iOS/鸿蒙在各自目录授权内
     * 列举) —— 未按本用途登记的目录照旧列不出内容, 不放宽用途校验。
     *
     * 安卓与桌面的压缩包回退在各自的本地书访问器内完成 (app 端 `Book.getArchiveUri`、桌面端
     * `DesktopFileBookAccessor.findArchiveFile`), 不经本方法, 故默认返回 null;
     * iOS/鸿蒙由 nativeMain 的定位实现覆写。
     *
     * 只定位: 不写路径缓存、不改书籍记录。
     *
     * @return 找回来的压缩包引用; 非压缩包子书或确实找不到时返回 null (调用方按原先的失败如实上报)
     */
    fun getReadableArchiveRef(book: Book): String? = null

    /**
     * 移除缓存的本地书路径 (供书籍删除场景清理)。
     */
    fun removeLocalPathCache(book: Book)
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
