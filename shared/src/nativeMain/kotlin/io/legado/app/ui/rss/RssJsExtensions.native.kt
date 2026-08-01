package io.legado.app.ui.rss

import io.legado.app.data.entities.BaseSource

/**
 * native (iOS/鸿蒙) 的 RSS 拦截 JS `java` 绑定。
 *
 * native 的 JS 桥 (`NativeJsExtensionsBridge`) 按 methodId 表分派, 表里没有
 * searchBook/addBook, 加装饰类也桥不出去, 故直接返回 [source] ——
 * 与 native 端 `JsExtFactory.wrap` 的做法一致, 拦截 JS 本身照常执行。
 */
actual fun createRssJsBinding(source: BaseSource, actions: RssJsApi): Any = source
