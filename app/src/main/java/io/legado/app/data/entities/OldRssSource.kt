package io.legado.app.data.entities

import androidx.annotation.Keep
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule

@Keep
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
) {
    fun toBookSource(): BookSource {
        val bookSource = BookSource()
        bookSource.bookSourceUrl = sourceUrl
        bookSource.bookSourceName = sourceName
        bookSource.bookSourceGroup = sourceGroup
        bookSource.bookSourceType = BookSourceType.rss
        bookSource.bookSourceComment = sourceComment
        bookSource.customOrder = customOrder
        bookSource.enabled = enabled
        bookSource.enabledExplore = true
        bookSource.jsLib = jsLib
        bookSource.enabledCookieJar = enabledCookieJar
        bookSource.enableDangerousApi = enableDangerousApi
        bookSource.concurrentRate = concurrentRate
        bookSource.header = header
        bookSource.loginUrl = loginUrl
        bookSource.loginUi = loginUi
        bookSource.loginCheckJs = loginCheckJs
        bookSource.coverDecodeJs = coverDecodeJs
        bookSource.variableComment = variableComment
        bookSource.lastUpdateTime = lastUpdateTime
        bookSource.exploreUrl = sortUrl
        bookSource.exploreStyle = articleStyle

        bookSource.ruleExplore = ExploreRule(
            bookList = ruleArticles,
            name = ruleTitle,
            author = rulePubDate,
            intro = ruleDescription,
            coverUrl = ruleImage,
            bookUrl = ruleLink
        )

        val mStyle = style ?: ""
        val mInjectJs = injectJs ?: ""
        val webJs = (if (mStyle.isNotEmpty()) "var style = document.createElement('style');\nstyle.innerHTML = \"${org.apache.commons.text.StringEscapeUtils.escapeEcmaScript(mStyle)}\";\ndocument.head.appendChild(style);\n" else "") + mInjectJs

        bookSource.ruleContent = ContentRule(
            content = ruleContent,
            webJs = webJs.ifEmpty { null },
            shouldOverrideUrlLoading = shouldOverrideUrlLoading
        )

        return bookSource
    }
}