package io.legado.app.help.ui

import kotlin.concurrent.Volatile

/**
 * 打开链接能力 provider (跨平台抽象)。
 *
 * 原 app 端 `JsExtensions.openUrl` 的 UI 依赖 (弹跳转确认对话框) 经此接口抽象,
 * 由 app 端 [AndroidOpenUrlProvider] 在 App.onCreate 经 [registerAndroidOpenUrlProvider]
 * 注册, 供 shared/commonMain 的 `JsExtensionsCommon` 回调。
 *
 * 模式参考 `VerificationUiProvider` / `VerificationUiProviders`。
 */
interface OpenUrlProvider {

    /**
     * 打开链接 (对应 app 端 `OpenUrlConfirmDialog.display`)。
     *
     * @param url 要打开的链接
     * @param mimeType MIME 类型, 可空
     * @param sourceKey 源 key (用于 disable/delete 源)
     * @param sourceTag 源 tag (用于对话框副标题)
     * @param sourceType 源类型 (默认 [io.legado.app.constant.SourceType.book])
     */
    fun openUrl(
        url: String,
        mimeType: String? = null,
        sourceKey: String? = null,
        sourceTag: String? = null,
        sourceType: Int = 0
    )
}

/**
 * [OpenUrlProvider] 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `OpenUrlProviders.get().openUrl(...)` 替代原
 * `OpenUrlConfirmDialog.display(...)`, 仅多一层 provider 间接。
 */
object OpenUrlProviders {
    @Volatile
    private var impl: OpenUrlProvider? = null

    /** 宿主启动早期注册一次 (任何 JsExtensionsCommon.openUrl 调用之前)。 */
    fun register(impl: OpenUrlProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): OpenUrlProvider = impl ?: error("OpenUrlProviders not registered")
}
