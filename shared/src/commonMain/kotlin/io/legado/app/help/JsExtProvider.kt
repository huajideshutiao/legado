package io.legado.app.help

import io.legado.app.data.entities.BaseSource
import kotlin.concurrent.Volatile

/**
 * F2: BookSource/HttpTTS 实体下沉解除 JsExtensions 继承依赖。
 *
 * 实体类 (BookSource/HttpTTS) 下沉到 shared 后不再继承 app 端 [io.legado.app.help.JsExtensions],
 * app 端启动时通过 [JsExtProviders.register] 注册 [JsExtFactory],
 * [BaseSource.evalJS] 注入 bindings["java"]/["source"] 时调用 [wrap] 取得包装器
 * (BookSourceJsExt/HttpTTSJsExt), 让 JS 调用 source.ajax(...) 等方法时
 * 走 JsExtensionsJsDispatcher 反射分派 (包装器实现 JsExtensions)。
 *
 * 返回 [Any] 而非 JsExtensions, 避免 shared jvmAndAndroidMain 引用 app-only 接口。
 * 兜底: 已是 JsExtensions 的对象 (如 AnalyzeUrl/AnalyzeRule) 直接返回原对象。
 */
object JsExtProviders {
    @Volatile
    private var factory: JsExtFactory? = null

    fun register(factory: JsExtFactory) {
        this.factory = factory
    }

    fun get(): JsExtFactory = factory ?: error("JsExtFactory 未注册")
}

interface JsExtFactory {
    /**
     * 包装 [source] 为 JsExtensions 实现对象 (BookSourceJsExt/HttpTTSJsExt)。
     * 若 [source] 本身已实现 JsExtensions (如 AnalyzeUrl/AnalyzeRule), 直接返回原对象。
     */
    fun wrap(source: BaseSource): Any
}
