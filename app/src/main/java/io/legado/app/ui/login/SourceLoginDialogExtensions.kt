package io.legado.app.ui.login

import io.legado.app.data.entities.BaseSource
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute

/**
 * 登录对话框导航入口 (原 BaseSource.showLoginDialog 扩展, 重命名避免与成员函数同名遮蔽)。
 * JS 的 source.showLoginDialog() 调成员函数发事件, SourceUiEventBridge 收到后转调此扩展。
 */
fun BaseSource.navigateToLogin() {
    // 非 Composable 取全局 navigator (LegadoApp 组合时注册)
    val navigator = AppNavigatorProviders.getOrNull() ?: return
    if (loginUi.isNullOrEmpty()) {
        if (!loginUrl.isNullOrBlank()) {
            // URL 登录走 WebView 路由
            navigator.push(AppRoute.WebView(loginUrl!!))
        }
    } else {
        // loginUi 登录走书源登录页路由
        navigator.push(AppRoute.Login(getKey()))
    }
}
