package io.legado.app.ui.rss

import io.legado.app.data.entities.BaseSource

/**
 * native (iOS/鸿蒙) 的 RSS 拦截 JS `java` 绑定。
 *
 * 与 JVM 半区同构: [BaseSource] 接口委托保留分派能力 (NativeJsExtensionsBridge 按
 * methodId 表分派需要 BaseSource 身份), 再叠加 [RssJsApi] 的 searchBook/addBook。
 * 桥接时经 [io.legado.app.model.script.NativeJsExtensionsBridge] 的 `__createRssJsObj`
 * 工厂 (createJsObject 的 RssJsApi 分支), 两个新方法走 methodId 1610/1611 分派,
 * 其余方法面与 BaseSource 属性照常走既有分派表。拦截 JS 与 Android 端等价可用。
 */
private class RssJsExtensionsNative(
    private val source: BaseSource,
    actions: RssJsApi,
) : BaseSource by source, RssJsApi by actions {

    override fun getSource(): BaseSource? = source
}

actual fun createRssJsBinding(source: BaseSource, actions: RssJsApi): Any =
    RssJsExtensionsNative(source, actions)
