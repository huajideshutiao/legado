package io.legado.app.ui.book.searchContent

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfigProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * 书内全文搜索 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `SearchContentViewModel(application: Application) : BaseViewModel(application)`:
 * - 核心搜索逻辑 (initBook / searchChapter / searchPosition / getResultAndQueryIndex)
 *   不依赖 Android 专属 API, 仅依赖 [AppDbProviders] / [IntentData] /
 *   [BookStorageProviders] / [ContentProcessorProviders] / [AppConfigProviders] + 协程,
 *   可以下沉 commonMain 供多端复用。
 * - DAO 访问走 [AppDbProviders.get] (宿主启动时注册), 替代 app 端 `appDb` 单例。
 * - 章节正文缓存走 [BookStorageProviders.get].getContent, 替代 app 端 `BookHelp.getContent`
 *   (BookHelp 重 Android 依赖留 app 端, BookStorage 跨平台抽象)。
 * - 正文处理走 [ContentProcessorProviders.get].getContent, 替代
 *   app 端 `ContentProcessor.get(name, origin).getContent(...)`
 *   (ContentProcessor 重 Android 依赖留 app 端)。
 * - 简繁转换配置走 [AppConfigProviders.get].chineseConverterType, 替代
 *   app 端 `AppConfig.chineseConverterType`。
 * - [IntentData] 已下沉 commonMain, 直接复用跨 Activity 临时大数据传递容器。
 *
 * # 留 app 端实现的部分 (Android-specific)
 *
 * 简繁转换 [ChineseUtils.t2s] / [ChineseUtils.s2t] 通过 lambda 注入:
 * - app 端实现 `{ ChineseUtils.t2s(it) }` 或 `{ ChineseUtils.s2t(it) }`
 *   (ChineseUtils 主体依赖 quick-transfer 库 + 反射 + 字典缓存);
 * - 桌面端实现可走 shared jvmAndAndroidMain 的 ChineseUtils actual
 *   (本任务桌面端仅注入 no-op 桥接, 与桌面端简化策略一致);
 * - iOS/鸿蒙端可走 commonMain/iosMain/ohosMain 的 ChineseUtils actual。
 *
 * 不在 commonMain 直接 import ChineseUtils 以保持 ViewModelShared 与下层工具解耦,
 * 由宿主按需注入转换策略, 便于单测替换为 stub。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式:
 * - app 端 SearchContentViewModel `extends BaseViewModel`, 内部持有
 *   [SearchContentViewModelShared] 实例;
 * - 仅注入 [scope] + [chineseConverter] 两个参数 (Android = `viewModelScope` + ChineseUtils 实现);
 * - 转发 initBook / searchChapter 等方法到 shared;
 * - 暴露 book / bookUrl / replaceEnabled / searchResultList / searchResultCounts /
 *   cacheChapterNames / lastQuery 等可变状态字段供宿主读写 (Activity 直接访问模式不变)。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 * @param chineseConverter 平台专属: 章节标题简繁转换
 *   - 入参 (类型 Int): AppConfig.chineseConverterType 的值 (0=不转换, 1=t2s, 2=s2t);
 *     由 shared 内部根据 chineseConverterType 选择性调用, 实现端无需关心分支。
 *   - 入参 (原字符串): 待转换的章节标题
 *   - 返回值: 转换后的标题 (chineseConverterType=0 时实现端可原样返回)
 *   - 注: 为简化 lambda 签名, 实际签名改为 `(type: Int, text: String) -> String`,
 *     shared 内部按 type 分发; app 端用 when(type) 调用 t2s/s2t, type=0 时原样返回。
 */
