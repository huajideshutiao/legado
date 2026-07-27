package io.legado.app.data.entities

import io.legado.app.help.JsExtensions

/**
 * F2: HttpTTS 下沉到 shared 后不再继承 app 端 JsExtensions, 包装器补回 JS 可见的 JsExtensions 面。
 *
 * 设计与 [BookSourceJsExt] 一致, 详见其文档。
 *
 * KSP 分派表零 diff: BaseSource/JsExtensions 接口本身未改, 生成的 dispatcher 方法集不变。
 */
class HttpTTSJsExt(val source: HttpTTS) : BaseSource by source, JsExtensions {

    override fun getSource(): BaseSource? = source

    override fun log(msg: Any?): Any? = super<JsExtensions>.log(msg)
}
