package io.legado.app.data.entities

import io.legado.app.help.JsExtensions

/**
 * F2: BookSource 下沉到 shared 后不再继承 app 端 JsExtensions, 包装器补回 JS 可见的 JsExtensions 面。
 *
 * - [BaseSource] 接口委托 ([BaseSource by source]) 保留 BaseSourceJsDispatcher 分派能力
 *   (target as BaseSource cast 成功), 同时 var 属性 (header/concurrentRate 等) getter/setter
 *   转发到 [source], JS 直接 source.setHeader(...) 仍修改原 BookSource 实例。
 * - [JsExtensions] 接口默认实现自动应用 (ajax/connect/webView/log 等), 内部调用 getSource()
 *   返回 [source] (BookSource), 与原 BookSource (override getSource() = this) 行为一致。
 * - [log] override 走 super<JsExtensions>.log, 与原 BookSource (override log = super<JsExtensions>.log)
 *   行为一致 (含 jsContextOrNull?.ensureActive() + Debug.log + AppLog.putDebug)。
 *
 * 包装器实例由 [io.legado.app.help.JsExtProviders] 在 [BaseSource.evalJS] 注入 bindings["java"]/["source"],
 * JS 调用 source.xxx(...) 时:
 * - BaseSource 方法 (getKey/getHeaderMap/evalJS 等) → BaseSourceJsDispatcher → 委托 source
 * - JsExtensions 方法 (ajax/connect/webView 等) → 反射 fallback (因 BaseSourceJsDispatcher 优先匹配)
 *   → BookSourceJsExt.ajax 默认实现 → getSource() = source → AnalyzeUrl(source = source)
 *
 * KSP 分派表零 diff: BaseSource/JsExtensions 接口本身未改, 生成的 dispatcher 方法集不变。
 */
class BookSourceJsExt(val source: BookSource) : BaseSource by source, JsExtensions {

    override fun getSource(): BaseSource? = source

    override fun log(msg: Any?): Any? = super<JsExtensions>.log(msg)
}
