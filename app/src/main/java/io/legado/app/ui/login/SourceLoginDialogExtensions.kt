package io.legado.app.ui.login

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.IntentData
import io.legado.app.help.SourceLoginContext
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute

/**
 * 登录导航入口 (原 BaseSource.showLoginDialog 扩展, 重命名避免与成员函数同名遮蔽)。
 * JS 的 source.showLoginDialog() 调成员函数发事件, SourceUiEventBridge 收到后转调此扩展。
 *
 * 表单/WebView 两条分支的选择交给 LoginRoute (对照原版 showLoginDialog), 此处只负责把源对象
 * 与调用方预置的 book/chapter 上下文 (原版 IntentData.nowBook/nowChapter) 随路由带过去 ——
 * HttpTTS 等源不在 bookSourceDao 里, 只传 url 目标页查不到。
 */
fun BaseSource.navigateToLogin() {
    // 非 Composable 取全局 navigator (LegadoApp 组合时注册)
    val navigator = AppNavigatorProviders.getOrNull() ?: return
    if (loginUi.isNullOrEmpty() && loginUrl.isNullOrBlank()) return
    val dataKey = SourceLoginContext.put(this, IntentData.book, IntentData.chapter)
    navigator.push(AppRoute.Login(getKey(), dataKey))
}
