package io.legado.app.data.entities

import com.script.jsdispatch.JsApi
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
 * 包装器实例由 [io.legado.app.help.JsExtProviders] 在 [BaseSource.evalJS] 注入 bindings["java"]/["source"]。
 *
 * @JsApi 标注: BookSourceJsExt 同时实现 BaseSource 和 JsExtensions 两个兄弟接口 (均标 @JsApi),
 * JsDispatchRegistry.forClass 的"最具体"选择在兄弟接口间无法决出胜者, 会按注册顺序固定返回
 * BaseSourceJsDispatcher, 其 methodNames 不含 post/get/head/ajax 等 JsExtensions 方法, 导致
 * java.post(...) 走反射 fallback (参数 coercion 与 dispatcher 路径不一致, 触发 argument type
 * mismatch)。标 @JsApi 后 KSP 生成 BookSourceJsExtJsDispatcher, 含 BaseSource + JsExtensions
 * 全部方法, forClass 直接命中, java.post(...) 走 dispatcher 快速路径 (参数经 JsCoerce 正确转换)。
 */
@JsApi
class BookSourceJsExt(val source: BookSource) : BaseSource by source, JsExtensions {

    override fun getSource(): BaseSource? = source

    override fun log(msg: Any?): Any? = super<JsExtensions>.log(msg)
}
