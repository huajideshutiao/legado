package io.legado.app.ui.root

/**
 * 页面的启动模式, 对照原版 AndroidManifest 的 `android:launchMode`。
 *
 * 原版一页一 Activity, 单例语义由系统按 launchMode 实现; 本项目单栈导航, 由导航层按本枚举
 * 还原同一语义:
 * - [Standard]: 每次入栈都追加新页 (原版默认);
 * - [SingleTop]: 栈顶已是同类页时复用该页并换参数, 否则追加;
 * - [SingleTask]: 栈中已有同类页时复用该页、换参数, 并销毁它上面的所有页。
 *
 * 复用保留原页面身份: 页面数据与界面状态以身份为键, 换参数不重建页面。
 */
enum class RouteLaunchMode {
    Standard, SingleTop, SingleTask
}

/**
 * 本路由的启动模式, 逐项对齐原版 AndroidManifest (archive 提交 d0c42f3242)。
 *
 * 未列出的路由在原版没有独立 Activity (本项目拆出来的页面) 或原版就是 standard, 一律 [RouteLaunchMode.Standard]。
 */
val AppRoute.launchMode: RouteLaunchMode
    get() = when (this) {
        // 原版 singleTask
        is AppRoute.Main, is AppRoute.Reader, is AppRoute.MangaReader, is AppRoute.BookInfoEdit,
        is AppRoute.AudioPlay, is AppRoute.BookSourceEdit, is AppRoute.ReplaceEdit,
        is AppRoute.About -> RouteLaunchMode.SingleTask

        // 原版 singleTop
        is AppRoute.BookInfo, is AppRoute.BookSourceManage, is AppRoute.TxtTocRule,
        is AppRoute.RuleSub, is AppRoute.ReplaceRule, is AppRoute.SourceFilterRule,
        is AppRoute.BookshelfManage, is AppRoute.BookSourceDebug, is AppRoute.Toc,
        is AppRoute.SearchContent, is AppRoute.ReadRss, is AppRoute.ImportBook,
        is AppRoute.RemoteBook, is AppRoute.Bookmark -> RouteLaunchMode.SingleTop

        else -> RouteLaunchMode.Standard
    }

/** 两个路由是否指向同一类页面: 单例判定按页面类型, 不看参数 (对照原版按 Activity 类判定)。 */
internal fun AppRoute.samePage(other: AppRoute): Boolean = this::class == other::class
