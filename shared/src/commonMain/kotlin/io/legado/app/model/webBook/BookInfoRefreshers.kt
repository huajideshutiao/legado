package io.legado.app.model.webBook

import io.legado.app.data.entities.BaseSource
import kotlin.concurrent.Volatile

/**
 * 书籍信息刷新 provider。
 *
 * AnalyzeRule 主体下沉 shared 后, refreshTocUrl() JS API 不能直接 import app 的 WebBook,
 * 经本 provider 反向调用 app 端 WebBook.getBookInfoAwait, 解除循环依赖。
 *
 * 宿主(安卓 App.onCreate)启动早期经 [BookInfoRefreshers.register] 注册一次。
 *
 * P2 Step 2: 为 AnalyzeRule 主体下沉 shared 做前置, 解除 AnalyzeRule→WebBook 直接依赖。
 */
object BookInfoRefreshers {
    @Volatile
    private var impl: BookInfoRefresher? = null

    /** 宿主启动早期注册一次(任何 AnalyzeRule.refreshTocUrl 调用之前)。 */
    fun register(impl: BookInfoRefresher) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册返回 null(refreshTocUrl 静默跳过, 保持原行为)。 */
    fun getOrNull(): BookInfoRefresher? = impl
}

/**
 * 书籍信息刷新接口。由 app 端实现并注册(委托给 WebBook.getBookInfoAwait)。
 *
 * 方法签名与 WebBook.getBookInfoAwait 对齐, 但仅暴露 AnalyzeRule 需要的部分。
 * refreshTocUrl 只用到 (bookSource, book, canReName=false), 不需要返回值。
 *
 * 参数类型: bookSource 用 BaseSource (已下沉 shared), book 用 Any (Book 实体未下沉,
 * app 端实现内 as Book 强转, 行为等价)。
 */
interface BookInfoRefresher {
    /**
     * 拉取并刷新书籍信息(对应 WebBook.getBookInfoAwait)。
     *
     * @param bookSource 书源
     * @param book 书籍(可变, 实现内部会写回 book 字段)
     * @param canReName 是否允许重命名
     */
    suspend fun refreshBookInfo(
        bookSource: BaseSource,
        book: Any,
        canReName: Boolean,
    )
}
