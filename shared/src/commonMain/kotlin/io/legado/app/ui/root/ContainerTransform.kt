package io.legado.app.ui.root

/**
 * 书籍页侧: 详情页 + [BookRef.toReadRoute] 分流出的 5 种阅读路由 (音频/视频/漫画/RSS/文字)。
 * 返回 null 即"本路由不是书籍页"。
 */
internal fun AppRoute.containerBookUrl(): String? = when (this) {
    is AppRoute.BookInfo -> book.bookUrl
    is AppRoute.Reader -> book.bookUrl
    is AppRoute.AudioPlay -> book.bookUrl
    is AppRoute.VideoPlay -> book.bookUrl
    is AppRoute.MangaReader -> book.bookUrl
    is AppRoute.ReadRss -> book.bookUrl
    else -> null
}

/**
 * 列表页侧: 书架主界面 / 搜索 / 发现show (含只带 sourceUrl 的外部入口形态)。
 *
 * [AppRoute.Main.tab] 不参与判定: 路由里的 tab 只是初始落点, 当前可见 tab 由 MainRoute
 * 自己的 rememberSaveable 持有, 路由层看不到它。故 Main 一律算列表页 —— 当前 tab 无该书时
 * 卡片矩形登记表里取不到值, 段自然不成立。
 */
private fun AppRoute.isBookListPage(): Boolean = when (this) {
    is AppRoute.Main -> true
    is AppRoute.Search -> true
    is AppRoute.ExploreShow -> true
    is AppRoute.ExploreShowByUrl -> true
    else -> false
}

/**
 * 容器变换段身份 = 参与本段的书 bookUrl (卡片矩形登记表的 key)。
 *
 * 方向无关: 传 (来源页, 目标页) 或反之都得同一结果; 不构成"列表页 ↔ 书籍页"页对时返回 null
 * (两侧同为列表页、同为书籍页、任一侧是其它页, 一律不做容器变换)。
 */
fun containerTransformBookUrl(a: AppRoute?, b: AppRoute?): String? {
    if (a == null || b == null) return null
    val aIsList = a.isBookListPage()
    val bIsList = b.isBookListPage()
    return when {
        aIsList && !bIsList -> b.containerBookUrl()
        bIsList && !aIsList -> a.containerBookUrl()
        else -> null
    }
}
