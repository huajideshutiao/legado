package io.legado.app.ui.rss

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.JsExtensionsCommon

/**
 * JVM 半区 (Android/桌面) 的 RSS 拦截 JS `java` 绑定, 对照 app 端 `RssJsExtensions`。
 *
 * 直接实现 [JsExtensionsCommon] (ajax/get/post/加密工厂等全量 JS 面, 含 getSource()),
 * 再叠加 [RssJsApi] 的 searchBook/addBook 两个 RSS 专属方法。
 */
private class RssJsExtensionsJvm(
    private val source: BaseSource,
    actions: RssJsApi,
) : JsExtensionsCommon, RssJsApi by actions {

    override fun getSource(): BaseSource? = source
}

actual fun createRssJsBinding(source: BaseSource, actions: RssJsApi): Any =
    RssJsExtensionsJvm(source, actions)
