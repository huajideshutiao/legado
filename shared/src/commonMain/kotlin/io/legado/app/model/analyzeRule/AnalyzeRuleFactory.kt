package io.legado.app.model.analyzeRule

import io.legado.app.data.entities.BaseSource

/**
 * AnalyzeRule 实例工厂: shared webBook 编排层直接 new [AnalyzeRuleCore] 会缺失平台端 JS 扩展面
 * (JsEncodeUtils 摘要/加解密工厂、jsoup get/head/post 等), 书源 JS 调 java.md5Encode 报 not a function。
 * 各端启动早期注册返回平台薄子类 (app AnalyzeRule / desktop DesktopAnalyzeRule) 的工厂恢复完整面。
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
