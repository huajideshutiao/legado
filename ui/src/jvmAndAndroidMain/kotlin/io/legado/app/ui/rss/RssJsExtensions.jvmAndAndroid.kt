package io.legado.app.ui.rss

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.JsExtensionsJvm

/**
 * JVM 半区 (Android/桌面) 的 RSS 拦截 JS `java` 绑定, 对照 app 端 `RssJsExtensions`。
 *
 * 结构照抄 `BookSourceJsExt`: [BaseSource] 接口委托保留分派能力,
 * [JsExtensionsJvm] 默认实现补回 ajax/get/post/加密工厂等全量 JS 面, 再加上
 * [RssJsApi] 的 searchBook/addBook 两个 RSS 专属方法。
 */
private class RssJsExtensionsJvm(
    private val source: BaseSource,
    actions: RssJsApi,
) : BaseSource by source, JsExtensionsJvm, RssJsApi by actions {

    override fun getSource(): BaseSource? = source

    override fun log(msg: Any?): Any? = super<JsExtensionsJvm>.log(msg)
}

actual fun createRssJsBinding(source: BaseSource, actions: RssJsApi): Any =
    RssJsExtensionsJvm(source, actions)
