package io.legado.app.model.analyzeRule

import io.legado.app.data.entities.BaseSource
import kotlin.concurrent.Volatile

/**
 * AnalyzeRule 实例工厂: shared webBook 编排层直接 new [AnalyzeRuleCore] 会缺失平台端的规则 override
 * (app 端 AnalyzeRule / desktop 端 DesktopAnalyzeRule 的 refreshTocUrl 等)。
 * JS 扩展面 (ajax/get/head/post/加解密) 由 [JsExtensionsCommon] 默认实现承载, 不依赖平台子类。
 * 各端启动早期注册返回平台薄子类的工厂。
 */
fun interface AnalyzeRuleFactory {
    fun create(
        ruleData: RuleDataInterface?,
        source: BaseSource?,
        preUpdateJs: Boolean,
    ): AnalyzeRuleCore
}

/** 工厂容器 (照 BookInfoRefreshers 容器模式)。未注册端走默认裸 [AnalyzeRuleCore], 行为与现状一致。 */
object AnalyzeRuleFactories {

    @Volatile
    private var impl: AnalyzeRuleFactory = AnalyzeRuleFactory { ruleData, source, preUpdateJs ->
        AnalyzeRuleCore(ruleData, source, preUpdateJs)
    }

    /** 宿主启动早期注册一次 (任何书源解析之前)。 */
    fun register(impl: AnalyzeRuleFactory) {
        this.impl = impl
    }

    fun create(
        ruleData: RuleDataInterface? = null,
        source: BaseSource? = null,
        preUpdateJs: Boolean = false,
    ): AnalyzeRuleCore = impl.create(ruleData, source, preUpdateJs)
}
