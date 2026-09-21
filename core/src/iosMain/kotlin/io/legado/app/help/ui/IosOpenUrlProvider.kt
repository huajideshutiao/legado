package io.legado.app.help.ui

import io.legado.app.help.openURL

/**
 * [OpenUrlProvider] iOS 端实现。
 *
 * 转发 [openURL] (UIApplication.sharedApplication.openURL), 与鸿蒙侧
 * OhosOpenUrlProviderImpl 交给系统打开的语义一致; iOS 系统 openURL 只接 URL,
 * mimeType/sourceKey/sourceTag/sourceType 无对应物故未用。
 * 在 iOS 宿主启动早期经 [registerIosOpenUrlProvider] 注册到 [OpenUrlProviders]。
 */
object IosOpenUrlProviderImpl : OpenUrlProvider {

    override fun openUrl(
        url: String,
        mimeType: String?,
        sourceKey: String?,
        sourceTag: String?,
        sourceType: Int
    ) {
        openURL(url)
    }
}

/** iOS 宿主启动早期注册一次, 任何 JsExtensionsCommon.openUrl 调用之前。 */
fun registerIosOpenUrlProvider() {
    OpenUrlProviders.register(IosOpenUrlProviderImpl)
}
