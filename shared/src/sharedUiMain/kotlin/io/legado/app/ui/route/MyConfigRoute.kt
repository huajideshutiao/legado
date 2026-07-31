package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.main.my.MyConfigScreen
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.copy_url
import legado.shared.generated.resources.open_in_browser
import legado.shared.generated.resources.web_service
import legado.shared.generated.resources.web_service_desc
import org.jetbrains.compose.resources.stringResource

/**
 * 我的页设置 shared 路由入口: 渲染 [MyConfigScreen] 并桥接各子条目路由跳转。
 *
 * MyConfigScreen 为纯展示型 (无 ScreenModel/UiActions), webService 开关状态用本地
 * remember 临时持有, 并通过 [PlatformCapabilityProviders.webServiceState] 同步平台实际运行态
 * (对照 app 端 MyTab FlowBus.withSticky(EventBus.WEB_SERVICE) 校正);
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
    val caps = PlatformCapabilityProviders.getOrNull()
    // 进入时以服务实际运行态校准 (对照 MyTab 初始化 mutableStateOf(WebService.isRun))
    var webServiceChecked by remember { mutableStateOf(caps?.isWebServiceRunning() ?: false) }
    val webServiceDesc = stringResource(Res.string.web_service_desc)
    var webServiceSummary by remember {
        mutableStateOf(
            if (webServiceChecked) caps?.getWebServiceUrl().orEmpty() else webServiceDesc
        )
    }
    var showWebServiceMenu by remember { mutableStateOf(false) }

    // 复刻 MyTab LaunchedEffect: FlowBus.withSticky(EventBus.WEB_SERVICE).collect 校正开关态/地址
    val webServiceState = remember { caps?.webServiceState }
    LaunchedEffect(webServiceState) {
        webServiceState?.collect { running ->
            webServiceChecked = running
            webServiceSummary = if (running) {
                PlatformCapabilityProviders.getOrNull()?.getWebServiceUrl().orEmpty()
            } else {
                webServiceDesc
            }
        }
    }

    MyConfigScreen(
        webServiceChecked = webServiceChecked,
        webServiceSummary = webServiceSummary,
        onThemeModeChange = {
            PlatformCapabilityProviders.getOrNull()?.applyDayNight()
        },
        // 对照 MyTab: 开关切换即时回填态; 写 prefs 由 switchPreference 内部处理,
        // shared 端无 SharedPreferences 监听, 直接调 setWebService 触发 start/stop
        onWebServiceChange = {
            webServiceChecked = it
            webServiceSummary = if (it) {
                PlatformCapabilityProviders.getOrNull()?.getWebServiceUrl().orEmpty()
            } else {
                webServiceDesc
            }
            PlatformCapabilityProviders.getOrNull()?.setWebService(it)
        },
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

    // web 服务长按菜单 (对照 MyTab context.selector: 复制地址 / 浏览器打开)
    if (showWebServiceMenu) {
        val url = PlatformCapabilityProviders.getOrNull()?.getWebServiceUrl()
        AppSelectorDialog(
            onDismissRequest = { showWebServiceMenu = false },
            title = stringResource(Res.string.web_service),
            items = listOf(
                stringResource(Res.string.copy_url),
                stringResource(Res.string.open_in_browser)
            ),
            onItemSelected = { i ->
                when (i) {
                    0 -> url?.let { PlatformCapabilityProviders.getOrNull()?.copyToClipboard(it) }
                    1 -> url?.let { PlatformCapabilityProviders.getOrNull()?.openExternalUrl(it) }
                }
            },
        )
    }
}
