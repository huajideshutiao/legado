package io.legado.app.ui.rss

/**
 * RSS 阅读页注入给书源 JS 的两个额外方法 (对照 app 端 `RssJsExtensions`)。
 *
 * 只在 `contentRule.shouldOverrideUrlLoading` 拦截 JS 里可见: 书源用它把站内的
 * "搜索"/"加入书架" 链接接回 App 自己的搜索页与详情页。
 *
 * 接口下沉在 :data: data 的 native 桥 (NativeJsExtensionsBridge 按 methodId 分派,
 * RSS 专属 searchBook/addBook 走 1610/1611) 需要引用它; 实现在 :ui
 * (RssJsActions, 走 AppNavigatorProviders 推路由) 与各端 actual 绑定工厂。
 */
interface RssJsApi {

    /** 对照 `SearchActivity.start(activity, key)` */
    fun searchBook(key: String)

    /** 对照 `AddToBookshelfHelper.add(activity, bookUrl)` */
    fun addBook(bookUrl: String)
}
