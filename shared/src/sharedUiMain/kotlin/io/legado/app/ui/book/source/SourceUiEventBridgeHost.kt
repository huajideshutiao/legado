package io.legado.app.ui.book.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SourceUiRequest
import io.legado.app.help.IntentData
import io.legado.app.help.SourceLoginContext
import io.legado.app.help.source.SourceVerificationHelpShared
import io.legado.app.help.sourceLoginOverlayPayload
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.encodeSourceVariableOverlayPayload
import io.legado.app.utils.FlowBus

/**
 * shared 统一书源 UI 事件桥宿主 (零薄壳方案 §3.1/§11, LegadoApp 统一管理 Overlay)。
 *
 * 替代 desktop/ios/ohos 三端独立副本: 订阅 FlowBus(SOURCE_UI_REQUEST),
 * 承接 JS 的 source.showLoginDialog()/showSourceVariableDialog()/验证码请求,
 * 按类型弹 shared Dialog; 打开 URL 统一走 OpenUrlProviders (desktop 的 browseUrl 为 JVM 专属)。
 *
 * 由各端 Compose 根 (DesktopApp/MainViewController/MainOhos) 顶层调用, 与各 Provider 平级,
 * 生命周期跟随 Composable。
 */
@Composable
fun SourceUiEventBridgeHost() {
    val currentRequest = remember { mutableStateOf<SourceUiRequest?>(null) }

    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.SOURCE_UI_REQUEST).collect { event ->
            if (event is SourceUiRequest) {
                currentRequest.value = event
            }
        }
    }

    val request = currentRequest.value ?: return

    // 图片验证码: 不限 BookSource (RssSource 验证同样走此路径), 先于 BookSource 转换处理
    if (request is SourceUiRequest.VerificationCode) {
        val key = request.source.getKey()
        VerificationCodeDialog(
            url = request.url,
            source = request.source,
            onConfirm = { code ->
                SourceVerificationHelpShared.setResult(key, code)
                SourceVerificationHelpShared.notifyResultArrived(key)
                currentRequest.value = null
            },
            onDismiss = {
                // 对齐 app 端 checkResult: 未确认关闭回填空串 (等待方走"验证结果为空")
                SourceVerificationHelpShared.getResult(key)
                    ?: SourceVerificationHelpShared.setResult(key, "")
                SourceVerificationHelpShared.notifyResultArrived(key)
                currentRequest.value = null
            },
        )
        return
    }

    // 登录: 不限 BookSource (HttpTTS/RSS 源同样要能登录), 先于 BookSource 转换处理。
    // book/chapter 取调用方预置的上下文 (对照原版 IntentData.nowBook/nowChapter), 喂给登录 JS。
    // 纯 Overlay 弹登录对话框 (表单/URL 两分支由 SourceLoginOverlayContent 统一分发),
    // 由 LegadoApp 统一渲染 EditDialogHost 包裹的登录对话框,
    // 避免在此根级直接渲染纯 Column 导致覆盖整个 LegadoApp (看起来像新开界面)。
    if (request is SourceUiRequest.Login) {
        LaunchedEffect(request) {
            // IntentData 一次性消费: 暂存 source/book/chapter, 弹窗渲染时按 key 取回
            val dataKey = SourceLoginContext.put(
                source = request.source,
                book = IntentData.book,
                chapter = IntentData.chapter,
            )
            val navigator = AppNavigatorProviders.getOrNull()
            if (navigator != null) {
                // 纯 Overlay 弹登录对话框, 不推新路由 (对照原版 showLoginDialog)
                navigator.showOverlay(
                    AppOverlay.Dialog(
                        key = "sourceLogin",
                        payload = sourceLoginOverlayPayload(request.source.getKey(), dataKey),
                    )
                )
            }
            currentRequest.value = null
        }
        return
    }

    // BaseSource 抽象, 实例恒为 BookSource; 转换失败清空请求避免卡死
    val src = request.source as? BookSource ?: run {
        currentRequest.value = null
        return
    }

    when (request) {
        is SourceUiRequest.SourceVariable -> {
            // 对照原版 BaseSource.showSourceVariableDialog: 经 AppOverlay 弹 shared
            // SourceVariableDialog (初始值 = getVariable() 原文, 注释 = variableComment + 提示语,
            // 确定后 setVariable 原样写回, 不解析不校验; 全部逻辑见 VariableOverlayDialog.kt)
            LaunchedEffect(request) {
                AppNavigatorProviders.getOrNull()?.showOverlay(
                    AppOverlay.Dialog(
                        key = "sourceVariable",
                        payload = encodeSourceVariableOverlayPayload(src),
                    )
                )
                currentRequest.value = null
            }
        }
        // 上方 early-return 已处理, 以下分支不可达 (sealed when 需穷尽)
        is SourceUiRequest.Login,
        is SourceUiRequest.VerificationCode -> Unit
    }
}
