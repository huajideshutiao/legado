package io.legado.app.data.entities

import com.script.jsdispatch.JsApi
import io.legado.app.help.JsExtensionsJvm

/**
 * F2: HttpTTS 下沉到 shared 后不再继承 app 端 JsExtensions, 包装器补回 JS 可见的
 * [JsExtensionsJvm] 面 (shared jvmAndAndroidMain)。
 *
 * 设计与 [BookSourceJsExt] 一致, 详见其文档 (含 @JsApi 标注原因)。
 */
@JsApi
class HttpTTSJsExt(val source: HttpTTS) : BaseSource by source, JsExtensionsJvm {

    override fun getSource(): BaseSource? = source

    override fun log(msg: Any?): Any? = super<JsExtensionsJvm>.log(msg)
}
