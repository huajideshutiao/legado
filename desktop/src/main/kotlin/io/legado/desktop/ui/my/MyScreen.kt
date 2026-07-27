package io.legado.desktop.ui.my

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.constant.EventBus
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.main.my.MyConfigScreen
import io.legado.app.utils.FlowBus
import io.legado.app.utils.browseUrl
import io.legado.app.web.WebServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * 桌面端"我的"页 (MY 一级入口)。
 *
 * 包装 shared [MyConfigScreen]，不重复自写 PreferenceScreen + preference 项。
 * shared MyConfigScreen 内部已按 app 端原版 pref_main.xml 顺序渲染:
 * - 顶部: 主题模式 / 主题设置 / 备份恢复 / Web 服务 / 其他设置
 * - 书源分组: 书源管理 / 替换净化 / 书源过滤规则 / TXT 目录规则 / 字典规则 / 规则订阅
 * - 其他分组: 书签 / 阅读记录 / 关于
 *
 * # Provider 注入
 *
 * `Main.kt` 已在最外层 `AppTheme { DesktopApp() }` 注入 4 个 DesktopXxxProvider,
 * 本 Composable 直接调用 shared MyConfigScreen, 不再重复注入。
 *
 * # WebService 接入
 *
 * Web 服务逻辑已下沉到 shared commonMain [WebServerManager] (HttpServer+WebSocketServer
 * 起停 + IP 枚举 + isRun/hostAddress 状态)。本页:
 * - [webServiceChecked] / [webServiceSummary] 从 [WebServerManager.isRun] / [hostAddress] 初始化
 * - [LaunchedEffect] 收集 FlowBus(EventBus.WEB_SERVICE) 实时回填开关态/地址 (对齐 app 端 MyTab.kt)
 * - [onWebServiceChange] 调 [WebServerManager.start] / [stop] (协程后台执行, 避免阻塞 UI)
 * - [onWebServiceLongClick] 弹选择对话框: 复制地址 / 浏览器打开 (对齐 app 端 context.selector)
 *
 * # 路由回调
 *
 * 其余 onXxx 回调由 DesktopApp 注入, 切到 MY 的各子路由
 * (BOOK_SOURCE / REPLACE / SOURCE_FILTER_RULE / TXT_TOC_RULE / DICT_RULE / RULE_SUB /
 * ALL_BOOKMARK / READ_RECORD / ABOUT / BACKUP_CONFIG / THEME_CONFIG / OTHER_CONFIG)。
 *
 * @param onThemeSetting 主题设置回调 (切到 THEME_CONFIG 路由)
 * @param onWebDavSetting 备份恢复回调 (切到 BACKUP_CONFIG 路由)
 * @param onOtherSetting 其他设置回调 (切到 OTHER_CONFIG 路由)
 * @param onBookSourceManage 书源管理回调 (切到 BOOK_SOURCE 路由)
 * @param onReplaceManage 替换净化回调 (切到 REPLACE 路由)
 * @param onSourceFilterRuleManage 书源过滤规则回调 (切到 SOURCE_FILTER_RULE 路由)
 * @param onTxtTocRuleManage TXT 目录规则回调 (切到 TXT_TOC_RULE 路由)
 * @param onDictRuleManage 字典规则回调 (切到 DICT_RULE 路由)
 * @param onRuleSubManage 规则订阅回调 (切到 RULE_SUB 路由)
 * @param onBookmark 书签回调 (切到 ALL_BOOKMARK 路由)
 * @param onReadRecord 阅读记录回调 (切到 READ_RECORD 路由)
 * @param onAbout 关于回调 (切到 ABOUT 路由)
 * @param onRssSources RSS 源回调 (切到 RSS_SOURCES 路由, 桌面端独有入口)
 */
