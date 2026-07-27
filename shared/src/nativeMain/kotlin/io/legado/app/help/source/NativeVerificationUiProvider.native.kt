package io.legado.app.help.source

import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.toast.Toasters
import io.legado.app.help.ui.OpenUrlProviders

/**
 * nativeMain: [VerificationUiProvider] 的 iOS/鸿蒙最小实现 (对照 desktop
 * DesktopVerificationUiProvider)。VerificationCodeDialog/内置 WebView 均未下沉,
 * 不支持的路径给明确文案后抛异常 (先于 registerWaitingThread, 不会陷入无限轮询等待);
 * 纯打开链接走 [OpenUrlProviders] 系统浏览器。
 */
object NativeVerificationUiProvider : VerificationUiProvider {

    override fun showVerificationCodeDialog(url: String, source: BaseSource) {
        val msg = "该平台暂不支持图片验证码验证: ${source.getTag()}"
        runCatching { Toasters.get().toastLong(msg) }
        throw NoStackTraceException(msg)
    }

    override fun startBrowser(
        source: BaseSource,
        url: String,
        title: String,
        saveResult: Boolean?,
        refetchAfterSuccess: Boolean?
    ) {
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
