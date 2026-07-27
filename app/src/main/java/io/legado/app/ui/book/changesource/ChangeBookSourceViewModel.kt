package io.legado.app.ui.book.changesource

import android.app.Application
import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 换源 ViewModel (Android 端)。
 *
 * # KMP 化重构说明
 *
 * 核心业务编排 (书源加载 / 并发搜索 / 排序 / 字数加载 / 切源 / 评分 / 置顶置底 / 禁用删除 /
 * 自动换源 / 刷新列表 / 筛选) 已下沉到 shared commonMain [ChangeBookSourceViewModelShared],
 * 用 `StateFlow<Boolean>` 替代 `MutableLiveData<Boolean>` (LiveData 不可 KMP)。
 *
 * 本类采用**组合委托**模式持有 [shared] 实例, 不通过继承 `ChangeBookSourceViewModelShared`:
 * - 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` / `viewModelScope`),
 *   Kotlin 单继承无法同时继承 [ChangeBookSourceViewModelShared];
 * - 平台专属逻辑通过 [ChangeBookSourcePlatform] 聚合接口注入 [shared]:
 *   - [AndroidChangeBookSourcePlatform] 包装 `AppConfig` (4 个 changeSource* 开关 + threadCount +
 *     searchGroup 读写) / `ContentProcessor.getContent` / `BookHelp.getDurChapter` /
 *     `SourceConfig` 评分 3 方法 / `context.toastOnUi`;
 * - 仅 1 个聚合接口参数, 不违反"避免超多继承与参数传递"原则。
 *
 * # 调用方兼容
 *
 * [ChangeBookSourceDialog] 调用方式保持不变:
 * - `viewModel.initData(arguments, oldBook, fromReadBookActivity)` (Bundle 解析在本类做)
 * - `viewModel.searchFinishCallback = ...` (var 属性转发)
 * - `viewModel.searchStateData.observe(...)` (LiveData, 订阅 shared.searchState 转发)
 * - `viewModel.searchDataFlow` (Flow, 直接转发)
 * - `viewModel.changeSourceProgress` (StateFlow, 直接转发)
 * - `viewModel.totalSourceCount` / `viewModel.name` / `viewModel.author` (getter 转发)
 * - `viewModel.bookMap` (直接转发)
 * - `viewModel.startSearch() / startSearch(origin) / refresh() / screen(key) / ...` (方法转发)
 * - `viewModel.getToc(book, onSuccess, onError): Coroutine` (方法转发, 返回 Coroutine 供 cancel)
 * - `viewModel.autoChangeSource(type) { book, toc, source -> ... }` (方法转发)
 *
 * # 状态桥接
 *
 * [searchStateData] 仍是 `LiveData<Boolean>` (供 Dialog observe), 内部用 [viewModelScope]
 * 协程订阅 [shared.searchState] (StateFlow) 转发到 MutableLiveData:
 * - StateFlow 是 hot flow, collect 时立即收到当前值, 不会错过搜索状态切换
 * - `postValue` 异步切到主线程, 与原 LiveData.postValue 行为一致
 * - 不用 `androidx.lifecycle.asLiveData()` 扩展: 项目未显式引入 lifecycle-livedata-ktx,
 *   用 viewModelScope.launch + collect 自己桥接确保编译通过
 *
 * # ChangeChapterSourceViewModel 兼容
 *
 * [ChangeChapterSourceViewModel] 继承本类, 覆盖 initData 调用 super.initData 添加
 * chapterIndex/chapterTitle 解析。本类 initData 保留 `(Bundle?, Book?, Boolean)` 签名,
 * 解析 Bundle 后转发到 [shared.initData] (name, author, fromReadBookActivity, oldBook),
 * 子类签名不变。
 */
@Suppress("MemberVisibilityCanBePrivate")
open class ChangeBookSourceViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope] 与 [AndroidChangeBookSourcePlatform]。
     *
     * 平台专属依赖通过聚合接口注入, 避免在 commonMain 硬编码 AppConfig / ContentProcessor /
     * BookHelp / SourceConfig / toastOnUi (全部 Android 专属)。
     *
     * 访问可见性: `protected` 供子类 [ChangeChapterSourceViewModel] 直接转发
     * `chapterIndex` / `chapterTitle` / `getContent` / `initData` 6 参数重载 等
     * 章节换源专属能力 (已下沉到 shared)。
     */
    protected val shared: ChangeBookSourceViewModelShared = ChangeBookSourceViewModelShared(
        scope = viewModelScope,
        platform = AndroidChangeBookSourcePlatform(),
    )

    /**
     * 搜索中状态 LiveData, 暴露给 [ChangeBookSourceDialog] observe。
     *
     * 内部订阅 [shared.searchState] (StateFlow<Boolean>) 转发到 MutableLiveData:
     * - StateFlow 启动 collect 时立即收到当前值, 不会错过搜索状态切换;
     * - `postValue` 异步切到主线程, 与原 LiveData.postValue 行为一致。
     *
     * 注: 不用 `androidx.lifecycle.asLiveData()` 扩展 (项目未显式引入
     * lifecycle-livedata-ktx), 用 viewModelScope.launch + collect 自己桥接。
     */
    val searchStateData = MutableLiveData<Boolean>()

    /**
     * 搜索完成回调, 转发到 [shared.searchFinishCallback]。
     *
     * Dialog 在 onViewCreated 设置, onDestroy 置 null。
     */
    var searchFinishCallback: ((isEmpty: Boolean) -> Unit)?
        get() = shared.searchFinishCallback
        set(value) {
            shared.searchFinishCallback = value
        }

    /** 书名, 转发到 [shared.name]。 */
    val name: String get() = shared.name

    /** 作者, 转发到 [shared.author]。 */
    val author: String get() = shared.author

    /** 书源总数, 转发到 [shared.totalSourceCount]。 */
    val totalSourceCount: Int get() = shared.totalSourceCount

    /** 已加载详情的书籍缓存, 转发到 [shared.bookMap]。Dialog.changeSource 取 book 用。 */
    val bookMap: MutableMap<String, Book> get() = shared.bookMap

    /**
     * 换源进度流, 转发到 [shared.changeSourceProgress]。
     *
     * 原本就是 StateFlow, 直接转发即可 (Dialog 用 drop(1).collect 显示进度)。
     */
    val changeSourceProgress = shared.changeSourceProgress

    /**
     * 搜索结果 Flow, 转发到 [shared.searchDataFlow]。
     *
     * Dialog 用 `conflate().collect { items = it }` 消费。
     */
    val searchDataFlow: Flow<List<SearchBook>> get() = shared.searchDataFlow

    init {
        // 订阅 shared.searchState (StateFlow), 把变化推到 searchStateData (LiveData)
        // 一次性订阅, viewModelScope cancel 时自动结束
        viewModelScope.launch {
            shared.searchState.collect { searching ->
                searchStateData.postValue(searching)
            }
        }
    }

    /**
     * 初始化数据 (从 Bundle 解析 name/author)。
     *
     * 保留原 `(Bundle?, Book?, Boolean)` 签名以兼容 [ChangeChapterSourceViewModel] 覆盖。
     * 解析 Bundle 后转发到 [shared.initData] (name, author, fromReadBookActivity, oldBook)。
     *
     * - name: 直接取 bundle.getString("name")
     * - author: 取后用 [AppPattern.authorRegex] 去除非法字符
     * - fromReadBookActivity: 由调用方传入 (activity is ReadBookActivity)
     * - oldBook: 由调用方传入 (callBack?.oldBook)
     *
     * @param arguments Bundle (含 name / author)
     * @param book 旧书
     * @param fromReadBookActivity 是否从阅读页进入
     */
    @CallSuper
    open fun initData(arguments: Bundle?, book: Book?, fromReadBookActivity: Boolean) {
        arguments?.let { bundle ->
            val name = bundle.getString("name") ?: ""
            val author = bundle.getString("author")?.replace(AppPattern.authorRegex, "") ?: ""
            shared.initData(name, author, fromReadBookActivity, book)
        }
    }

    /** 刷新筛选, 转发到 [shared.refresh]。 */
    fun refresh(): Boolean = shared.refresh()

    /** 启动搜索, 转发到 [shared.startSearch]。 */
    fun startSearch() = shared.startSearch()

    /** 启动单源搜索, 转发到 [shared.startSearch]。 */
    fun startSearch(origin: String) = shared.startSearch(origin)

    /** 字数开关变化回调, 转发到 [shared.onLoadWordCountChecked]。 */
    fun onLoadWordCountChecked(isChecked: Boolean) = shared.onLoadWordCountChecked(isChecked)

    /** 刷新列表, 转发到 [shared.startRefreshList]。 */
    fun startRefreshList(onlyRefreshNoWordCountBook: Boolean = false) =
        shared.startRefreshList(onlyRefreshNoWordCountBook)

    /** 筛选, 转发到 [shared.screen]。 */
    fun screen(key: String?) = shared.screen(key)

    /** 启停切换, 转发到 [shared.startOrStopSearch]。 */
    fun startOrStopSearch() = shared.startOrStopSearch()

    /** 停止搜索, 转发到 [shared.stopSearch]。 */
    fun stopSearch() = shared.stopSearch()

    /**
     * 获取目录 (带回调), 转发到 [shared.getToc]。
     *
     * 返回 [Coroutine] 供调用方 cancel (Dialog.waitDialog.onCancelListener)。
     */
    fun getToc(
        book: Book,
        onSuccess: (toc: List<BookChapter>, source: BookSource) -> Unit,
        onError: (e: Throwable) -> Unit
    ): Coroutine<Pair<List<BookChapter>, BookSource>> =
        shared.getToc(book, onSuccess, onError)

    /** 禁用书源, 转发到 [shared.disableSource]。 */
    fun disableSource(searchBook: SearchBook) = shared.disableSource(searchBook)

    /** 置顶书源, 转发到 [shared.topSource]。 */
    fun topSource(searchBook: SearchBook) = shared.topSource(searchBook)

    /** 置底书源, 转发到 [shared.bottomSource]。 */
    fun bottomSource(searchBook: SearchBook) = shared.bottomSource(searchBook)

    /** 删除书源, 转发到 [shared.del]。 */
    fun del(searchBook: SearchBook) = shared.del(searchBook)

    /** 自动换源, 转发到 [shared.autoChangeSource]。 */
    fun autoChangeSource(
        bookType: Int?, onSuccess: (book: Book, toc: List<BookChapter>, source: BookSource) -> Unit
    ) = shared.autoChangeSource(bookType, onSuccess)

    /** 设置书源评分, 转发到 [shared.setBookScore]。 */
    fun setBookScore(searchBook: SearchBook, score: Int) = shared.setBookScore(searchBook, score)

    /** 获取书源评分, 转发到 [shared.getBookScore]。 */
    fun getBookScore(searchBook: SearchBook): Int = shared.getBookScore(searchBook)

    /**
     * ViewModel 销毁回调, 转发到 [shared.onCleared] 关闭 searchPool。
     */
    override fun onCleared() {
        super.onCleared()
        shared.onCleared()
    }

    /**
     * Android 端 [ChangeBookSourcePlatform] 实现: 包装 [AppConfig] / [ContentProcessor] /
     * [BookHelp] / [SourceConfig] / [context.toastOnUi]。
     *
     * 内部类形式, 直接访问外类 [context] (BaseViewModel 提供)。
     */
    private inner class AndroidChangeBookSourcePlatform : ChangeBookSourcePlatform {

        // ---- AppConfig 相关 ----

        override val threadCount: Int
            get() = AppConfig.threadCount

        /**
         * searchGroup 用 var 实现: getter 读 [AppConfig.searchGroup],
         * setter 写 [AppConfig.searchGroup] (持久化到 SharedPreferences)。
         *
         * 注: [setSearchGroup] 默认实现已调 `searchGroup = value` 触发 setter, 无需额外覆盖。
         */
        override var searchGroup: String
            get() = AppConfig.searchGroup
            set(value) {
                AppConfig.searchGroup = value
            }

        /**
         * changeSourceCheckAuthor: getter 读 [AppConfig.changeSourceCheckAuthor],
         * setter 写 [AppConfig.changeSourceCheckAuthor] (持久化到 SharedPreferences)。
         *
         * app 端 Dialog 直接 `AppConfig.changeSourceCheckAuthor = value` 写回, 走此 setter。
         */
        override var changeSourceCheckAuthor: Boolean
            get() = AppConfig.changeSourceCheckAuthor
            set(value) {
                AppConfig.changeSourceCheckAuthor = value
            }

        override var changeSourceLoadInfo: Boolean
            get() = AppConfig.changeSourceLoadInfo
            set(value) {
                AppConfig.changeSourceLoadInfo = value
            }

        override var changeSourceLoadToc: Boolean
            get() = AppConfig.changeSourceLoadToc
            set(value) {
                AppConfig.changeSourceLoadToc = value
            }

        override var changeSourceLoadWordCount: Boolean
            get() = AppConfig.changeSourceLoadWordCount
            set(value) {
                AppConfig.changeSourceLoadWordCount = value
            }

        // ---- BookHelp 相关 ----

        /** 委托 [BookHelp.getDurChapter], 章节名相似度匹配定位当前章节。 */
        override fun getDurChapter(oldBook: Book, chapters: List<BookChapter>): Int {
            return BookHelp.getDurChapter(oldBook, chapters)
        }

        // ---- ContentProcessor 相关 ----

        /**
         * 委托 [ContentProcessor.get].getContent, 走完整正文处理
         * (替换规则 / 简繁 / 重排段 / 去重复标题)。
         *
         * includeTitle 传 false, 与原 `contentProcessor.getContent(oldBook, chapter, content, false)` 一致。
         *
         * 注: [ContentProcessor.getContent] 返回 [BookContent] (非 CharSequence), 接口要求
         * [CharSequence]; [BookContent.toString] 实现为 `textList.joinToString("\n")`,
         * 与 shared 调用方 [ChangeBookSourceViewModelShared.loadWordCount] 中
         * `platform.processContent(...).toString()` 语义一致 (取拼接后的正文文本)。
         */
        override fun processContent(
            oldBook: Book, chapter: BookChapter, content: String, includeTitle: Boolean
        ): CharSequence {
            return ContentProcessor.get(oldBook).getContent(
                oldBook, chapter, content, includeTitle
            ).toString()
        }

        // ---- SourceConfig 评分相关 ----

        /** 委托 [SourceConfig.setBookScore], 持久化到 SharedPreferences。 */
        override fun setBookScore(origin: String, name: String, author: String, score: Int) {
            SourceConfig.setBookScore(origin, name, author, score)
        }

        /** 委托 [SourceConfig.getBookScore], 从 SharedPreferences 读。 */
        override fun getBookScore(origin: String, name: String, author: String): Int {
            return SourceConfig.getBookScore(origin, name, author)
        }

        /** 委托 [SourceConfig.getSourceScore], 从 SharedPreferences 读。 */
        override fun getSourceScore(origin: String): Int {
            return SourceConfig.getSourceScore(origin)
        }

        // ---- Toast 相关 ----

        /** 委托 [context.toastOnUi], 走 Android Toast。 */
        override fun toastOnUi(msg: String) {
            context.toastOnUi(msg)
        }
    }

    /**
     * 搜索回调接口 (保留供外部引用, 实际实现已下沉到 [ChangeBookSourceViewModelShared.SourceCallback])。
     *
     * 保留此类型别名以兼容历史代码 (若有外部引用), 实际类型为
     * [ChangeBookSourceViewModelShared.SourceCallback]。
     */
    interface SourceCallback : ChangeBookSourceViewModelShared.SourceCallback

}
