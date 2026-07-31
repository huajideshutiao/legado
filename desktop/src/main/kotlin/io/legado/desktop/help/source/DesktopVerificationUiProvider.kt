package io.legado.desktop.help.source

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.SourceUiRequest
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.SourceVerificationHelpShared
import io.legado.app.help.source.VerificationUiProvider
import io.legado.app.help.source.VerificationUiProviders
import io.legado.app.help.ui.ToastProviders
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.utils.FlowBus
import io.legado.app.utils.browseUrl
import io.legado.desktop.help.webview.DesktopWebViewEngines
import io.legado.desktop.help.webview.WebViewWindowHandle
import io.legado.desktop.help.webview.WebViewWindowRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [VerificationUiProvider] 桌面端实现。
 *
 * - 图片验证码: 发 [SourceUiRequest.VerificationCode] 事件, 由 SourceUiEventBridgeHost
 *   弹 sharedUiMain 的 Compose VerificationCodeDialog 采集并回填结果;
 * - 网页验证 (`saveResult == true`): 用 [DesktopWebViewEngines] 的内嵌浏览器开独立窗口,
 *   语义对照 app 端 `WebViewModel.saveVerificationResult` —— 每次导航完成同步 cookie,
 *   用户关窗后按 refetchAfterSuccess 决定"用新 cookie 重新 HTTP 请求"还是"回传网页源码";
 *   引擎不可用时退回原先的报错文案 (调用方 runCatching, 不崩);
 * - 纯打开链接 (`saveResult != true`): 走系统默认浏览器, 与 app 端"仅打开"语义一致。
 */
object DesktopVerificationUiProvider : VerificationUiProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun showVerificationCodeDialog(url: String, source: BaseSource) {
        // 与 BaseSource.showLoginDialog 同总线; 调用方随后 registerWaitingThread 轮询等待,
        // UI 确认/关闭时经 SourceVerificationHelpShared.setResult + notifyResultArrived 唤醒
        FlowBus.with(EventBus.SOURCE_UI_REQUEST)
            .tryEmit(SourceUiRequest.VerificationCode(source, url))
    }

    override fun startBrowser(
        source: BaseSource,
        url: String,
        title: String,
        saveResult: Boolean?,
        refetchAfterSuccess: Boolean?,
        asBottomSheet: Boolean,
    ) {
        // desktop 无 BottomSheet 容器, asBottomSheet 降级为普通打开 (忽略半屏语义)
        if (saveResult != true) {
            browseUrl(url)
            return
        }
        val engine = DesktopWebViewEngines.get() ?: run {
            val guide = DesktopWebViewEngines.installGuide()?.message.orEmpty()
            val msg = "桌面端暂不支持该验证方式(需内置浏览器回传网页源码): $title\n$guide"
            runCatching { ToastProviders.get().showToast(msg, long = true) }
            throw NoStackTraceException(msg)
        }
        val sourceKey = source.getKey()
        // 关窗回调里要用到句柄本身 (取网页源码), 故先声明再赋值
        var handle: WebViewWindowHandle? = null
        handle = engine.openWindow(
            WebViewWindowRequest(
                url = url,
                title = "$title - 完成验证后关闭本窗口",
                cookieTag = sourceKey,
                onClosed = {
                    scope.launch {
                        saveVerificationResult(
                            sourceKey = sourceKey,
                            url = url,
                            refetchAfterSuccess = refetchAfterSuccess != false,
                            html = { handle?.currentHtml() },
                        )
                    }
                },
            )
        ) ?: run {
            val msg = "内置浏览器窗口打开失败: $title"
            runCatching { ToastProviders.get().showToast(msg, long = true) }
            throw NoStackTraceException(msg)
        }
    }

    /**
     * 对照 app 端 `WebViewModel.saveVerificationResult`:
     * refetchAfterSuccess 时用刚拿到的 cookie 重新走 HTTP 拉一次 (不再过 webView),
     * 否则直接回传窗口里的网页源码; 两条路都以 setResult + 唤醒等待线程收尾。
     */
    private suspend fun saveVerificationResult(
        sourceKey: String,
        url: String,
        refetchAfterSuccess: Boolean,
        html: suspend () -> String?,
    ) {
        val result = runCatching {
            if (refetchAfterSuccess) {
                val bookSource = AppDbProviders.get().bookSourceDao.getBookSource(sourceKey)
                AnalyzeUrlFactories.create(url, source = bookSource)
                    .getStrResponseAwait(allowWebView = false).body
            } else {
                html()
            }
        }.onFailure { AppLog.put("书源验证结果获取失败", it) }.getOrNull()
        // 对齐 app 端 checkResult: 拿不到也要回填空串, 否则等待线程会一直挂着
        SourceVerificationHelpShared.setResult(sourceKey, result ?: "")
        SourceVerificationHelpShared.notifyResultArrived(sourceKey)
    }
}

/** 桌面端 main 入口注册一次, 任何书源验证流程之前。 */
fun registerDesktopVerificationUiProvider() {
    VerificationUiProviders.register(DesktopVerificationUiProvider)
}
