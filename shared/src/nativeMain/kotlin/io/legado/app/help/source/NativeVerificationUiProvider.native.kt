package io.legado.app.help.source

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.SourceUiRequest
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.toast.Toasters
import io.legado.app.help.ui.OpenUrlProviders
import io.legado.app.utils.FlowBus

/**
 * nativeMain: [VerificationUiProvider] 的 iOS/鸿蒙实现 (对照 desktop
 * DesktopVerificationUiProvider)。
 *
 * - 图片验证码: 发 [SourceUiRequest.VerificationCode] 事件, 由各端 SourceUiEventBridgeHost
 *   弹 sharedUiMain 的 Compose VerificationCodeDialog 采集并回填结果
 *   (原"暂不支持"toast+抛异常已升级为共享件消费; iOS ImageBitmapLoader 暂 stub,
 *   图片区降级显示 URL, 输入流程完整可用);
 * - 网页验证 (需回传网页源码, `saveResult == true`): 内置 WebView 未下沉,
 *   给明确文案后抛异常 (先于 registerWaitingThread, 不会陷入无限轮询等待);
 * - 纯打开链接: 走 [OpenUrlProviders] 系统浏览器。
 */
object NativeVerificationUiProvider : VerificationUiProvider {

    override fun showVerificationCodeDialog(url: String, source: BaseSource) {
        // 与 BaseSource.showLoginDialog 同总线; 调用方随后 registerWaitingThread 轮询等待,
        // UI 确认/关闭时经 SourceVerificationHelpShared.setResult 回填, 轮询超时自然取到
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
        // native 暂无 BottomSheet 容器, asBottomSheet 降级为普通打开 (忽略半屏语义)
        if (saveResult == true) {
            val msg = "该平台暂不支持此验证方式(需内置浏览器回传网页源码): $title"
            runCatching { Toasters.get().toastLong(msg) }
            throw NoStackTraceException(msg)
        }
        // 纯打开链接: 与 desktop browseUrl 分支同语义, 走系统浏览器
        OpenUrlProviders.get().openUrl(url)
    }
}

/**
 * 注册 [NativeVerificationUiProvider] (iOS/鸿蒙共用)。
 * 未注册时 JsExtensionsCommon 验证入口裸抛 "not registered" IllegalStateException。
 */
fun registerNativeVerificationUiProvider() {
    VerificationUiProviders.register(NativeVerificationUiProvider)
}
