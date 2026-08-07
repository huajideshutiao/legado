package io.legado.app.help.ui

import io.legado.app.help.IntentData
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.OpenUrlConfirmPayload
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay

/**
 * [OpenUrlProvider] 的 app 端实现。
 *
 * 跳转确认对话框已下沉 sharedUiMain (key="openUrlConfirm"): 这里经 AppOverlay 弹出,
 * 确认后由共享层调 PlatformCapabilities.openExternalUrl(url, mimeType) 打开链接。
 * 在 App.onCreate 经 [registerAndroidOpenUrlProvider] 注册到 [OpenUrlProviders]。
 */
object AndroidOpenUrlProvider : OpenUrlProvider {

    override fun openUrl(
        url: String,
        mimeType: String?,
        sourceKey: String?,
        sourceTag: String?,
        sourceType: Int
    ) {
        val navigator = AppNavigatorProviders.getOrNull()
        if (navigator == null) {
            // 对照原版 LifecycleHelp.currentActivity 为空时的 toast
            Toasters.get().toast("无法在后台显示跳转确认对话框")
            return
        }
        navigator.showOverlay(
            AppOverlay.Dialog(
                key = "openUrlConfirm",
                payload = IntentData.put(
                    OpenUrlConfirmPayload(
                        url = url,
                        mimeType = mimeType,
                        sourceKey = sourceKey,
                        sourceTag = sourceTag,
                        sourceType = sourceType,
                    )
                ),
            )
        )
    }
}

/**
 * 注册 app 端 [AndroidOpenUrlProvider] 到 [OpenUrlProviders]。
 *
 * 在 App.onCreate 早期调用一次, 任何 JsExtensionsCommon.openUrl 调用之前。
 */
fun registerAndroidOpenUrlProvider() {
    OpenUrlProviders.register(AndroidOpenUrlProvider)
}
