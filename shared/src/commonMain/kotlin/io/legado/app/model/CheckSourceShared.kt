package io.legado.app.model

import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.source.SourceCacheProvider
import io.legado.app.help.source.SourceCacheProviders
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.script.ScriptException
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * 书源校验配置核心 (shared commonMain 下沉版)。
 *
 * # 背景
 *
 * 对照 app 端原 `io.legado.app.model.CheckSource` object:
 * - 全部 var 状态 (keyword/timeout/checkSearch/checkDiscovery/checkInfo/checkCategory/
 *   checkContent) + `putConfig()` + `summary`/`upSummary()` 仅依赖 CacheManager + appString,
 *   可以下沉 commonMain 供多端复用。
 * - CacheManager 本身依赖 appDb.cacheDao + ACache + LruCache, 留 app 端; 但 shared commonMain
 *   已有 [SourceCacheProvider] 接口 (app 端 JsEnginesAndroid 注册时委托给 CacheManager),
 *   通过 `provider.get(key)` / `provider.put(key, value.toString())` 即可派生出
 *   `getLong`/`getBoolean`/`putLong`/`putBoolean`, 行为与原 `CacheManager.getLong/get/put`
 *   完全一致 (CacheManager.put 内部本就是 `value.toString()` 后插入 cacheDao)。
 * - `appString(AppStringKey.xxx)` 已在 shared commonMain (i18n/AppStrings.kt) 下沉。
 *
 * # 不下沉部分 (Android 特有)
 *
 * - `start(context, sources)` / `stop(context)` / `resume(context)`: 依赖 Android
 *   `Context.startService<CheckSourceService>` + IntentAction, 留 app 端 CheckSource object,
 *   app 端调用方 `CheckSource.start(...)` 写法不变。
 *
 * # 状态同步
 *
 * app 端 CheckSource object 改为薄壳: var/get/set/putConfig/summary 全部委托给本 object,
 * start/stop/resume 保留。app 端调用方 `CheckSource.timeout` 等代码零改动。
 *
 * 模式参考 [io.legado.app.help.source.SourceHelp] (shared 下沉 + app 薄壳)。
 */
object CheckSourceShared {

    /** 校验关键词 (用户可改, 默认 "我的")。 */
    var keyword = "我的"

    /**
     * 校验超时时间 (毫秒, 默认 180000)。
     *
     * getter 每次读 CacheManager: object 初始化早于 [SourceCacheProviders] 注册, 若在初始化时
     * 求值会永久锁死默认值; 用户改过 (setter) 则以内存值为准。以下五个开关同理。
     */
    var timeout: Long
        get() = timeoutOverride ?: cacheGetLong("checkSourceTimeout") ?: 180000L
        set(value) {
            timeoutOverride = value
        }
    private var timeoutOverride: Long? = null

    /** 是否校验搜索 (默认 true)。 */
    var checkSearch: Boolean
        get() = checkSearchOverride ?: cacheGetBoolean("checkSearch") ?: true
        set(value) {
            checkSearchOverride = value
        }
    private var checkSearchOverride: Boolean? = null

    /** 是否校验发现 (默认 true)。 */
    var checkDiscovery: Boolean
        get() = checkDiscoveryOverride ?: cacheGetBoolean("checkDiscovery") ?: true
        set(value) {
            checkDiscoveryOverride = value
        }
    private var checkDiscoveryOverride: Boolean? = null

    /** 是否校验详情 (默认 true)。 */
    var checkInfo: Boolean
        get() = checkInfoOverride ?: cacheGetBoolean("checkInfo") ?: true
        set(value) {
            checkInfoOverride = value
        }
    private var checkInfoOverride: Boolean? = null

    /** 是否校验目录 (默认 true)。 */
    var checkCategory: Boolean
        get() = checkCategoryOverride ?: cacheGetBoolean("checkCategory") ?: true
        set(value) {
            checkCategoryOverride = value
        }
    private var checkCategoryOverride: Boolean? = null

