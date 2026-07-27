package io.legado.app.model.remote

import io.legado.app.data.AppDbProviders
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.ui.book.import.ImportFileItem

/**
 * 远程书籍条目模型。
 *
 * 实现 shared 端 [ImportFileItem] 接口供 [io.legado.app.ui.book.import.remote.RemoteBookScreen]
 * 泛型复用:
 * - [tag] 取 [contentType] (对照原 RemoteBookScreen 调用点 `item.contentType`)
 * - [itemKey] 取 [path] (对照原 items() key 参数 `it.path`)
 * - [lastModified] 桥接 [lastModify] (ImportBook 用 lastModified, 此处接口统一)
 *
 * # 下沉说明 (app → shared/commonMain)
 * - 原 `@Keep` (androidx.annotation.Keep) 移除: commonMain 沿用项目惯例 (照 CacheManager /
 *   OldRssSource / AnalyzeByJSoup 先例), 反射保活改由 shared/consumer-rules.pro -keep 登记。
 * - 原 `appDb.bookDao.hasFile(...)` → `AppDbProviders.get().bookDao.hasFile(...)`
 *   (appDb 顶层 val 在 app 模块, commonMain 经 [AppDbProviders] 注入访问, 照 BookController 先例)。
 * - 原 `runBlocking { bookDao.hasFile(...) }` 在构造函数中调用 (构造函数不能 suspend),
 *   已改为 [create] 工厂函数 (suspend), 调用方 (getRemoteBookList/getRemoteBook 均 suspend)
 *   改用 `RemoteBook.create(webDavFile)`。原 runBlocking 移除 (commonMain 禁用, Native 死锁风险)。
 */
data class RemoteBook(
    val filename: String,
    val path: String,
    override val size: Long,
    val lastModify: Long,
    var contentType: String = "folder",
    override var isOnBookShelf: Boolean = false,
    override val isUpDir: Boolean = false
) : ImportFileItem {

    override val isDir get() = contentType == "folder" && !isUpDir
    override val name get() = if (isUpDir) ".." else filename
    override val lastModified get() = lastModify
    override val tag get() = contentType
    override val itemKey get() = path

    /**
     * 次级构造: 仅设置 contentType, 不查询书架状态 (isOnBookShelf 默认 false)。
     * 书架状态查询请用 [create] 工厂函数 (suspend)。
     */
    constructor(webDavFile: WebDavFile) : this(
        webDavFile.displayName,
        webDavFile.path,
        webDavFile.size,
        webDavFile.lastModify
    ) {
        if (!webDavFile.isDir) {
            contentType = webDavFile.displayName.substringAfterLast(".")
        }
    }

    companion object {
        /**
         * 工厂函数: 构造 RemoteBook 并查询书架状态 (bookDao.hasFile 已 suspend)。
         * 替代原构造函数中的 `runBlocking { bookDao.hasFile(...) }`。
         */
        suspend fun create(webDavFile: WebDavFile): RemoteBook {
            val book = RemoteBook(webDavFile)
            if (!webDavFile.isDir) {
                book.isOnBookShelf = AppDbProviders.get().bookDao.hasFile(webDavFile.displayName)
            }
            return book
        }
    }

}
