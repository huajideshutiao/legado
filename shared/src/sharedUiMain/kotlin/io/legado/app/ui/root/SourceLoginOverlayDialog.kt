package io.legado.app.ui.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.legado.app.help.SourceLoginContext
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.source.LoginScreen
import io.legado.app.ui.book.source.LoginScreenModel
import io.legado.app.ui.book.source.LoginUiActions
import io.legado.app.ui.book.source.LoginUiEvent
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.book.source.SourceLoginFormState
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.browser.WebViewCallbacks
import io.legado.app.ui.browser.WebViewConfig
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.decodeStringMapOrNull
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.loading
import org.jetbrains.compose.resources.stringResource

/**
 * 书源登录 Overlay 对话框 (key="sourceLogin")。
 *
 * 对照原版 `BaseSource.showLoginDialog` 的两条分支分发:
 * - loginUi 非空 -> [SourceLoginDialog] 表单登录 (book/chapter 作为登录 JS 上下文);
 * - 否则 -> [LoginScreen] WebView 登录 (对照 `WebViewActivity` isLogin=true)。
 *
 * 纯 Overlay 形态: 由 [OverlayContentHost] 按 key 分流, 经 [EditDialogHost]
 * (AppDialog + appDialogSize 居中卡片) 直接叠在当前界面上层, 不推新路由。
 * 表单/URL 两条分支视觉完全一致 (桌面端 WebView 本就是独立窗口, 对话框外壳承担
 * "登录中"容器语义)。
 *
 * payload 格式: [io.legado.app.help.sourceLoginOverlayPayload] 编码的 {url, dataKey}
 * (dataKey 指向 [SourceLoginContext], 对照原版 IntentData; 仅 URL 的入口 (深链/列表页)
 * 只有 url, 缺失时 [LoginScreenModel] 退化为按 url 查库)。
 *
 * WebView 渲染由平台注入 [LoginScreen.platformWebViewSlot], 经 [LocalWebViewSlot] 取平台实现
 * (Overlay 承载 WebView 有先例: "web_view" Sheet 已内嵌平台 WebView slot)。
 */
