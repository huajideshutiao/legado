package io.legado.app.ui.rss

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.book.addType
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.model.webBook.WebBook.getBookInfoByUrlAwait
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.toRouteRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RSS 阅读页注入给书源 JS 的两个额外方法 (对照 app 端 `RssJsExtensions`)。
 *
 * 只在 `contentRule.shouldOverrideUrlLoading` 拦截 JS 里可见: 书源用它把站内的
 * "搜索"/"加入书架" 链接接回 App 自己的搜索页与详情页。
 *
 * 各端由 [RssJsBindingFactory] 把本接口与平台自己的 JS 扩展面合成一个对象后绑到 `java`,
 * 见 `createRssJsBinding`。
 */
interface RssJsApi {

    /** 对照 `SearchActivity.start(activity, key)` */
    fun searchBook(key: String)

    /** 对照 `AddToBookshelfHelper.add(activity, bookUrl)` */
    fun addBook(bookUrl: String)
}

/**
 * [RssJsApi] 的共享实现: 走 [AppNavigatorProviders] 推路由, 与 app 端 Activity 跳转等价。
 *
 * 平台的 `java` 包装类把这两个方法委托到本类 (`RssJsApi by RssJsActions(scope)`)。
 */
class RssJsActions(private val scope: CoroutineScope) : RssJsApi {

    override fun searchBook(key: String) {
        AppNavigatorProviders.getOrNull()?.push(AppRoute.Search(key = key, submit = true))
    }

    override fun addBook(bookUrl: String) {
        if (bookUrl.isBlank()) {
            Toasters.get().toast("url不能为空")
            return
        }
        scope.launch {
            runCatching {
                withContext(IoDispatcher) { getBookInfoByUrlAwait(bookUrl) }
            }.onSuccess { book ->
                AppNavigatorProviders.getOrNull()
                    ?.push(AppRoute.BookInfo(book.apply { addType(BookType.notShelf) }
                        .toRouteRef()))
            }.onFailure { e ->
                AppLog.put("添加书籍 $bookUrl 出错", e)
                Toasters.get().toast(e.message ?: "添加书籍失败")
            }
        }
    }
}

/**
 * RSS 拦截 JS 的 `java` 绑定工厂。
 *
 * 平台差异在于"JS 可见的扩展面"怎么来: JVM 半区 (Android/桌面) 用 `JsExtensionsJvm` 接口默认实现,
 * native 半区靠 `NativeJsExtensionsBridge` 按 methodId 桥接 [BaseSource] 自身。
 * 故本工厂由各端 actual 提供, 与 [io.legado.app.help.JsExtProviders] 同一套路。
 *
 * native 半区的 JS 桥按 methodId 表分派, 表里没有 searchBook/addBook, 故 native actual 直接
 * 返回 [source] (拦截 JS 照常执行, 只是这两个方法取不到)。iOS/鸿蒙的 WebView slot 目前也还是占位,
 * 拦截根本不会触发。
 */
expect fun createRssJsBinding(source: BaseSource, actions: RssJsApi): Any
