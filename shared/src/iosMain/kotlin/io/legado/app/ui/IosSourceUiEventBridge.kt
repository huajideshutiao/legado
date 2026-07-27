package io.legado.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SourceUiRequest
import io.legado.app.help.source.SourceVerificationHelpShared
import io.legado.app.help.ui.OpenUrlProviders
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.book.source.VerificationCodeDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.FlowBus
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.encodeStringMap
import kotlinx.coroutines.launch

/**
 * iOS 端书源 UI 事件桥 (对照 desktop SourceUiEventBridgeDesktop)。
 *
 * 订阅 FlowBus(SOURCE_UI_REQUEST), 承接 JS 的 source.showLoginDialog()/
 * showSourceVariableDialog(), 按请求类型弹 SourceLoginDialog/VariableDialog;
 * onOpenUrl 走 OpenUrlProviders (desktop 的 browseUrl 为 JVM 专属)。
 */
object IosSourceUiEventBridge {
    val currentRequest = mutableStateOf<SourceUiRequest?>(null)
}

/** 由 MainViewController Compose 根调用, 与各 Provider 平级, 生命周期跟随 Composable。 */
@Composable
fun SourceUiEventBridgeHost() {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.SOURCE_UI_REQUEST).collect { event ->
            if (event is SourceUiRequest) {
                IosSourceUiEventBridge.currentRequest.value = event
            }
        }
    }

    val request = IosSourceUiEventBridge.currentRequest.value ?: return

    // 图片验证码: 不限 BookSource (RssSource 验证同样走此路径), 先于 BookSource 转换处理
    if (request is SourceUiRequest.VerificationCode) {
        val key = request.source.getKey()
        VerificationCodeDialog(
            url = request.url,
            source = request.source,
            onConfirm = { code ->
                SourceVerificationHelpShared.setResult(key, code)
                SourceVerificationHelpShared.notifyResultArrived(key)
                IosSourceUiEventBridge.currentRequest.value = null
            },
            onDismiss = {
                // 对齐 app 端 checkResult: 未确认关闭回填空串 (等待方走"验证结果为空")
                SourceVerificationHelpShared.getResult(key)
                    ?: SourceVerificationHelpShared.setResult(key, "")
                SourceVerificationHelpShared.notifyResultArrived(key)
                IosSourceUiEventBridge.currentRequest.value = null
            },
        )
        return
    }

    // BaseSource 抽象, 实例恒为 BookSource; 转换失败清空请求避免卡死
    val src = request.source as? BookSource ?: run {
        IosSourceUiEventBridge.currentRequest.value = null
        return
    }

    when (request) {
        is SourceUiRequest.Login -> {
            SourceLoginDialog(
                source = src,
                onDismiss = { IosSourceUiEventBridge.currentRequest.value = null },
                onOpenUrl = { url -> OpenUrlProviders.get().openUrl(url) },
            )
        }
        is SourceUiRequest.SourceVariable -> {
            // 与 desktop 一致: bookVariables 传空 Map (无具体书籍), 确认后 setVariable 写回
            VariableDialog(
                sourceVariables = decodeStringMapOrNull(src.getVariable()) ?: emptyMap(),
                bookVariables = emptyMap(),
                onConfirm = { newSourceVars, _ ->
                    scope.launch {
                        src.setVariable(encodeStringMap(newSourceVars))
                    }
                    IosSourceUiEventBridge.currentRequest.value = null
                },
                onDismiss = {
                    IosSourceUiEventBridge.currentRequest.value = null
                },
            )
        }
        // 上方 early-return 已处理, 此分支不可达 (sealed when 需穷尽)
        is SourceUiRequest.VerificationCode -> Unit
    }
}
