package io.legado.app.help.ui

import io.legado.app.ui.association.OpenUrlConfirmDialog

/**
 * [OpenUrlProvider] 的 app 端实现。
 *
 * 委托原 [OpenUrlConfirmDialog.display], 行为与下沉前完全一致。
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
        OpenUrlConfirmDialog.display(
            url,
            mimeType,
            sourceKey,
            sourceTag,
            sourceType
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