@Composable
fun MyScreen(
    onThemeSetting: () -> Unit,
    onWebDavSetting: () -> Unit,
    onOtherSetting: () -> Unit,
    onBookSourceManage: () -> Unit,
    onReplaceManage: () -> Unit,
    onSourceFilterRuleManage: () -> Unit,
    onTxtTocRuleManage: () -> Unit,
    onDictRuleManage: () -> Unit,
    onRuleSubManage: () -> Unit,
    onBookmark: () -> Unit,
    onReadRecord: () -> Unit,
    onAbout: () -> Unit,
    onRssSources: () -> Unit,
) {
    // 主题模式切换: 桌面端调 DesktopThemeStoreProvider.toggleDark 切深浅色
    // (对照 app 端 ThemeConfig.applyDayNight(context), 桌面端无 Activity 重启, 直接切 isDark 触发重组)
    val themeStore = LocalThemeStoreProvider.current
    val scope = rememberCoroutineScope()
    val webServiceDesc = rememberString("web_service_desc")

    // Web 服务状态: 从 WebServerManager 初始化, 收集 FlowBus 事件实时更新
    // (对齐 app 端 MyTab.kt: webServiceChecked = WebService.isRun, LaunchedEffect 收集 EventBus.WEB_SERVICE)
    var webServiceChecked by remember { mutableStateOf(WebServerManager.isRun) }
    var webServiceSummary by remember {
        mutableStateOf(if (WebServerManager.isRun) WebServerManager.hostAddress else webServiceDesc)
    }
    var showWebServiceMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 复刻 app 端 observeEventSticky<WEB_SERVICE>: 服务状态变化实时回填开关态/地址
        FlowBus.withSticky(EventBus.WEB_SERVICE).collect {
            webServiceChecked = WebServerManager.isRun
            webServiceSummary = if (WebServerManager.isRun) {
                WebServerManager.hostAddress
            } else {
                webServiceDesc
            }
        }
    }

    MyConfigScreen(
        webServiceChecked = webServiceChecked,
        webServiceSummary = webServiceSummary,
        // 主题模式切换: 调 DesktopThemeStoreProvider.toggleDark 切深浅色
        onThemeModeChange = { (themeStore as? DesktopThemeStoreProvider)?.toggleDark() },
        // Web 服务开关: 协程后台调 WebServerManager.start/stop (NanoHTTPD 起/停可能阻塞, 避免 UI 卡顿)
        onWebServiceChange = { checked ->
            webServiceChecked = checked
            scope.launch {
                withContext(Dispatchers.IO) {
                    if (checked) {
                        val addresses = WebServerManager.start()
                        if (addresses.isEmpty()) {
                            // 启动失败 (无可用 IP), 回退开关态 + toast 提示
                            withContext(Dispatchers.Main) {
                                webServiceChecked = false
                                Toasters.get().toast("web service cant start, no ip address")
                            }
                        }
                    } else {
                        WebServerManager.stop()
                    }
                }
            }
        },
        // Web 服务长按: 弹选择对话框 (复制地址 / 浏览器打开), 对齐 app 端 context.selector
        onWebServiceLongClick = { showWebServiceMenu = true },
        onThemeSetting = onThemeSetting,
        onWebDavSetting = onWebDavSetting,
        onOtherSetting = onOtherSetting,
        onBookSourceManage = onBookSourceManage,
        onReplaceManage = onReplaceManage,
        onSourceFilterRuleManage = onSourceFilterRuleManage,
        onTxtTocRuleManage = onTxtTocRuleManage,
        onDictRuleManage = onDictRuleManage,
        onRuleSubManage = onRuleSubManage,
        onBookmark = onBookmark,
        onReadRecord = onReadRecord,
        onAbout = onAbout,
        // RSS 源入口: 桌面端独有, showRssEntry=true 让 MyConfigScreen 渲染 RSS preference 项
        showRssEntry = true,
        onRssSources = onRssSources,
    )

    // Web 服务长按菜单 (复制地址 / 浏览器打开), 对齐 app 端 context.selector
    if (showWebServiceMenu) {
        val currentUrl = WebServerManager.hostAddress
        AlertDialog(
            onDismissRequest = { showWebServiceMenu = false },
            confirmButton = {
                TextButton(onClick = {
                    // 复制地址到系统剪贴板 (java.awt Toolkit, 桌面端跨平台)
                    runCatching {
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(StringSelection(currentUrl), null)
                    }
                    Toasters.get().toast(jvmGetString("address_copied"))
                    showWebServiceMenu = false
                }) { Text(rememberString("copy_address")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    // 浏览器打开 (shared jvmMain browseUrl, 用 java.awt.Desktop.browse)
                    runCatching { browseUrl(currentUrl) }
                        .onFailure { Toasters.get().toast(jvmGetString("open_failed", it.localizedMessage)) }
                    showWebServiceMenu = false
                }) { Text(rememberString("open_in_browser")) }
            },
            title = { Text(rememberString("web_service")) },
            text = { Text(currentUrl) },
            modifier = Modifier.width(360.dp).padding(16.dp).fillMaxWidth(),
        )
    }
}
