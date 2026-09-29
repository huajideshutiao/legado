package io.legado.app.model.analyzeRule

import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapterLike
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.BookInfoRefreshers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 桌面端 AnalyzeRule 薄子类: 继承 shared [AnalyzeRuleCore], JS 面与 app 端 AnalyzeRule 对齐
 * (JsExtensionsCommon 全量面)。
 */
@Suppress("unused")
class DesktopAnalyzeRule(
    ruleData: RuleDataInterface? = null,
    source: BaseSource? = null,
    preUpdateJs: Boolean = false
) : AnalyzeRuleCore(ruleData, source, preUpdateJs) {

    /** 与 app 端 AnalyzeRule.refreshTocUrl 同逻辑: 经 [BookInfoRefreshers] 反向调用 WebBook.getBookInfoAwait。 */
    override fun refreshTocUrl() {
        val bookSource = getSource() as? BookSource
        val book = ruleData as? Book
        if (bookSource == null || book == null) return
        val refresher = BookInfoRefreshers.getOrNull() ?: return
        runBlocking(coroutineContext) {
            withTimeout(1800000) {
                refresher.refreshBookInfo(bookSource, book, false)
            }
        }
    }
}

/**
 * 桌面端 AnalyzeUrl 薄子类: 与 [DesktopAnalyzeRule] 同理,
 * url 内 `<js>` 的 java 绑定 JS 面与 app 端 AnalyzeUrl 对齐。
 */
@Suppress("unused")
class DesktopAnalyzeUrl(
    rawUrl: String,
    baseUrl: String = "",
    source: BaseSource? = null,
    ruleData: RuleDataInterface? = null,
    chapter: BookChapterLike? = null,
    readTimeout: Long? = null,
    callTimeout: Long? = null,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    headerMapF: Map<String, String>? = null,
    hasLoginHeader: Boolean = true,
    selectedOptions: Map<String, String>? = null,
    variables: Map<AppConst.JsVarName, Any>? = null
) : AnalyzeUrlCore(
    rawUrl, baseUrl, source, ruleData, chapter, readTimeout, callTimeout,
    coroutineContext, headerMapF, hasLoginHeader, selectedOptions, variables
) {
}

/** 桌面端 main 入口注册: shared 编排层创建 AnalyzeRule/AnalyzeUrl 改走桌面薄子类。 */
fun registerDesktopAnalyzeRuleFactory() {
    AnalyzeRuleFactories.register { ruleData, source, preUpdateJs ->
        DesktopAnalyzeRule(ruleData, source, preUpdateJs)
    }
    AnalyzeUrlFactories.register {
            rawUrl, baseUrl, source, ruleData, chapter, readTimeout, callTimeout,
            coroutineContext, headerMapF, hasLoginHeader, selectedOptions, variables ->
        DesktopAnalyzeUrl(
            rawUrl, baseUrl, source, ruleData, chapter, readTimeout, callTimeout,
            coroutineContext, headerMapF, hasLoginHeader, selectedOptions, variables
        )
    }
}
