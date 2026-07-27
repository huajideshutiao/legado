@file:Suppress("unused")

package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.isDataUrl

/*
 * BookChapter 扩展函数下沉区 (shared commonMain)。
 *
 * 仅放 shared 侧实体类 (BookChapter) 自身依赖、且无 app 平台绑定的扩展。
 * 安卓侧绑定 (AppConfig/appDb/ChineseUtils 等, 如 getDisplayTitle) 已下沉到
 * BookDisplayExtensionsShared.kt (commonMain), ChineseUtils/Period 经 expect/actual 桥接。
 *
 * 跨模块同包名同签名扩展自动合并, 消费方 import 零改动。
 * 注意: 同包名同签名扩展函数不允许在两个模块同时定义, 需从 app 端删除已下沉的扩展。
 */

/**
 * 计算章节绝对 URL (处理相对 URL / 二级目录 / 阅读定义的 urlOption)。
 *
 * 原 app 端 BookChapterExtensions.kt 中的扩展, 下沉到 shared 以支持
 * WebBook/BookChapterList/BookContent 在 shared 中使用。
 *
 * 依赖均为 shared 已下沉类型 (AnalyzeUrlCore/NetworkUtils/isDataUrl)。
 */
fun BookChapter.getAbsoluteURL(book: Book): String {
    //二级目录解析的卷链接为空 返回目录页的链接
    if (url.startsWith(title) && isVolume) return book.tocUrl
    if (url.isDataUrl()) return url
    // Pattern.matcher → Regex.find: match.range.first 对应 matcher.start(), match.range.last + 1 对应 matcher.end()
    val urlMatch = AnalyzeUrlCore.paramPattern.find(url)
    val urlBefore = urlMatch?.let { url.substring(0, it.range.first) } ?: url
    val urlAbsoluteBefore = NetworkUtils.getAbsoluteURL(book.tocUrl, urlBefore)
    return if (urlBefore.length == url.length) {
        urlAbsoluteBefore
    } else {
        // urlMatch 非 null (urlBefore.length != url.length 意味着 find() 匹配成功)
        "$urlAbsoluteBefore," + url.substring(urlMatch!!.range.last + 1)
    }
}
