package io.legado.app.model.webBook

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.help.IntentDataAccessor
import io.legado.app.help.IntentDataProviders
import io.legado.app.utils.NativeRegexReplacer
import io.legado.app.utils.RegexReplacers

/**
 * webBook 编排层的 native (iOS/鸿蒙) provider 注册入口, 对照 desktop
 * registerDesktopWebBookProviders (ContentProcessorProviders 由
 * registerNativeContentProcessorAccessor 单独注册, 此处不重复)。
 *
 * - [BookInfoRefreshers]: 委托 commonMain [WebBook.getBookInfoAwait] (未注册时
 *   AnalyzeRule.refreshTocUrl 静默跳过, ruleTocUrl 规则失效)
 * - [IntentDataProviders]: 桥接 commonMain [IntentData] 单例 (未注册时 webBook 编排层抛异常)
 * - [RegexReplacers]: 注册 [NativeRegexReplacer] (未注册时替换规则 replace(timeout) 路径抛异常)
 */
fun registerNativeWebBookProviders() {
    BookInfoRefreshers.register(NativeBookInfoRefresher)
    IntentDataProviders.register(NativeIntentDataAccessor)
    RegexReplacers.register(NativeRegexReplacer)
}

/** 委托 commonMain [WebBook.getBookInfoAwait] (与 app/desktop 端实现一致)。 */
private object NativeBookInfoRefresher : BookInfoRefresher {
    override suspend fun refreshBookInfo(
        bookSource: BaseSource,
        book: Any,
        canReName: Boolean,
    ) {
        WebBook.getBookInfoAwait(bookSource as BookSource, book as Book, canReName)
    }
}

/** 桥接 commonMain [IntentData] 单例 (与 desktop DesktopIntentDataAccessor 一致)。 */
private object NativeIntentDataAccessor : IntentDataAccessor {
    override fun setSource(source: Any?) {
        IntentData.source = source
    }
}
