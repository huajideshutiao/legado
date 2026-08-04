@file:OptIn(ExperimentalNativeApi::class)

package io.legado.app.help.source

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.SourceUiRequest
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
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
 * - 纯打开链接 (`saveResult != true`, 即 java.startBrowser): 对齐 app 端语义 —— 原版无论
 *   saveResult 都启动内置 WebViewActivity, 故同样推 [AppRoute.WebView] 打开内置浏览器
 *   (cookie 由平台 slot 回写; 鸿蒙 WebView 为占位, 显示待桥接提示);
 *   `asBottomSheet=true` (startBrowser(url, title, true)) 复用 shared "web_view"
 *   Sheet 以半屏底部弹窗打开 (对照原版 JsActivity BottomSheetDialog, 与 app 端
 *   VerificationUiProviderImpl 一致)。
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
        // 鸿蒙: WebView 仍为占位 (NAPI 桥接未接入, 见 OhosWebViewStub), 验证回传
        // 无法完成, 明确报错避免等待线程挂起; TODO(ohos): 桥接完成移除本分支
        if (saveResult == true && Platform.osFamily != OsFamily.IOS) {
            val msg = "该平台暂不支持此验证方式(需内置浏览器回传网页源码): $title"
            runCatching { Toasters.get().toastLong(msg) }
            throw NoStackTraceException(msg)
        }
        val navigator = AppNavigatorProviders.getOrNull() ?: return
        if (asBottomSheet && saveResult != true) {
            // BottomSheet 半屏方式打开 (对照原版 JsActivity BottomSheetDialog, 与 app 端
            // VerificationUiProviderImpl 一致): 复用 shared "web_view" Sheet, 由
            // SheetOverlayContent 承载平台 WebView slot (iOS 为 WKWebView, MainViewController
            // 注入, didFinish 同步 cookie), 不再推整页路由。
            // asBottomSheet=true 仅来自 JS startBrowser(url,title,asBottomSheet), 恒为
            // saveResult=false; 万一出现 saveResult==true 组合则落整页路由保留验证回传能力。
            navigator.showOverlay(AppOverlay.Sheet(key = "web_view", payload = url))
        } else {
            // 对齐 app 端 VerificationUiProviderImpl: 推 AppRoute.WebView 打开内置浏览器
            // (原版 startBrowser 无论 saveResult 都启动内置 WebViewActivity)
            navigator.push(
                AppRoute.WebView(
                    url = url,
                    title = title,
                    sourceKey = source.getKey(),
                    sourceName = source.getTag(),
                    sourceType = source.getSourceType(),
                    saveResult = saveResult == true,
                    refetchAfterSuccess = refetchAfterSuccess ?: true,
                )
            )
        }
    }
}

/**
 * 注册 [NativeVerificationUiProvider] (iOS/鸿蒙共用)。
 * 未注册时 JsExtensionsCommon 验证入口裸抛 "not registered" IllegalStateException。
 */
fun registerNativeVerificationUiProvider() {
    VerificationUiProviders.register(NativeVerificationUiProvider)
}
