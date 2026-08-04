package io.legado.app.ui.login

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.IntentData
import io.legado.app.help.SourceLoginContext
import io.legado.app.help.sourceLoginOverlayPayload
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay

/**
 * 登录导航入口 (原 BaseSource.showLoginDialog 扩展, 重命名避免与成员函数同名遮蔽)。
 * JS 的 source.showLoginDialog() 调成员函数发事件, SourceUiEventBridge 收到后转调此扩展。
 *
 * 表单/URL 两条分支统一由 sourceLogin Overlay (SourceLoginOverlayContent) 分发,
 * 此处只负责把源对象与调用方预置的 book/chapter 上下文 (原版 IntentData.nowBook/nowChapter)
 * 存入 [SourceLoginContext] 随 payload 带过去 —— HttpTTS 等源不在 bookSourceDao 里,
 * 只传 url 目标页查不到。纯 Overlay 弹对话框, 不推新路由。
 */
fun BaseSource.navigateToLogin() {
    // 非 Composable 取全局 navigator (LegadoApp 组合时注册)
    val navigator = AppNavigatorProviders.getOrNull() ?: return
    if (loginUi.isNullOrEmpty() && loginUrl.isNullOrBlank()) return
    val dataKey = SourceLoginContext.put(this, IntentData.book, IntentData.chapter)
    navigator.showOverlay(
        AppOverlay.Dialog(
            key = "sourceLogin",
            payload = sourceLoginOverlayPayload(getKey(), dataKey),
        )
    )
}
