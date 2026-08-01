@file:OptIn(ExperimentalNativeApi::class)

package io.legado.app.help.source

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.SourceUiRequest
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.toast.Toasters
import io.legado.app.help.ui.OpenUrlProviders
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.utils.FlowBus
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

/**
 * nativeMain: [VerificationUiProvider] 的 iOS/鸿蒙实现 (对照 desktop
 * DesktopVerificationUiProvider)。
 *
 * - 图片验证码: 发 [SourceUiRequest.VerificationCode] 事件, 由各端 SourceUiEventBridgeHost
 *   弹 sharedUiMain 的 Compose VerificationCodeDialog 采集并回填结果
 *   (原"暂不支持"toast+抛异常已升级为共享件消费; iOS ImageBitmapLoader 暂 stub,
 *   图片区降级显示 URL, 输入流程完整可用);
 * - 网页验证 (需回传网页源码, `saveResult == true`): iOS 推 [AppRoute.WebView] 走 shared
 *   WebViewRoute 的验证回传 (对照 app 端 VerificationUiProviderImpl), 参数含
 *   saveResult/refetchAfterSuccess 与书源信息, 由 WKWebView 完成 outerHTML/重拉回传;
 *   鸿蒙 WebView 仍为占位 (NAPI 桥接未接入), 保持明确报错 (TODO: 桥接完成移除);
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
            if (Platform.osFamily == OsFamily.IOS) {
                // iOS: 推 AppRoute.WebView 走 shared WebViewRoute 验证回传
                // (WKWebView 完成 outerHTML/重拉回传, 对照 app 端 VerificationUiProviderImpl)
                AppNavigatorProviders.getOrNull()?.push(
                    AppRoute.WebView(
                        url = url,
                        title = title,
                        sourceKey = source.getKey(),
                        sourceName = source.getTag(),
                        sourceType = source.getSourceType(),
                        saveResult = true,
                        refetchAfterSuccess = refetchAfterSuccess ?: true,
                    )
                )
            } else {
                // 鸿蒙: WebView 仍为占位 (NAPI 桥接未接入, 见 OhosWebViewStub),
                // 明确报错避免等待线程挂起; TODO(ohos): 桥接完成移除本分支
                val msg = "该平台暂不支持此验证方式(需内置浏览器回传网页源码): $title"
                runCatching { Toasters.get().toastLong(msg) }
                throw NoStackTraceException(msg)
            }
            return
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