class SearchContentViewModelShared(
    private val scope: CoroutineScope,
    private val chineseConverter: (type: Int, text: String) -> String,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl), 供 [searchAllChapters] 取章节列表。 */
    private val appDb get() = AppDbProviders.get()

    /** AppConfig 容器 (宿主启动时由 app 端注册 AppConfigAccessorImpl)。 */
    private val appConfig get() = AppConfigProviders.get()

    var bookUrl: String = ""
        private set

    var book: Book? = null
        private set

    var lastQuery: String = ""

    var searchResultCounts: Int = 0

    val cacheChapterNames = hashSetOf<String>()

    val searchResultList: MutableList<SearchResult> = mutableListOf()

    var replaceEnabled: Boolean = false

    /**
     * 从 [IntentData.book] 初始化当前书籍 + bookUrl。
     *
     * 对照原 SearchContentViewModel.initBook:
     * - `execute { ... }.onSuccess { ... }` 在 BaseViewModel 内部走 viewModelScope + IO,
     *   下沉后由 [scope].launch 编排; IntentData 访问是内存操作, 无需切 IO,
     *   在 scope 默认调度器 (Android=Main) 执行, 与原 execute 完成后切主线程回调等价。
     * - 原 `contentProcessor = ContentProcessor.get(it.name, it.origin)` 缓存 ContentProcessor
     *   实例的下沉等价: ContentProcessorProviders.get().getContent 内部自己缓存 (WebBookProvidersImpl
     *   委托 `ContentProcessor.get(book).getContent(...)`, 行为等价), 无需 shared 持有实例。
     *
     * @param success 初始化成功回调 (对应原 execute.onSuccess, 在 [scope] 上下文回调)
     */
    fun initBook(success: () -> Unit) {
        scope.launch {
            IntentData.book?.let {
                // 强转 Book (对照原 app 端 `it as Book`, IntentData.book 类型为 BaseBook?,
                // 实际运行场景中总是 Book 类型, 此处保持原强转行为不变)
                book = it as Book
                bookUrl = it.bookUrl
            }
            // onSuccess 回调: BaseViewModel.execute 在协程完成后切主线程回调,
            // shared 端在 scope 上下文回调 (Android=viewModelScope 默认 Main 调度器),
            // 与原行为等价。
            success.invoke()
        }
    }

    /**
     * 在指定章节内搜索 query, 返回该章节内所有匹配结果。
     *
     * 对照原 SearchContentViewModel.searchChapter:
     * 1. 取章节正文: `BookHelp.getContent(book, chapter)` →
     *    [BookStorageProviders.get].getContent (跨平台抽象, 行为等价);
     *    正文为空 (未缓存/读不到) 返回空列表, 与原一致。
     * 2. ensureActive: 协程取消检查, 与原一致。
     * 3. 章节标题简繁转换: 原 `ChineseUtils.t2s/s2t(chapter.title)` →
     *    [chineseConverter] lambda 注入, 由宿主实现具体转换;
     *    type=0 (不转换) 时由宿主原样返回, 行为与原 else 分支一致。
     * 4. ensureActive: 协程取消检查, 与原一致。
     * 5. 正文处理: `contentProcessor!!.getContent(book, chapter, content, useReplace = replaceEnabled)`
     *    → [ContentProcessorProviders.get].getContent(book, chapter, content, useReplace)
     *    (WebBookProvidersImpl 委托 `ContentProcessor.get(book).getContent(...)`, 行为等价)。
     * 6. 正则搜索: [searchPosition] + [getResultAndQueryIndex] (纯函数, 直接复用)。
     * 7. 累加 searchResultCounts (与原一致)。
     *
     * @param query 搜索关键词 (子串匹配, 非正则)
     * @param chapter 待搜索章节
     * @return 该章节内所有匹配结果 (空列表表示无匹配或正文为空)
     */
    suspend fun searchChapter(
        query: String,
        chapter: BookChapter
    ): List<SearchResult> {
        val searchResultsWithinChapter: MutableList<SearchResult> = mutableListOf()
        val book = book ?: return searchResultsWithinChapter
        val chapterContent = BookStorageProviders.get().getContent(book, chapter)
            ?: return searchResultsWithinChapter
        currentCoroutineContext().ensureActive()
        chapter.title = chineseConverter(appConfig.chineseConverterType, chapter.title)
        currentCoroutineContext().ensureActive()
        val mContent = ContentProcessorProviders.get().getContent(
            book, chapter, chapterContent, useReplace = replaceEnabled
        ).toString()
        val positions = searchPosition(mContent, query)
        positions.forEachIndexed { index, position ->
            currentCoroutineContext().ensureActive()
            val construct = getResultAndQueryIndex(mContent, position, query)
            val result = SearchResult(
                resultCountWithinChapter = index,
                resultText = construct.second,
                chapterTitle = chapter.title,
                query = query,
                chapterIndex = chapter.index,
                queryIndexInResult = construct.first,
                queryIndexInChapter = position
            )
            searchResultsWithinChapter.add(result)
        }
        searchResultCounts += searchResultsWithinChapter.size
        return searchResultsWithinChapter
    }

    /**
     * 在所有已缓存章节内搜索 query, 返回所有匹配结果。
     *
     * 对照原 SearchContentActivity.startContentSearch 的搜索编排逻辑 (下沉到 shared):
     * 1. `appDb.bookChapterDao.getChapterList(bookUrl)` 获取所有章节;
     * 2. 遍历章节, 跳过未缓存章节 (非本地书且 [cacheChapterNames] 不含章节文件名),
     *    与原 `if (isLocalBook || viewModel.cacheChapterNames.contains(...))` 一致;
     * 3. 对每个已缓存章节调用 [searchChapter];
     * 4. 累加非空结果到 [searchResultList] + 通过 [onResults] 回调通知调用方增量更新 UI;
     * 5. 返回所有结果总和 ([searchResultList] 的快照)。
     *
     * UI 状态 (searching 标志 / 空结果提示 / 错误处理) 由调用方管理,
     * 本方法仅负责搜索编排 + 结果累加, 与原 Activity 职责划分一致。
     *
     * 协程取消: 每次循环 [ensureActive] 检查, 与原 `ensureActive()` 一致;
     * 调用方取消协程即可中止整个搜索流程。
     *
     * @param query 搜索关键词 (子串匹配, 非正则)
     * @param onResults 每章搜索完成回调 (入参为该章的非空结果), 供 UI 增量更新;
     *   默认 no-op, 调用方可不传
     * @return 所有章节的搜索结果总和 (已累加到 [searchResultList], 返回其快照)
     */
    suspend fun searchAllChapters(
        query: String,
        onResults: (List<SearchResult>) -> Unit = {}
    ): List<SearchResult> {
        val book = book ?: return emptyList()
        // isLocal 书籍所有章节都视为已缓存 (与原 Activity.isLocalBook 判断一致)
        val isLocalBook = book.isLocal
        appDb.bookChapterDao.getChapterList(bookUrl).forEach { chapter ->
            currentCoroutineContext().ensureActive()
            val results = if (isLocalBook || cacheChapterNames.contains(chapter.getFileName())) {
                searchChapter(query, chapter)
            } else {
                emptyList()
            }
            currentCoroutineContext().ensureActive()
            if (results.isNotEmpty()) {
                searchResultList.addAll(results)
                onResults(results)
            }
        }
        return searchResultList.toList()
    }

    /**
     * 在 content 中查找 pattern 所有出现位置 (子串匹配, 非正则)。
     *
     * 对照原 SearchContentViewModel.searchPosition: 纯函数, 直接复用。
     * 每次循环 ensureActive 检查协程取消 (与原一致)。
     */
    private suspend fun searchPosition(content: String, pattern: String): List<Int> {
        val position: MutableList<Int> = mutableListOf()
        var index = content.indexOf(pattern)
        while (index >= 0) {
            currentCoroutineContext().ensureActive()
            position.add(index)
            index = content.indexOf(pattern, index + pattern.length)
        }
        return position
    }

    /**
     * 截取 queryIndexInContent 周围 length 个字符作为搜索结果展示文本,
     * 并返回 (queryIndexInResult, resultText)。
     *
     * 对照原 SearchContentViewModel.getResultAndQueryIndex: 纯函数, 直接复用。
     * 左右各移动 length (=20) 个字符, 越界自动夹到 0/content.length。
     */
    private fun getResultAndQueryIndex(
        content: String,
        queryIndexInContent: Int,
        query: String
    ): Pair<Int, String> {
        // 左右移动20个字符，构建关键词周边文字，在搜索结果里显示
        // 判断段落，只在关键词所在段落内分割
        // 利用标点符号分割完整的句
        // length和设置结合，自由调整周边文字长度
        val length = 20
        var po1 = queryIndexInContent - length
        var po2 = queryIndexInContent + query.length + length
        if (po1 < 0) {
            po1 = 0
        }
        if (po2 > content.length) {
            po2 = content.length
        }
        val queryIndexInResult = queryIndexInContent - po1
        val newText = content.substring(po1, po2)
        return queryIndexInResult to newText
    }

}