    /** 是否校验正文 (默认 true)。 */
    var checkContent: Boolean
        get() = checkContentOverride ?: cacheGetBoolean("checkContent") ?: true
        set(value) {
            checkContentOverride = value
        }
    private var checkContentOverride: Boolean? = null

    /** 配置摘要 (供 OtherConfigHost 显示)。 */
    val summary: String get() = upSummary()

    /**
     * 持久化配置到 CacheManager (对应原 `CheckSource.putConfig()`)。
     *
     * 通过 [SourceCacheProvider].put 写入 cacheDao, 行为与原 `CacheManager.put(key, value)`
     * 完全一致 (CacheManager.put 内部本就是 `value.toString()` 后 insert Cache)。
     */
    fun putConfig() {
        cachePut("checkSourceTimeout", timeout)
        cachePut("checkSearch", checkSearch)
        cachePut("checkDiscovery", checkDiscovery)
        cachePut("checkInfo", checkInfo)
        cachePut("checkCategory", checkCategory)
        cachePut("checkContent", checkContent)
    }

    /**
     * 拼接校验项摘要 (对应原 `CheckSource.upSummary()`)。
     *
     * 行为与原完全一致: 按顺序拼接 search/discovery/source_tab_info/chapter_list/main_body,
     * 最后格式化为 `(timeout秒) checkItem` 字符串。
     */
    private fun upSummary(): String {
        var checkItem = ""
        if (checkSearch) checkItem = "$checkItem ${appString(AppStringKey.search)}"
        if (checkDiscovery) checkItem = "$checkItem ${appString(AppStringKey.discovery)}"
        if (checkInfo) checkItem = "$checkItem ${appString(AppStringKey.source_tab_info)}"
        if (checkCategory) checkItem = "$checkItem ${appString(AppStringKey.chapter_list)}"
        if (checkContent) checkItem = "$checkItem ${appString(AppStringKey.main_body)}"
        return appString(
            AppStringKey.check_source_config_summary,
            (timeout / 1000).toString(),
            checkItem
        )
    }

    // ---- CacheManager 派生访问 (通过 SourceCacheProvider 间接, 与原 CacheManager.getLong/get/put 等价) ----

    /**
     * 从 CacheManager 读取 Long (对应 `CacheManager.getLong(key)`)。
     *
     * [SourceCacheProvider].get 返回 String?, `toLongOrNull()` 派生 Long?。
     * provider 未注册时 (测试/工具场景) 返回 null, 由调用方回落到默认值。
     */
    private fun cacheGetLong(key: String): Long? =
        SourceCacheProviders.impl?.get(key)?.toLongOrNull()

    /**
     * 从 CacheManager 读取 Boolean (对应 `CacheManager.get(key)?.toBoolean()`)。
     */
    private fun cacheGetBoolean(key: String): Boolean? =
        SourceCacheProviders.impl?.get(key)?.toBoolean()

    /**
     * 写入 CacheManager (对应 `CacheManager.put(key, value)`)。
     *
     * 任意类型 value 转 String 后 put, 与原 `CacheManager.put` 内部
     * `Cache(key, value.toString(), deadline=0)` + `cacheDao.insert` 行为一致。
     */
    private fun cachePut(key: String, value: Any) {
        SourceCacheProviders.impl?.put(key, value.toString())
    }

    // ---- 业务方法 (从 app 端 CheckSourceService 下沉) ----

