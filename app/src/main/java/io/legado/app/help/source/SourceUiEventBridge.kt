package io.legado.app.help.source

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SourceUiRequest
import io.legado.app.help.IntentData
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.showSourceLogin
import io.legado.app.ui.association.VerificationCodeDialog
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.encodeSourceVariableOverlayPayload
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 书源 JS 弹窗事件桥。
 * BaseSource(entity, 零安卓渗漏) 发 [SourceUiRequest], 这里在主线程拿 currentActivity
 * 转调 app 侧 show*Dialog/Overlay。语义对齐原 BaseSource.show*Dialog()(LifecycleHelp.currentActivity)。
 */
object SourceUiEventBridge {

    fun init() {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            FlowBus.with(EventBus.SOURCE_UI_REQUEST).collect { event ->
                if (event !is SourceUiRequest) return@collect
                // 源变量弹窗已下沉 shared: 经 sourceVariable Overlay 弹出, 不再需要 Activity 上下文;
                // 其余分支 (登录/验证码) 需要当前 Activity, 故仍保留此守卫
                if (LifecycleHelp.currentActivity !is AppCompatActivity) return@collect
                when (event) {
                    // 统一登录入口 (shared): URL 登录时平台直开全屏 WebView
                    // (openLoginWebView 默认推 AppRoute.WebView isLogin=true 路由, 桌面端独立窗口),
                    // 不弹 sourceLogin Overlay; 表单登录 (loginUi 非空) 仍弹 Overlay 表单
                    is SourceUiRequest.Login -> showSourceLogin(
                        event.source.getKey(),
                        event.source,
                        // 与 shared SourceUiEventBridgeHost 统一签名: 读调用方预置的
                        // IntentData.book/chapter 供登录 JS 上下文 (对照原版 nowBook/nowChapter)
                        IntentData.book,
                        IntentData.chapter,
                    )
                    is SourceUiRequest.SourceVariable -> {
                        // VariableDialog 已下沉 shared: 经 sourceVariable Overlay 弹出
                        // (对照原版 BaseSource.showSourceVariableDialog, 非 BookSource 不弹)
                        val source = event.source as? BookSource ?: return@collect
                        AppNavigatorProviders.getOrNull()?.showOverlay(
                            AppOverlay.Dialog(
                                key = "sourceVariable",
                                payload = encodeSourceVariableOverlayPayload(source),
                            )
                        )
                    }
                    // Android 端验证码不经事件总线 (VerificationUiProviderImpl 直调
                    // VerificationCodeDialog.display), 此分支仅保证 sealed when 穷尽
                    is SourceUiRequest.VerificationCode -> VerificationCodeDialog.display(
                        event.url, event.source.getKey(), event.source.getTag(),
                        event.source.getSourceType()
                    )
                }
            }
        }
    }
}
