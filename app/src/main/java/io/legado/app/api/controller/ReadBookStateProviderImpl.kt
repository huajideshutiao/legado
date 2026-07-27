package io.legado.app.api.controller

import io.legado.app.data.entities.BookProgress
import io.legado.app.model.ReadBook

/**
 * [ReadBookStateProvider] 的 Android 实现。
 *
 * 原 app 端 BookController.deleteBook/saveBookProgress 通过 `ReadBook.book` /
 * `ReadBook.webBookProgress` 同步当前阅读状态。ReadBook 单例 (object) 依赖 Compose
 * CompositionLocal 注入 (shared 端 ReadBookShared 是 class, 无全局访问点), 未下沉
 * commonMain。本实现桥接 app 端 ReadBook 单例, 在 App.onCreate 经
 * [ReadBookStateProviders.register] 注册, 供 shared/commonMain 端
 * [BookController.deleteBook]/[saveBookProgress] 走 provider 间接调用。
 *
 * # 行为等价
 * - [currentBookUrl]/[currentBookName]/[currentBookAuthor]: 对应 `ReadBook.book?.xxx`,
 *   无阅读书时返回 null (shared 端 BookController 据此跳过同步)。
 * - [clearCurrentBook]: 对应 `ReadBook.book = null`, deleteBook 删除当前阅读书时调用。
 * - [setWebBookProgress]: 对应 `ReadBook.webBookProgress = progress`,
 *   saveBookProgress 同步当前阅读书进度时调用。
 *
 * 注册时机: App.onCreate 经 [registerAndroidWebBookProviders]。
 * 模式参考 [io.legado.app.model.webBook.WebBookProvidersImpl]。
 */
object ReadBookStateProviderImpl : ReadBookStateProvider {

    override val currentBookUrl: String?
        get() = ReadBook.book?.bookUrl

    override val currentBookName: String?
        get() = ReadBook.book?.name

    override val currentBookAuthor: String?
        get() = ReadBook.book?.author

    override fun clearCurrentBook() {
        ReadBook.book = null
    }

    override fun setWebBookProgress(progress: BookProgress) {
        ReadBook.webBookProgress = progress
    }
}
