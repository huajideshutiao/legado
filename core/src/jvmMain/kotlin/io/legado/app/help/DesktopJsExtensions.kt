package io.legado.app.help

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HttpTTS

/**
 * 桌面端 BookSource/HttpTTS 的 JS 扩展包装器, 对应 app 端 [io.legado.app.data.entities.BookSourceJsExt]/
 * [io.legado.app.data.entities.HttpTTSJsExt]: 直接继承 shared 的 [JsExtensionsJvm]
 * (jsoup get/head/post + JsEncodeUtilsDefaults 加密工厂 + JsExtensionsCommon 全量面)。
 *
 * 原本文件的 DesktopJsExtensions 接口 (与 app 端 JsExtensions 逐字重复的 get/head/post 与加密工厂)
 * 已下沉 jvmAndAndroidMain 删除, 此处仅剩包装器与注册函数。
 */
@Suppress("unused")
class DesktopBookSourceJsExt(val source: BookSource) : BaseSource by source, JsExtensionsJvm {

    override fun getSource(): BaseSource? = source

    override fun log(msg: Any?): Any? = super<JsExtensionsJvm>.log(msg)
}

/** 对应 app 端 [io.legado.app.data.entities.HttpTTSJsExt], 设计同 [DesktopBookSourceJsExt]。 */
class DesktopHttpTtsJsExt(val source: HttpTTS) : BaseSource by source, JsExtensionsJvm {

    override fun getSource(): BaseSource? = source

    override fun log(msg: Any?): Any? = super<JsExtensionsJvm>.log(msg)
}

/**
 * 桌面端 main 入口注册: [BaseSource.evalJS] 注入 bindings["java"]/["source"] 时取包装器。
 *
 * 注册函数留在 shared jvmMain, 使 desktop 模块无需看见 hutool 返回类型。
 */
fun registerDesktopJsExtFactory() {
    JsExtProviders.register(object : JsExtFactory {
        override fun wrap(source: BaseSource): Any = when (source) {
            is BookSource -> DesktopBookSourceJsExt(source)
            is HttpTTS -> DesktopHttpTtsJsExt(source)
            // 兜底: 已实现 JsExtensionsCommon 的其他对象直接返回 (与 app 端一致)
            else -> source
        }
    })
}
