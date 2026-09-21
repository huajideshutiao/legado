package io.legado.app.api.controller

import io.legado.app.data.entities.BookProgress

/**
 * ReadBook 单例状态跨平台 provider 契约。
 *
 * 原 app 端 BookController.deleteBook/saveBookProgress 通过 `ReadBook.book` /
 * `ReadBook.webBookProgress` 同步当前阅读状态。ReadBook 单例 (object) 依赖 Compose
 * CompositionLocal 注入 (ReadBookShared 是 class, 无全局访问点), 未下沉 commonMain。
 * 本接口把 BookController 用到的 4 个 ReadBook 操作抽象为 commonMain 可用契约,
 * 由 app 端实现桥接 ReadBook 单例, 在 App.onCreate 经 [ReadBookStateProviders.register] 注册。
 *
 * desktop/iOS/鸿蒙端无 ReadBook 单例, 不注册时 [getOrNull] 返回 null,
 * BookController.deleteBook/saveBookProgress 跳过 ReadBook 同步 (web API 返回值不受影响,
 * 仅当前阅读界面状态不同步, 这些平台本无阅读界面)。
 *
 * 模式参考 [io.legado.app.data.AppDbProviders]。
 */
interface ReadBookStateProvider {
    /** 当前阅读书籍的 bookUrl (对应 `ReadBook.book?.bookUrl`), null 表示无正在阅读的书。 */
    val currentBookUrl: String?

    /** 当前阅读书籍的 name (对应 `ReadBook.book?.name`)。 */
    val currentBookName: String?

    /** 当前阅读书籍的 author (对应 `ReadBook.book?.author`)。 */
    val currentBookAuthor: String?

    /** 清空当前阅读书 (对应 `ReadBook.book = null`), deleteBook 删除当前阅读书时调用。 */
    fun clearCurrentBook()

    /** 设置 web 阅读进度 (对应 `ReadBook.webBookProgress = progress`), saveBookProgress 同步时调用。 */
    fun setWebBookProgress(progress: BookProgress)
}

/**
 * [ReadBookStateProvider] provider 容器。宿主启动早期注册一次 (可选)。
 *
 * 与 [AppDbProviders] 不同, 本容器允许不注册 (desktop/iOS/鸿蒙无 ReadBook 单例):
 * BookController 通过 [getOrNull] 取实现, null 时跳过 ReadBook 同步, 行为降级但不报错。
 */
object ReadBookStateProviders {
    @Volatile
    private var impl: ReadBookStateProvider? = null

    /** 宿主启动早期注册一次 (可选, desktop/iOS/鸿蒙可不注册)。 */
    fun register(impl: ReadBookStateProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册返回 null (调用方自行降级处理)。 */
    fun getOrNull(): ReadBookStateProvider? = impl
}
