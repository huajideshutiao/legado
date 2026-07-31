package io.legado.app.ui.book.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SourceUiRequest
import io.legado.app.help.IntentData
import io.legado.app.help.SourceLoginContext
import io.legado.app.help.source.SourceVerificationHelpShared
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.FlowBus
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.encodeStringMap
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
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
    // 走 Overlay 栈由 LegadoApp 统一渲染 EditDialogHost 包裹的 SourceLoginDialog,
    // 避免在此根级直接渲染纯 Column 导致覆盖整个 LegadoApp (看起来像新开界面)。
    if (request is SourceUiRequest.Login) {
        LaunchedEffect(request) {
            // IntentData 一次性消费: 暂存 source/book/chapter, 弹窗/路由渲染时按 key 取回
            val dataKey = SourceLoginContext.put(
                source = request.source,
                book = IntentData.book,
                chapter = IntentData.chapter,
            )
            val navigator = AppNavigatorProviders.getOrNull()
            if (navigator != null) {
                if (request.source.loginUi.isNullOrEmpty()) {
                    // URL 登录: 对照原版 showLoginDialog 的 WebViewActivity 分支, 开登录页
                    navigator.push(AppRoute.Login(request.source.getKey(), dataKey))
                } else {
                    // 表单登录: 对照原版 showDialogFragment<SourceLoginDialog>, Overlay 弹对话框
                    navigator.showOverlay(
                        AppOverlay.Dialog(key = "sourceLogin", payload = dataKey)
                    )
                }
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
            // 与 BookSourceEditScreen 一致: bookVariables 传空 Map (无具体书籍),
            // sourceVariables 从 getVariable() 解析; 确认后 setVariable 写回
            VariableDialog(
                sourceVariables = decodeStringMapOrNull(src.getVariable()) ?: emptyMap(),
                bookVariables = emptyMap(),
                onConfirm = { newSourceVars, _ ->
                    scope.launch {
                        src.setVariable(encodeStringMap(newSourceVars))
                    }
                    currentRequest.value = null
                },
                onDismiss = {
                    currentRequest.value = null
                },
            )
        }
        // 上方 early-return 已处理, 以下分支不可达 (sealed when 需穷尽)
        is SourceUiRequest.Login,
        is SourceUiRequest.VerificationCode -> Unit
    }
}