    /**
     * 校验单个书源 (业务入口, 对应原 CheckSourceService.checkSource)。
     *
     * 内部执行:
     * 1. `withTimeout([timeout])` 调用 [doCheckSource]
     * 2. 成功: `Debug.updateFinalMessage` 写入 "校验成功"
     * 3. 失败: 按 TimeoutCancellationException / ScriptException / 其他分类 addGroup
     *    + addErrorComment + updateFinalMessage
     * 4. 末尾同步 `source.respondTime`
     *
     * 调用方 (app 端 CheckSourceService) 仅负责并发调度 + Notification, 业务流程在本方法内完成。
     */
    suspend fun checkSource(source: BookSource) {
        kotlin.runCatching {
            withTimeout(timeout) {
                doCheckSource(source)
            }
        }.onSuccess {
            Debug.updateFinalMessage(source.bookSourceUrl, "校验成功")
        }.onFailure {
            currentCoroutineContext().ensureActive()
            when (it) {
                is TimeoutCancellationException -> source.addGroup("校验超时")
                is ScriptException -> source.addGroup("js失效")
                !is NoStackTraceException -> source.addGroup("网站失效")
            }
            source.addErrorComment(it)
            Debug.updateFinalMessage(source.bookSourceUrl, "校验失败:${it.localizedMessage}")
        }
        source.respondTime = Debug.getRespondTime(source.bookSourceUrl)
    }

    /**
     * 执行实际校验流程 (对应原 CheckSourceService.doCheckSource)。
     *
     * 顺序: startChecking → 清理标记 → 校验搜索 → 校验发现 → 检查无效分组并抛出。
     */
    private suspend fun doCheckSource(source: BookSource) {
        Debug.startChecking(source)
        source.removeInvalidGroups()
        source.removeErrorComment()
        //校验搜索书籍
        if (checkSearch) {
            val searchWord = source.getCheckKeyword(keyword)
            if (!source.searchUrl.isNullOrBlank()) {
                source.removeGroup("搜索链接规则为空")
                val searchBooks = WebBook.getBookListAwait(source, searchWord).books
                if (searchBooks.isEmpty()) {
                    source.addGroup("搜索失效")
                } else {
                    source.removeGroup("搜索失效")
                    checkBook(searchBooks.first().toBook(), source)
                }
            } else {
                source.addGroup("搜索链接规则为空")
            }
        }
        //校验发现书籍
        if (checkDiscovery && !source.exploreUrl.isNullOrBlank()) {
            val url = source.exploreKinds().firstOrNull {
                !it.url.isNullOrBlank()
            }?.url
            if (url.isNullOrBlank()) {
                source.addGroup("发现规则为空")
            } else {
                source.removeGroup("发现规则为空")
                val exploreBooks = WebBook.getBookListAwait(source, url, isSearch = false).books
                if (exploreBooks.isEmpty()) {
                    source.addGroup("发现失效")
                } else {
                    source.removeGroup("发现失效")
                    checkBook(exploreBooks.first().toBook(), source, false)
                }
            }
        }
        val finalCheckMessage = source.getInvalidGroupNames()
        if (finalCheckMessage.isNotBlank()) {
            throw NoStackTraceException(finalCheckMessage)
        }
    }

    /**
     * 校验书源的详情/目录/正文 (对应原 CheckSourceService.checkBook)。
     *
     * 按 checkInfo/checkCategory/checkContent 开关分别校验, 失败时按 bookType 给 addGroup。
     */
    private suspend fun checkBook(book: Book, source: BookSource, isSearchBook: Boolean = true) {
        kotlin.runCatching {
            if (!checkInfo) {
                return
            }
            //校验详情
            if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, book)
            }
            if (!checkCategory || source.bookSourceType == BookSourceType.file) {
                return
            }
            //校验目录
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow().asSequence()
                .filter { !(it.isVolume && it.url.startsWith(it.title)) }
                .take(2)
                .toList()
            val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
            if (!checkContent) {
                return
            }
            //校验正文
            WebBook.getContentAwait(
                bookSource = source,
                book = book,
                bookChapter = toc.first(),
                nextChapterUrl = nextChapterUrl,
                needSave = false
            )
        }.onFailure {
            val bookType = if (isSearchBook) "搜索" else "发现"
            when (it) {
                is ContentEmptyException -> source.addGroup("${bookType}正文失效")
                is TocEmptyException -> source.addGroup("${bookType}目录失效")
                else -> throw it
            }
        }.onSuccess {
            val bookType = if (isSearchBook) "搜索" else "发现"
            source.removeGroup("${bookType}目录失效")
            source.removeGroup("${bookType}正文失效")
        }
    }
}
