package io.legado.desktop.help.source

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.SourceUiRequest
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.VerificationUiProvider
import io.legado.app.help.source.VerificationUiProviders
import io.legado.app.help.ui.ToastProviders
import io.legado.app.utils.FlowBus
import io.legado.app.utils.browseUrl

/**
 * [VerificationUiProvider] 桌面端实现。
 *
 * - 图片验证码: 发 [SourceUiRequest.VerificationCode] 事件, 由 SourceUiEventBridgeHost
 *   弹 sharedUiMain 的 Compose VerificationCodeDialog 采集并回填结果
 *   (原 Swing JOptionPane 自实现已收敛为共享件消费);
 * - 网页验证 (需回传网页源码, `saveResult == true`): 桌面端无内置 WebView 取不到源码,
 *   给明确文案后抛 [NoStackTraceException], 避免未注册时的 IllegalStateException 裸抛;
 * - 纯打开链接 (`saveResult != true`): 走系统默认浏览器, 与 app 端"仅打开"语义一致。
 */
object DesktopVerificationUiProvider : VerificationUiProvider {

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
        refetchAfterSuccess: Boolean?
    ) {
        if (saveResult == true) {
            val msg = "桌面端暂不支持该验证方式(需内置浏览器回传网页源码): $title"
            runCatching { ToastProviders.get().showToast(msg, long = true) }
            throw NoStackTraceException(msg)
        }
        browseUrl(url)
    }
}

/** 桌面端 main 入口注册一次, 任何书源验证流程之前。 */
fun registerDesktopVerificationUiProvider() {
    VerificationUiProviders.register(DesktopVerificationUiProvider)
}
