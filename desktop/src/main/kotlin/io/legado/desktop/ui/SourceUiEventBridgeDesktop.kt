package io.legado.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SourceUiRequest
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.FlowBus
import io.legado.app.utils.browseUrl
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.encodeStringMap
import kotlinx.coroutines.launch

/**
 * 桌面端书源 UI 事件桥 (对齐 app 端 SourceUiEventBridge)。
 *
 * app 端用 LifecycleHelp.currentActivity (AppCompatActivity) 做宿主转调 show*Dialog 扩展;
 * desktop 无 Activity, 改用 Compose 顶层状态 + FlowBus 订阅驱动弹窗显隐:
 * - [currentRequest] 持有当前待处理的 SourceUiRequest (null=无弹窗)
 * - [SourceUiEventBridgeHost] 由 DesktopApp 顶层调用, 订阅 FlowBus 并按请求类型弹出对应 Dialog
 *
 * 触发源: BaseSource.showLoginDialog()/showSourceVariableDialog() (供 JS 调用) 经
 * FlowBus(SOURCE_UI_REQUEST) 发出 SourceUiRequest.Login / SourceVariable。
 */
object DesktopSourceUiEventBridge {
    val currentRequest = mutableStateOf<SourceUiRequest?>(null)
}

/**
 * 桌面端 SourceUiRequest 订阅 + 弹窗渲染宿主。
 *
 * 由 DesktopApp 顶层调用 (与各 Provider 平级), 生命周期跟随 Composable:
 * - LaunchedEffect(Unit) 订阅 FlowBus, 收到 SourceUiRequest 写入 [DesktopSourceUiEventBridge.currentRequest]
 * - 按 Login/SourceVariable 分发到 sharedUiMain 的 SourceLoginDialog / VariableDialog
 * - 确认/取消均清空 currentRequest 隐藏弹窗
 */
@Composable
fun SourceUiEventBridgeHost() {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.SOURCE_UI_REQUEST).collect { event ->
            if (event is SourceUiRequest) {
                DesktopSourceUiEventBridge.currentRequest.value = event
            }
        }
    }

    val request = DesktopSourceUiEventBridge.currentRequest.value ?: return
    // BaseSource 抽象, 实例恒为 BookSource; 安全转换防御异常输入, 失败时清空请求避免卡死
    val src = request.source as? BookSource ?: run {
        DesktopSourceUiEventBridge.currentRequest.value = null
        return
    }

    when (request) {
        is SourceUiRequest.Login -> {
            // 登录逻辑由 shared SourceLoginDialog 内部处理 (putLoginInfo + login JS),
            // 与 app 端一致; 桌面端仅需注入 onOpenUrl 回调
            SourceLoginDialog(
                source = src,
                onDismiss = { DesktopSourceUiEventBridge.currentRequest.value = null },
                onOpenUrl = { url -> browseUrl(url) },
            )
        }
        is SourceUiRequest.SourceVariable -> {
            // 与 BookSourceEditScreen 一致: bookVariables 传空 Map (无具体书籍),
            // sourceVariables 从 getVariable() 解析; 确认后 setVariable 写回 (SourceCacheProviders 持久化)
            VariableDialog(
                sourceVariables = decodeStringMapOrNull(src.getVariable()) ?: emptyMap(),
                bookVariables = emptyMap(),
                onConfirm = { newSourceVars, _ ->
                    scope.launch {
                        src.setVariable(encodeStringMap(newSourceVars))
                    }
                    DesktopSourceUiEventBridge.currentRequest.value = null
                },
                onDismiss = {
                    DesktopSourceUiEventBridge.currentRequest.value = null
                },
            )
        }
    }
}
