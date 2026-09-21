package io.legado.app.data.entities

import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule

/*
 * OldRssSource 扩展函数 (app 端)。
 *
 * OldRssSource 数据类已下沉到 shared jvmAndAndroidMain, 但其 toBookSource() 方法依赖
 * BookSource (app 端 C 类不下沉), 故方法体留在 app 端作为同包扩展函数。
 *
 * 调用方代码不变 (oldRss.toBookSource()), Kotlin 跨模块同包名 top-level 扩展自动可见。
 */
fun OldRssSource.toBookSource(): BookSource {
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

    bookSource.exploreRule = ExploreRule(
        bookList = ruleArticles,
        name = ruleTitle,
        author = rulePubDate,
        intro = ruleDescription,
        coverUrl = ruleImage,
        bookUrl = ruleLink
    )

    val mStyle = style ?: ""
    val mInjectJs = injectJs ?: ""
    val webJs =
        (if (mStyle.isNotEmpty()) "var style = document.createElement('style');\nstyle.innerHTML = \"${
            io.legado.app.utils.EscapeUtils.escapeEcmaScript(mStyle)
        }\";\ndocument.head.appendChild(style);\n" else "") + mInjectJs

    bookSource.contentRule = ContentRule(
        content = ruleContent,
        webJs = webJs.ifEmpty { null },
        shouldOverrideUrlLoading = shouldOverrideUrlLoading
    )

    return bookSource
}
