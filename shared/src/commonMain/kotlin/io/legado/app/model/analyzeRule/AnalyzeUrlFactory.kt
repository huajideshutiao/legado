package io.legado.app.model.analyzeRule

import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapterLike
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * AnalyzeUrl 实例工厂: shared 直接 new [AnalyzeUrlCore] 会缺失平台端 JS 扩展面
 * (createSymmetricCrypto 等加解密工厂、jsoup get/head/post 等), url 内 `<js>` 调
 * java.createSymmetricCrypto 报 not a function。各端启动早期注册返回平台薄子类
 * (app AnalyzeUrl / desktop DesktopAnalyzeUrl) 的工厂恢复完整面, 模式同 [AnalyzeRuleFactory]。
 */
fun interface AnalyzeUrlFactory {
    fun create(
        rawUrl: String,
        baseUrl: String,
        source: BaseSource?,
        ruleData: RuleDataInterface?,
        chapter: BookChapterLike?,
        readTimeout: Long?,
        callTimeout: Long?,
        coroutineContext: CoroutineContext,
        headerMapF: Map<String, String>?,
        hasLoginHeader: Boolean,
        selectedOptions: Map<String, String>?,
        variables: Map<AppConst.JsVarName, Any>?,
    ): AnalyzeUrlCore
}

/** 工厂容器 (照 [AnalyzeRuleFactories] 容器模式)。未注册端走默认裸 [AnalyzeUrlCore], 行为与现状一致。 */
object AnalyzeUrlFactories {

    @Volatile
    private var impl: AnalyzeUrlFactory = AnalyzeUrlFactory {
            rawUrl, baseUrl, source, ruleData, chapter, readTimeout, callTimeout,
            coroutineContext, headerMapF, hasLoginHeader, selectedOptions, variables ->
        AnalyzeUrlCore(
            rawUrl, baseUrl, source, ruleData, chapter, readTimeout, callTimeout,
            coroutineContext, headerMapF, hasLoginHeader, selectedOptions, variables
        )
    }

    /** 宿主启动早期注册一次 (任何书源解析之前)。 */
    fun register(impl: AnalyzeUrlFactory) {
        this.impl = impl
    }

    fun create(
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
        variables: Map<AppConst.JsVarName, Any>? = null,
    ): AnalyzeUrlCore = impl.create(
        rawUrl, baseUrl, source, ruleData, chapter, readTimeout, callTimeout,
        coroutineContext, headerMapF, hasLoginHeader, selectedOptions, variables
    )
}
