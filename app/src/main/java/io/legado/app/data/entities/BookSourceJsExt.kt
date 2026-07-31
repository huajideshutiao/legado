package io.legado.app.data.entities

import com.script.jsdispatch.JsApi
import io.legado.app.help.JsExtensionsJvm

/**
 * F2: BookSource 下沉到 shared 后不再继承 app 端 JsExtensions, 包装器补回 JS 可见的
 * [JsExtensionsJvm] 面 (shared jvmAndAndroidMain, 含 jsoup get/head/post + 加密工厂)。
 *
 * - [BaseSource] 接口委托 ([BaseSource by source]) 保留 BaseSourceJsDispatcher 分派能力
 *   (target as BaseSource cast 成功), 同时 var 属性 (header/concurrentRate 等) getter/setter
 *   转发到 [source], JS 直接 source.setHeader(...) 仍修改原 BookSource 实例。
 * - [JsExtensionsJvm] 接口默认实现自动应用 (ajax/connect/webView/log 等), 内部调用 getSource()
 *   返回 [source] (BookSource), 与原 BookSource (override getSource() = this) 行为一致。
 * - [log] override 走 super<JsExtensionsJvm>.log, 与原 BookSource (override log = super<JsExtensions>.log)
 *   行为一致 (含 jsContextOrNull?.ensureActive() + Debug.log + AppLog.putDebug)。
 *
 * 包装器实例由 [io.legado.app.help.JsExtProviders] 在 [BaseSource.evalJS] 注入 bindings["java"]/["source"]。
 *
 * @JsApi 标注: BookSourceJsExt 同时实现 BaseSource 和 JsExtensionsJvm 两个兄弟接口 (仅前者标 @JsApi),
 * 自身标 @JsApi 后 KSP 生成 BookSourceJsExtJsDispatcher, 含 BaseSource + JsExtensionsJvm
 * 全部方法, forClass 直接命中, java.post(...) 走 dispatcher 快速路径 (参数经 JsCoerce 正确转换)。
 */
@JsApi
class BookSourceJsExt(val source: BookSource) : BaseSource by source, JsExtensionsJvm {

    override fun getSource(): BaseSource? = source

    override fun log(msg: Any?): Any? = super<JsExtensionsJvm>.log(msg)
}
