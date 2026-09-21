package io.legado.app.help.ui

import io.legado.app.napi.OhosNativeBridge

/**
 * [OpenUrlProvider] 鸿蒙端实现。
 *
 * 经 [OhosNativeBridge.openUrl] tsfn 桥接到 ArkTS context.startAbility
 * (KMP 无 ArkTS API 访问能力, 需 tsfn 跨线程 dispatch 到 ArkTS 主线程;
 * 走相同 fire-and-forget tsfn 模式);
 * 未注册 tsfn 时由 [OhosNativeBridge] 丢弃命令 (兼容 napi 未接入阶段, 保证 JS 调用链不崩)。
 * 在 [registerOhosProviders] 经 [registerOhosOpenUrlProvider] 注册到 [OpenUrlProviders]。
 *
 * # 调用链
 * KMP 业务 (JsExtensionsCommon.openUrl) → [openUrl] → [OhosNativeBridge.openUrl] →
 * openUrlTsfn → C++ ohos_open_url_dispatch → napi_call_threadsafe_function →
 * ArkTS OpenUrlCallJs → SystemBridgeHandler.handleOpenUrl → context.startAbility(Want.uri=url)。
 */
object OhosOpenUrlProviderImpl : OpenUrlProvider {

    override fun openUrl(
        url: String,
        mimeType: String?,
        sourceKey: String?,
        sourceTag: String?,
        sourceType: Int
    ) {
        OhosNativeBridge.openUrl(url, mimeType, sourceKey, sourceTag, sourceType)
    }
}

/** 鸿蒙宿主启动早期注册一次, 任何 JsExtensionsCommon.openUrl 调用之前。 */
fun registerOhosOpenUrlProvider() {
    OpenUrlProviders.register(OhosOpenUrlProviderImpl)
}
