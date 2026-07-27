package io.legado.app.data.entities

import kotlinx.serialization.Serializable

/**
 * 旧版 RSS 书源数据结构 (反序列化旧 rssSources.json 用)。
 *
 * 放在 shared jvmAndAndroidMain, 仅保留数据字段; 不含 toBookSource() 方法
 * (该方法依赖 BookSource, BookSource 是 C 类不下沉, 留在 app 端)。
 *
 * @Keep 注解移除: shared 无 androidx.annotation 依赖, 反射保活由 consumer-rules.pro 的
 * `-keep class io.legado.app.data.entities.** { *; }` 覆盖 (照 AnalyzeByXPath/StrResponse 先例)。
 *
 * toBookSource() 方法已拆为扩展函数留在 app 端
 * `OldRssSourceExtensions.kt` (同包名 io.legado.app.data.entities, 调用方零改动)。
 */
@Serializable
data class OldRssSource(
    var sourceUrl: String = "",
    var sourceName: String = "",
    var sourceIcon: String = "",
    var sourceGroup: String? = null,
    var sourceComment: String? = null,
    var enabled: Boolean = true,
    var variableComment: String? = null,
    var jsLib: String? = null,
    var enabledCookieJar: Boolean? = true,
    var enableDangerousApi: Boolean? = false,
    var concurrentRate: String? = null,
    var header: String? = null,
    var loginUrl: String? = null,
    var loginUi: String? = null,
    var loginCheckJs: String? = null,
    var coverDecodeJs: String? = null,
    var sortUrl: String? = null,
    var singleUrl: Boolean = false,
    var articleStyle: Int = 0,
    var ruleArticles: String? = null,
    var ruleNextPage: String? = null,
    var ruleTitle: String? = null,
    var rulePubDate: String? = null,
    var ruleDescription: String? = null,
    var ruleImage: String? = null,
    var ruleLink: String? = null,
    var ruleContent: String? = null,
    var contentWhitelist: String? = null,
    var contentBlacklist: String? = null,
    var shouldOverrideUrlLoading: String? = null,
    var style: String? = null,
    var enableJs: Boolean = true,
    var loadWithBaseUrl: Boolean = true,
    var injectJs: String? = null,
    var lastUpdateTime: Long = 0,
    var customOrder: Int = 0
)
