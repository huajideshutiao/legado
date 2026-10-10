package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.utils.File
import io.legado.app.utils.IosSecurityScopedStorage

/**
 * iOS [LocalBookLocator]: 路径解析复用 nativeMain [NativeLocalBookLocator] (与鸿蒙同一套规则),
 * 触及文件的只读动作 (最后修改时间) 走 [IosSecurityScopedStorage] 的 security-scoped 授权。
 *
 * # 为什么必须覆写文件动作
 * 用户经"文件"面板授权的目录在应用容器之外, 直接按 POSIX 路径 stat 会因缺少 security scope
 * 失败 (或被系统拒绝): 授权只在 [IosSecurityScopedStorage.acquireLease] 期间有效。
 * 路径解析本身不碰文件系统, 直接委托 nativeMain 实现, 不重复一套解析逻辑。
 *
 * 删本地书文件不在这里: 删文件只有统一入口
 * [io.legado.app.model.fileBook.FileBookAccessor] 一个, iOS 的授权由
 * [io.legado.app.model.fileBook.IosAuthorizedFileBookAccessor] 包裹 (含整段删原文件的租约)。
 *
 * 导入进 `{filesDir}/books` 的沙盒副本不需要授权, 授权层对沙盒路径自动放行。
 */
class IosLocalBookLocator : LocalBookLocator {

    private val paths = NativeLocalBookLocator()

    override fun getLocalPath(book: Book): String? = paths.getLocalPath(book)

    override fun getArchivePath(book: Book): String? = paths.getArchivePath(book)

    override fun cacheLocalPath(book: Book, path: String) = paths.cacheLocalPath(book, path)

    override fun removeLocalPathCache(book: Book) = paths.removeLocalPathCache(book)

    /**
     * 本地书文件最后修改时间 (epoch millis)。
     *
     * 授权失效时返回 0 —— 与原版"取不到时间即视为未修改"的判定一致
     * ([io.legado.app.help.book.isLocalModified] 用 0 与 latestChapterTime 比较), 不伪造时间戳;
     * 真正的"重新选目录"提示由阅读页打开文件时抛出的授权异常触发。
     */
    override fun getLastModified(book: Book): Long {
        val path = paths.getLocalPath(book) ?: return 0L
        return runCatching {
            IosSecurityScopedStorage.withAccess(path) { File(it).lastModified() }
        }.getOrDefault(0L)
    }
}

/** 注册 iOS 本地书定位实现 (在 [registerNativeLocalBookLocator] 的位置调用)。 */
fun registerIosLocalBookLocator() {
    LocalBookLocators.register(IosLocalBookLocator())
}