@Composable
internal fun SourceLoginOverlayContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    // ===== 挂起/恢复 (对照原版 DialogFragment 被新 Activity 全屏盖住仍存活) =====
    // 登录 JS startBrowser → push AppRoute.WebView 时, 单页导航下路由渲染在 Overlay 之下,
    // 不隐藏对话框窗口会遮住 WebView; 故路由栈被 push 盖住时挂起 (不渲染窗口, 状态保留), 
    // pop 回原栈时恢复, 表单数据不丢。挂起监听必须在所有 return 分支之前注册,
    // 保证组合存活期间持续捕获恢复时机。
    val initialStackSize = remember { navigator.backStack.value.size }
    var suspended by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        navigator.backStack.collect { entries ->
            val newSuspended = entries.size > initialStackSize
            if (newSuspended != suspended) {
                suspended = newSuspended
                navigator.setOverlaySuspended(overlay.key, newSuspended)
            }
        }
    }
    // Overlay 关闭 (dismiss / popTo / resetRoot 清栈) 时清理挂起标记
    DisposableEffect(Unit) {
        onDispose { navigator.setOverlaySuspended(overlay.key, false) }
    }

    val params = remember(overlay.payload) { parseSourceLoginPayload(overlay.payload) }
    // 无 url 且无 dataKey (payload 完全无法解析) 时直接关闭, 不落入空对话框
    if (params.sourceUrl.isBlank() && params.dataKey == null) {
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }

    // Overlay 无 RouteEntry, ScreenModel 生命周期绑定本组合, 关闭时回收协程
    val screenModel = remember(overlay) { LoginScreenModel() }
    DisposableEffect(screenModel) {
        onDispose { screenModel.onCleared() }
    }
    val clipboard = LocalClipboardManager.current
    val platformCapabilities = PlatformCapabilityProviders.getOrNull()

    // 解析源 (dataKey 内存上下文优先, 其次查库), 取 loginUrl 供 WebView slot
    LaunchedEffect(params.sourceUrl, params.dataKey) {
        screenModel.dispatch(LoginUiEvent.Init(params.sourceUrl, params.dataKey))
    }

    val state by screenModel.state.collectAsState()

    // 用户确认登录完成 -> 关闭对话框 (信号流, 重复点击也每次都投递)
    LaunchedEffect(screenModel) {
        screenModel.loginCompleteFlow.collect { navigator.dismissOverlay(overlay.key) }
    }

    // 表单登录状态 (登录数据/行) 由本组合持有: 挂起恢复后 SourceLoginDialog 重建时
    // 保留用户已输入的值, 跳过 rebuild 清空 (对照原版 DialogFragment 存活语义)
    val loginFormState = remember { SourceLoginFormState() }

    // loginUi 非空走表单登录 (对照原版 BaseSource.showLoginDialog 的 SourceLoginDialog 分支),
    // book/chapter 作为登录 JS 上下文透传
    val formSource = state.source?.takeIf { !it.loginUi.isNullOrEmpty() }

    // 刷新: 自增序号重建平台 WebView slot
    var webViewReloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(screenModel) {
        screenModel.refreshFlow.collect { webViewReloadKey++ }
    }

    val actions =
        remember(navigator, clipboard, platformCapabilities, screenModel, state.loginUrl) {
            object : LoginUiActions {
                override fun onBack() {
                    navigator.dismissOverlay(overlay.key)
                }

                override fun onLogin() {
                    screenModel.dispatch(LoginUiEvent.LoginComplete)
                }

                override fun onRefresh() {
                    // 重新创建平台 WebView slot，触发当前登录地址重载。
                    screenModel.dispatch(LoginUiEvent.Refresh)
                }

                override fun onOpenInBrowser() {
                    state.loginUrl.takeIf { it.isNotBlank() }
                        ?.let { platformCapabilities?.openExternalUrl(it) }
                }

                override fun onCopyUrl() {
                    clipboard.setText(AnnotatedString(state.loginUrl))
                }

                override fun onShowAppLog() {
                    screenModel.dispatch(LoginUiEvent.ShowAppLog)
                }

                override fun onDismissAppLogDialog() {
                    screenModel.dispatch(LoginUiEvent.DismissAppLogDialog)
                }
            }
        }

    // ===== 挂起: 不渲染 UI 窗口 (新路由可见) =====
    // 注意: 挂起 return 必须位于全部状态声明之后 —— return 之前已声明的节点
    // (screenModel / loginFormState / 信号收集) 在挂起期间保持存活, 恢复后原样呈现;
    // return 之后的 UI 节点 (EditDialogHost/表单/WebView) 挂起时移出组合, 恢复时重建。
    if (suspended) return

    // 源解析完成但拿不到可登录内容 (源不在库 / loginUrl、loginUi 双空):
    // 对照原版 showLoginDialog 双空直接 return 的语义, 关闭即可, 不落入空白对话框。
    if (!state.loading && formSource == null && state.loginUrl.isBlank()) {
        LaunchedEffect(Unit) {
            if (state.source == null) {
                Toasters.get().toast("未找到书源")
            }
            navigator.dismissOverlay(overlay.key)
        }
        return
    }

    // URL 登录兜底短路 (2026-08-07 用户拍板: 去掉登录中转界面): 入口无源对象时
    // (深链/列表页只传 url) Overlay 已渲染, 源加载完成后问平台是否直开登录窗口 ——
    // 平台已处理 (桌面端 = 独立浏览器窗口, isLogin 语义) 则关闭对话框, 不落入
    // 对话框外壳; 移动端 openLoginWebView 返回 false, 恢复渲染内嵌 WebView 对话框。
    // 有源对象的入口已在 showSourceLogin 提前短路, 不会走到这里。
    // 注意: 平台结果未定前只渲染占位, 不能渲染 LoginScreen —— 否则桌面端
    // LocalWebViewSlot → DesktopWebViewSlot 会先开一个窗口, 再被兜底窗口替换 (双窗闪屏)。
    var urlLoginFallback by remember { mutableStateOf(false) }
    val urlLoginAwait =
        !state.loading && formSource == null && state.loginUrl.isNotBlank() && !urlLoginFallback
    if (urlLoginAwait) {
        LaunchedEffect(state.loginUrl) {
            val handled = platformCapabilities
                ?.openLoginWebView(state.loginUrl, state.source?.getKey().orEmpty()) == true
            if (handled) {
                navigator.dismissOverlay(overlay.key)
            } else {
                urlLoginFallback = true
            }
        }
    }

    // 统一居中对话框外壳: 加载占位 / 表单登录 / URL 登录 (WebView) 共用同一
    // EditDialogHost (AppDialog + appDialogSize 居中卡片), 全端"登录=对话框"。
    EditDialogHost(onDismiss = { navigator.dismissOverlay(overlay.key) }) {
        when {
            // 源还没解析出来: 对话框内加载占位, 先不建 WebView, 否则表单源会闪一下空白页
            state.loading -> LoginLoadingPlaceholder()

            // URL 登录兜底等待平台结果: 同样只渲染占位 (防桌面端双窗闪屏, 见上)
            urlLoginAwait -> LoginLoadingPlaceholder()

            // 表单登录: 对照原版 BaseSource.showLoginDialog 的
            // showDialogFragment<SourceLoginDialog> 分支。
            formSource != null -> SourceLoginDialog(
                source = formSource,
                onDismiss = { navigator.dismissOverlay(overlay.key) },
                onOpenUrl = { platformCapabilities?.openExternalUrl(it) },
                book = state.book,
                chapter = state.chapter,
                formState = loginFormState,
            )

            // URL 登录 (loginUi 为空, 对照原版 WebViewActivity isLogin=true 分支):
            // WebView 内容同样以居中对话框呈现, 与表单登录视觉一致。
            else -> LoginScreen(
                state = state,
                actions = actions,
                platformWebViewSlot = { url ->
                    LocalWebViewSlot.current(
                        // 2026-08-06 功能保留: URL 登录必须带 isLogin (窗口"确定"= 确认 cookie
                        // 后 reload 检测, 对照原版 WebViewActivity isLogin 分支) + sourceKey (cookie
                        // 按书源回写, 登录态可复用)
                        WebViewConfig(
                            url = url,
                            isLogin = true,
                            sourceKey = state.source?.getKey() ?: "",
                            // URL 登录在原版同样走 WebViewActivity, 故视口设置与浏览器一致
                            wideViewPort = true,
                        ),
                        Modifier.fillMaxSize(),
                        WebViewCallbacks(),
                    )
                },
                webViewReloadKey = webViewReloadKey,
            )
        }
    }
}

/** 源解析期间对话框内的加载占位 (避免整窗闪白 / 表单源闪空 WebView 页)。 */
@Composable
private fun LoginLoadingPlaceholder() {
    val loadingText = stringResource(Res.string.loading)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = AppTheme.colors.accent,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(loadingText, color = AppTheme.colors.secondaryText)
    }
}

private data class SourceLoginPayload(val sourceUrl: String, val dataKey: String?)

private fun parseSourceLoginPayload(payload: String?): SourceLoginPayload {
    val map = decodeStringMapOrNull(payload)
    if (map != null && map.containsKey("url")) {
        return SourceLoginPayload(map["url"].orEmpty(), map["dataKey"])
    }
    // 旧格式兜底 (历史快照: 整个 payload 即 dataKey): 源对象只能靠上下文, 取不到即关闭
    return SourceLoginPayload("", payload)
}
