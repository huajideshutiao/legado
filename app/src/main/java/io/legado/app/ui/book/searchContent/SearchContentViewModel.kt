package io.legado.app.ui.book.searchContent


import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.ChineseUtils

/**
 * 书内全文搜索 ViewModel (Android 端)。
 *
 * # KMP 化重构说明
 *
 * 核心搜索逻辑 (initBook / searchChapter / searchPosition / getResultAndQueryIndex)
 * 已下沉到 shared commonMain [SearchContentViewModelShared], 替代原直接依赖
 * `IntentData.book` / `BookHelp.getContent` / `ContentProcessor.get(...).getContent` /
 * `AppConfig.chineseConverterType` / `ChineseUtils.t2s|s2t`。
 *
 * 本类采用**组合委托**模式持有 [shared] 实例, 不通过继承 [SearchContentViewModelShared]:
 * - 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 *   `viewModelScope`), Kotlin 单继承无法同时继承 [SearchContentViewModelShared];
 * - 平台专属逻辑通过 lambda 注入 [shared]:
 *   - 简繁转换 [ChineseUtils.t2s] / [ChineseUtils.s2t] (依赖 quick-transfer 库 + 反射
 *     + 字典缓存, 通过 lambda 注入避免 commonMain 引入 ChineseUtils);
 * - 仅 2 个参数 (scope + chineseConverter), 不违反"避免超多继承与参数传递"原则。
 *
 * # 调用方兼容
 *
 * [SearchContentActivity] 调用方式保持不变:
 * - `viewModel.book` / `viewModel.bookUrl` / `viewModel.replaceEnabled`
 * - `viewModel.cacheChapterNames` / `viewModel.searchResultList` / `viewModel.searchResultCounts`
 * - `viewModel.lastQuery`
 * - `viewModel.initBook { ... }` / `viewModel.searchChapter(query, chapter)`
 *
 * 各字段直接转发 [shared] 同名字段 (var/val 委托 getter/setter),
 * 行为与原 app 端直接持有完全一致。
 *
 * # 简繁转换 lambda
 *
 * [chineseConverter] 入参 (type: Int, text: String):
 * - type=0 (不转换): 原样返回 text, 对应原 `else -> chapter.title`
 * - type=1 (t2s): 调 [ChineseUtils.t2s], 对应原 `1 -> ChineseUtils.t2s(chapter.title)`
 * - type=2 (s2t): 调 [ChineseUtils.s2t], 对应原 `2 -> ChineseUtils.s2t(chapter.title)`
 *
 * shared 内部根据 [io.legado.app.help.config.AppConfigProviders.get].chineseConverterType
 * 读 type 后传给本 lambda, app 端 lambda 按 type 分发, 行为与原 `when` 完全一致。
 */
class SearchContentViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope] 与简繁转换 lambda。
     */
    private val shared: SearchContentViewModelShared = SearchContentViewModelShared(
        scope = viewModelScope,
        chineseConverter = { type, text ->
            when (type) {
                1 -> ChineseUtils.t2s(text)
                2 -> ChineseUtils.s2t(text)
                else -> text
            }
        },
    )

    val bookUrl: String
        get() = shared.bookUrl

    val book: Book?
        get() = shared.book

    var lastQuery: String
        get() = shared.lastQuery
        set(value) {
            shared.lastQuery = value
        }

    var searchResultCounts: Int
        get() = shared.searchResultCounts
        set(value) {
            shared.searchResultCounts = value
        }

    val cacheChapterNames: MutableSet<String>
        get() = shared.cacheChapterNames

    val searchResultList: MutableList<SearchResult>
        get() = shared.searchResultList

    var replaceEnabled: Boolean
        get() = shared.replaceEnabled
        set(value) {
            shared.replaceEnabled = value
        }

    /**
     * 从 [io.legado.app.help.IntentData.book] 初始化当前书籍。
     *
     * 转发到 [shared.initBook], 内部用 [viewModelScope].launch 编排,
     * success 回调在主线程执行, 与原 BaseViewModel.execute.onSuccess 行为一致。
     */
    fun initBook(success: () -> Unit) {
        shared.initBook(success)
    }

    /**
     * 在指定章节内搜索 query, 返回该章节内所有匹配结果。
     *
     * 转发到 [shared.searchChapter], 行为与原完全一致 (详见 shared KDoc)。
     */
    suspend fun searchChapter(
        query: String,
        chapter: BookChapter
    ): List<SearchResult> = shared.searchChapter(query, chapter)

}
