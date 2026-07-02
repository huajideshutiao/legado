package io.legado.app.help.source

import io.legado.app.constant.SourceType
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.model.SharedJsScope
import io.legado.app.model.script.JsScope
import kotlin.coroutines.CoroutineContext

/**
 * 获取书源对应的共享 JS scope。
 *
 * 返回 [JsScope]（app 侧统一类型），由 [SharedJsScope] 按当前引擎分派到
 * rhino [io.legado.app.model.script.rhino.RhinoJsScope] 或
 * quickjs [io.legado.app.model.script.quickjs.QuickJsJsScope]。
 */
fun BaseSource.getShareScope(coroutineContext: CoroutineContext? = null): JsScope? {
    return SharedJsScope.getScope(jsLib, enableDangerousApi == true, coroutineContext)
}

fun BaseSource.getSourceType(): Int {
    return when (this) {
        is BookSource -> SourceType.book
        else -> error("unknown source type: ${this::class.simpleName}.")
    }
}
