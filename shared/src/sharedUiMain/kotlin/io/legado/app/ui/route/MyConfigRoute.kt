package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.main.my.MyConfigScreen
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.copy_url
import legado.shared.generated.resources.my
import legado.shared.generated.resources.open_in_browser
import legado.shared.generated.resources.web_service_desc
import org.jetbrains.compose.resources.stringResource

/**
 * 我的页设置 shared 路由入口: 渲染 [MyConfigScreen] 并桥接各子条目路由跳转。
 *
 * MyConfigScreen 为纯展示型 (无 ScreenModel/UiActions), webService 开关态与副标题由
 * [PlatformCapabilityProviders] 的 webServiceAddress 流回填 (空串=未运行);
 * 主题模式切换 (applyDayNight) / web 服务启停 (setWebService) / 长按菜单 (复制/打开地址) /
 * 书签入口通过 [PlatformCapabilityProviders] 与 [AppNavigator] 桥接。
 *
 * web 服务长按菜单用 [AppSelectorDialog] (对照 app 端 MyTab context.selector)。
 */
@Composable
fun MyConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val caps = PlatformCapabilityProviders.get()
    val webServiceDesc = stringResource(Res.string.web_service_desc)
    // Web 服务地址: 空串=未运行 (对照原版 observeEvent<String>(WEB_SERVICE) 后回读 hostAddress)
    val webServiceAddress by caps.webServiceAddress.collectAsState()
    var showWebServiceMenu by remember { mutableStateOf(false) }

    // push 打开时自带顶栏 + 返回按钮 (对照其他 push 型路由如 ThemeConfigRoute;
    // tab 态不经本路由, 由 MainRoute 的 MyTabTitleBar 提供无返回顶栏)
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = stringResource(Res.string.my),
            onBack = { navigator.pop() },
        )
        MyConfigScreen(
            webServiceChecked = webServiceAddress.isNotEmpty(),
            webServiceSummary = webServiceAddress.ifEmpty { webServiceDesc },
            onThemeModeChange = {
                PlatformCapabilityProviders.get().applyDayNight()
            },
            // 开关态由 webServiceAddress 回填, 不本地乐观更新 (对照 MyTabContent)
            onWebServiceChange = { caps.setWebService(it) },
            onWebServiceLongClick = { showWebServiceMenu = true },
            onThemeSetting = { navigator.push(AppRoute.ThemeConfig) },
            onWebDavSetting = { navigator.push(AppRoute.BackupConfig) },
            onOtherSetting = { navigator.push(AppRoute.OtherConfig) },
            onBookSourceManage = { navigator.push(AppRoute.BookSourceManage) },
            onReplaceManage = { navigator.push(AppRoute.ReplaceRule) },
            onSourceFilterRuleManage = { navigator.push(AppRoute.SourceFilterRule) },
            onTxtTocRuleManage = { navigator.push(AppRoute.TxtTocRule) },
            onDictRuleManage = { navigator.push(AppRoute.DictRule) },
            onRuleSubManage = { navigator.push(AppRoute.RuleSub) },
            onBookmark = { navigator.push(AppRoute.Bookmark()) },
            onReadRecord = { navigator.push(AppRoute.ReadRecord) },
            onAbout = { navigator.push(AppRoute.About) },
            // 原版 pref_main.xml 无 RSS 条目, 订阅入口在主界面底栏 tab, 此处不渲染
        )
    }

    // web 服务长按菜单 (对照 MyTab context.selector: 复制地址 / 浏览器打开)
    if (showWebServiceMenu) {
        val url = webServiceAddress.takeIf { it.isNotEmpty() }
        AppSelectorDialog(
            onDismissRequest = { showWebServiceMenu = false },
            items = listOf(
                stringResource(Res.string.copy_url),
                stringResource(Res.string.open_in_browser)
            ),
            onItemSelected = { i ->
                when (i) {
                    0 -> url?.let { PlatformCapabilityProviders.get().copyToClipboard(it) }
                    1 -> url?.let { PlatformCapabilityProviders.get().openExternalUrl(it) }
                }
            },
        )
    }
}
