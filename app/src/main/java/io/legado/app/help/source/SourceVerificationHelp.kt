package io.legado.app.help.source

import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.ui.association.VerificationCodeDialog
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.utils.isMainThread

/**
 * 源验证 (app 端薄壳)。
 *
 * 核心流程 (缓存 key 生成 / setResult / getResult / clearResult / 内存轮询等待 /
 * 注册并唤醒等待线程) 下沉到 [SourceVerificationHelpShared] (shared commonMain),
 * 供 desktop/iOS/鸿蒙 复用。
 *
 * UI 部分 (VerificationCodeDialog.display / 推送 AppRoute.WebView) 经
 * [VerificationUiProvider] 注入, 本文件 [VerificationUiProviderImpl] 为 app 端实现,
 * 在 App.onCreate 经 [registerAndroidVerificationUiProvider] 注册。
 *
 * 调用方 import 不变 (包名 `io.legado.app.help.source.SourceVerificationHelp` 保持)。
 * 行为与下沉前完全一致, 仅位置迁移 + 依赖抽象。
 */
object SourceVerificationHelp {

    /**
     * 获取书源验证结果
     * 图片验证码 防爬 滑动验证码 点击字符 等等
     */
    @Synchronized
    fun getVerificationResult(
        source: BaseSource?,
        url: String,
        title: String,
        useBrowser: Boolean,
        refetchAfterSuccess: Boolean = true
    ): String {
        source
            ?: throw NoStackTraceException("getVerificationResult parameter source cannot be null")
        require(url.length < 64 * 1024) { "getVerificationResult parameter url too long" }
        check(!isMainThread) { "getVerificationResult must be called on a background thread" }

        clearResult(source.getKey())

        if (!useBrowser) {
            VerificationUiProviders.get().showVerificationCodeDialog(url, source)
            SourceVerificationHelpShared.registerWaitingThread(source.getKey())
        } else {
            startBrowser(source, url, title, true, refetchAfterSuccess)
        }

        return SourceVerificationHelpShared.waitVerificationResult(source.getKey())
    }

    /**
     * 启动内置浏览器
     * @param saveResult 保存网页源代码到数据库
     */
    fun startBrowser(
        source: BaseSource?,
        url: String,
        title: String,
        saveResult: Boolean? = false,
        refetchAfterSuccess: Boolean? = true,
        asBottomSheet: Boolean = false,
    ) {
        source ?: throw NoStackTraceException("startBrowser parameter source cannot be null")
        require(url.length < 64 * 1024) { "startBrowser parameter url too long" }
        VerificationUiProviders.get()
            .startBrowser(source, url, title, saveResult, refetchAfterSuccess, asBottomSheet)
        SourceVerificationHelpShared.registerWaitingThread(source.getKey())
    }


    fun checkResult(sourceKey: String) {
        SourceVerificationHelpShared.getResult(sourceKey) ?: SourceVerificationHelpShared.setResult(sourceKey, "")
        SourceVerificationHelpShared.notifyResultArrived(sourceKey)
    }

    fun setResult(sourceKey: String, result: String?) {
        SourceVerificationHelpShared.setResult(sourceKey, result)
    }

    fun getResult(sourceKey: String): String? {
        return SourceVerificationHelpShared.getResult(sourceKey)
    }

    fun clearResult(sourceKey: String) {
        SourceVerificationHelpShared.clearResult(sourceKey)
    }
}

/**
 * [VerificationUiProvider] 的 app 端实现。
 *
 * 委托 [VerificationCodeDialog.display] / [AppNavigatorProviders] 推送 [AppRoute.WebView],
 * 在 App.onCreate 经 [registerAndroidVerificationUiProvider] 注册
 * 到 [VerificationUiProviders]。
 */
object VerificationUiProviderImpl : VerificationUiProvider {

    override fun showVerificationCodeDialog(url: String, source: BaseSource) {
        VerificationCodeDialog.display(
            url,
            source.getKey(),
            source.getTag(),
            source.getSourceType()
        )
    }

    override fun startBrowser(
        source: BaseSource,
        url: String,
        title: String,
        saveResult: Boolean?,
        refetchAfterSuccess: Boolean?,
        asBottomSheet: Boolean,
    ) {
        val navigator = AppNavigatorProviders.getOrNull() ?: return
        if (asBottomSheet) {
            // BottomSheet 半屏方式打开 (对照 JsActivity BottomSheetDialog peekHeight=60%)
            navigator.showOverlay(AppOverlay.Sheet(key = "web_view", payload = url))
        } else {
            navigator.push(AppRoute.WebView(url))
        }
    }
}

/**
 * 注册 app 端 [VerificationUiProviderImpl] 到 [VerificationUiProviders]。
 *
 * 在 App.onCreate 早期 (registerAndroidWebBookProviders 中) 调用一次,
 * 任何 SourceVerificationHelp 调用之前。
 */
fun registerAndroidVerificationUiProvider() {
    VerificationUiProviders.register(VerificationUiProviderImpl)
}
