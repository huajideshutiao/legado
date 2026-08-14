package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.legado.app.ui.association.LegadoDeepLinkHandler
import io.legado.app.ui.route.AboutRoute
import io.legado.app.ui.route.AudioPlayRoute
import io.legado.app.ui.route.BackupConfigRoute
import io.legado.app.ui.route.BookInfoEditRoute
import io.legado.app.ui.route.BookInfoRoute
import io.legado.app.ui.route.BookSourceDebugRoute
import io.legado.app.ui.route.BookSourceEditRoute
import io.legado.app.ui.route.BookSourceManageRoute
import io.legado.app.ui.route.BookmarkRoute
import io.legado.app.ui.route.BookshelfManageRoute
import io.legado.app.ui.route.ChangeChapterSourceRoute
import io.legado.app.ui.route.ChangeSourceRoute
import io.legado.app.ui.route.CoverConfigRoute
import io.legado.app.ui.route.DictRuleRoute
import io.legado.app.ui.route.ExploreRoute
import io.legado.app.ui.route.ExploreShowRoute
import io.legado.app.ui.route.ImportBookRoute
import io.legado.app.ui.route.JsEditRoute
import io.legado.app.ui.route.MainRoute
import io.legado.app.ui.route.MangaReaderRoute
import io.legado.app.ui.route.MyConfigRoute
import io.legado.app.ui.route.OtherConfigRoute
import io.legado.app.ui.route.ReadRecordRoute
import io.legado.app.ui.route.ReadRssRoute
import io.legado.app.ui.route.ReaderRoute
import io.legado.app.ui.route.RemoteBookRoute
import io.legado.app.ui.route.ReplaceEditRoute
import io.legado.app.ui.route.ReplaceRuleRoute
import io.legado.app.ui.route.ReviewListRoute
import io.legado.app.ui.route.RuleSubRoute
import io.legado.app.ui.route.SearchContentRoute
import io.legado.app.ui.route.SearchRoute
import io.legado.app.ui.route.SourceFilterRuleRoute
import io.legado.app.ui.route.ThemeConfigRoute
import io.legado.app.ui.route.TocRoute
import io.legado.app.ui.route.TxtTocRuleRoute
import io.legado.app.ui.route.VideoPlayRoute
import io.legado.app.ui.route.WebDavConfigRoute
import io.legado.app.ui.route.WebViewRoute
import io.legado.app.ui.route.WelcomeConfigRoute

/**
 * shared 统一路由内容分发 (零薄壳方案)。
 *
 * 按 [RouteEntry.route] 类型调用 `io.legado.app.ui.route` 下对应的 `XxxRoute`,
 * 由 Route 绑定 ScreenModel 并渲染 shared Screen。路由分发由 shared 统一接管,
 * 不再保留平台平行页面分发薄壳。
 */
@Composable
fun RouteContent(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
): Boolean {
    return when (val route = entry.route) {
        // ===== 已下沉路由 (调用 shared XxxRoute, 返回 true) =====
        is AppRoute.About -> {
            AboutRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.BackupConfig -> {
            BackupConfigRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.BookInfo -> {
            BookInfoRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.BookInfoEdit -> {
            BookInfoEditRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.BookSourceDebug -> {
            BookSourceDebugRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.BookSourceEdit -> {
            BookSourceEditRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.BookSourceManage -> {
            BookSourceManageRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.Bookmark -> {
            BookmarkRoute(entry, navigator, screenModelStore)
            true
        }
        is AppRoute.BookshelfManage -> {
            BookshelfManageRoute(entry, navigator, screenModelStore)
            true
        }
        is AppRoute.ChangeSource -> {
            ChangeSourceRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.CoverConfig -> {
            CoverConfigRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.DictRule -> {
            DictRuleRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.Explore -> {
            ExploreRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.ExploreShow -> {
            ExploreShowRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.ExploreShowByUrl -> {
            ExploreShowRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.ImportBook -> {
            ImportBookRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.Main -> {
            // 导入壳 (全平台共享, 对照 master AssociationActivity 透明壳语义):
            // 深链导入进行中 (LegadoDeepLinkHandler.pending 非空) 时不渲染书架/发现等
            // 主界面页面, 只保留容器 (Activity/导航/主题, 由平台根正常组合) + 共享导入
            // 对话框; 导入完成 pending 置空后主界面自然出现。
            val pending by LegadoDeepLinkHandler.pending.collectAsState()
            if (pending == null) {
                MainRoute(entry, navigator, screenModelStore)
            }
            true
        }

        is AppRoute.MyConfig -> {
            MyConfigRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.OtherConfig -> {
            OtherConfigRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.ReadRecord -> {
            ReadRecordRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.RemoteBook -> {
            RemoteBookRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.ReplaceEdit -> {
            ReplaceEditRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.ReplaceRule -> {
            ReplaceRuleRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.RuleSub -> {
            RuleSubRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.Search -> {
            SearchRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.SearchContent -> {
            SearchContentRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.SourceFilterRule -> {
            SourceFilterRuleRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.ThemeConfig -> {
            ThemeConfigRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.Toc -> {
            TocRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.TxtTocRule -> {
            TxtTocRuleRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.WelcomeConfig -> {
            WelcomeConfigRoute(entry, navigator, screenModelStore)
            true
        }

        // ===== 阅读、媒体与平台能力路由 =====
        is AppRoute.Reader -> {
            ReaderRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.AudioPlay -> {
            AudioPlayRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.VideoPlay -> {
            VideoPlayRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.MangaReader -> {
            MangaReaderRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.ReadRss -> {
            ReadRssRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.ChangeChapterSource -> {
            ChangeChapterSourceRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.ReviewList -> {
            ReviewListRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.WebDavConfig -> {
            WebDavConfigRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.WebView -> {
            WebViewRoute(entry, navigator, screenModelStore)
            true
        }

        is AppRoute.JsEdit -> {
            JsEditRoute(entry, navigator, screenModelStore)
            true
        }
    }
}
