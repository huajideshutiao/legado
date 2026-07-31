package io.legado.app.model.analyzeRule

import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapterLike
import io.legado.app.data.entities.BookSource
import io.legado.app.help.JsExtensionsJvm
import io.legado.app.model.webBook.BookInfoRefreshers
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.URL
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 桌面端 AnalyzeRule 薄子类: 继承 shared [JsExtensionsJvm], JS 面与 app 端 AnalyzeRule 对齐
 * (JsEncodeUtils 摘要/加解密工厂 + jsoup get/head/post + JsExtensionsCommon 全量面)。
 *
 * 放 shared jvmMain 而非 desktop 模块: hutool 在 shared 是 implementation 依赖,
 * desktop 模块编译期不可见, setIv 等 hutool 父类成员只能在 shared 内部引用。
 */
@Suppress("unused")
class DesktopAnalyzeRule(
    ruleData: RuleDataInterface? = null,
    source: BaseSource? = null,
    preUpdateJs: Boolean = false
) : AnalyzeRuleCore(ruleData, source, preUpdateJs), JsExtensionsJvm {

    /** 恢复原版 java.net.URL 重载路径, 避免 String 门面 substringBefore(",") 截断含逗号的 redirectUrl。 */
    override fun getAbsoluteURL(redirectUrl: URL?, relativePath: String): String {
        return NetworkUtils.getAbsoluteURL(redirectUrl, relativePath)
    }

    /**
     * 与 app 端 AnalyzeRule.ajax 同处理: 解析 diamond 继承冲突,
     * 显式选择 [AnalyzeRuleCore.ajax] (传递 ruleData 给 [AnalyzeUrlCore], 行为更完整)。
     */
    override fun ajax(url: Any): String? = super<AnalyzeRuleCore>.ajax(url)

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
 * 桌面端 AnalyzeUrl 薄子类: 与 [DesktopAnalyzeRule] 同理, 继承 shared [JsExtensionsJvm],
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
), JsExtensionsJvm {
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
