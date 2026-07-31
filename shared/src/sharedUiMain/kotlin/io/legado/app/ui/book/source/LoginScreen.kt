package io.legado.app.ui.book.source

// I18N KEYS (已注册于 ResourceProvider.jvm.kt):
//   "loading" / "refresh" / "open_in_browser" / "copy_url" / "login" / "log"

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.copy_url
import legado.shared.generated.resources.ic_check
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.log
import legado.shared.generated.resources.login
import legado.shared.generated.resources.open_in_browser
import legado.shared.generated.resources.refresh
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 书源登录页 (URL/WebView 登录) shared Screen。
 *
 * 对照 app 端 `WebViewActivity` (isLogin=true) 的页面结构:
 * - [AppTitleBar]: title(页面标题/加载中) + subtitle(源标签) + 确认按钮(ic_check) + 溢出菜单
 * - WebView 区: 由 [platformWebViewSlot] 注入, 传入 [LoginUiState.loginUrl];
 *   平台 slot 内部负责加载页面、onPageFinished 时持久化 cookie 到 [io.legado.app.help.http.CookieStoreProviders]
 * - 确认登录: TitleBar 上的 ic_check 图标按钮 (对照 WebViewActivity menu_ok),
 *   触发 [LoginUiActions.onLogin]
 *
 * 平台特有行为 (WebView/CookieManager) 通过 slot + actions 注入, 不直接引用 android.webkit。
 *
 * @param state 登录页展示状态
 * @param actions 用户交互回调
 * @param platformWebViewSlot 平台 WebView 渲染槽, 参数为待加载 URL
 * @param webViewReloadKey WebView 重建序号, Route 收到刷新信号后自增
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    actions: LoginUiActions,
    platformWebViewSlot: @Composable (String) -> Unit,
    webViewReloadKey: Int = 0,
) {
    val colors = AppTheme.colors
    val loadingText = stringResource(Res.string.loading)
    val refreshText = stringResource(Res.string.refresh)
    val openInBrowserText = stringResource(Res.string.open_in_browser)
    val copyUrlText = stringResource(Res.string.copy_url)
    val loginText = stringResource(Res.string.login)
    val logText = stringResource(Res.string.log)

    val titleText = state.pageTitle.ifBlank { loadingText }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleText,
            onBack = actions::onBack,
            actions = {
                // 确认登录按钮: ic_check 图标 (对照 WebViewActivity menu_ok, always 显示)
                IconButton(onClick = actions::onLogin) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_check),
                        contentDescription = loginText,
                        tint = colors.primaryText,
                    )
                }
                // 刷新按钮 (对照 WebViewActivity menu_refresh, always 显示)
                IconButton(onClick = actions::onRefresh) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_refresh_black_24dp),
                        contentDescription = refreshText,
                        tint = colors.primaryText,
                    )
                }
                OverflowMenu {
                    DropdownMenuItem(onClick = { it(); actions.onOpenInBrowser() }) {
                        Text(openInBrowserText, color = colors.primaryText)
                    }
                    DropdownMenuItem(onClick = { it(); actions.onCopyUrl() }) {
                        Text(copyUrlText, color = colors.primaryText)
                    }
                    DropdownMenuItem(onClick = { it(); actions.onShowAppLog() }) {
                        Text(logText, color = colors.primaryText)
                    }
                }
            },
        )
        // WebView 区: 平台 slot 负责加载 URL + cookie 捕获
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            key(webViewReloadKey) {
                platformWebViewSlot(state.loginUrl)
            }
        }
    }

    // 日志对话框
    if (state.showAppLog) {
        AppLogDialog(onDismiss = actions::onDismissAppLogDialog)
    }
}

/**
 * [LoginScreen] 用户交互回调。
 *
 * 对照 app 端 WebViewActivity (isLogin=true) 的菜单与行为:
 * - onBack: 返回 (优先 webView.goBack, 由平台 slot 处理; 此处为最终 pop)
 * - onLogin: 确认登录完成 (menu_ok, 触发 reload 检查 cookie 或直接 pop)
 * - onRefresh: 刷新 WebView (menu_refresh)
 * - onOpenInBrowser: 用系统浏览器打开当前 URL (menu_open_in_browser)
 * - onCopyUrl: 复制当前 URL 到剪贴板 (menu_copy_url)
 * - onShowAppLog / onDismissAppLogDialog: 日志对话框
 */
interface LoginUiActions {
    /** 返回 (标题栏返回箭头) */
    fun onBack()

    /** 确认登录完成 (TitleBar ic_check 按钮) */
    fun onLogin()

    /** 刷新 WebView (TitleBar ic_refresh 按钮) */
    fun onRefresh()

    /** 溢出菜单: 用系统浏览器打开 */
    fun onOpenInBrowser()

    /** 溢出菜单: 复制当前 URL */
    fun onCopyUrl()

    /** 溢出菜单: 查看日志 */
    fun onShowAppLog()

    /** 关闭日志对话框 */
    fun onDismissAppLogDialog()
}
