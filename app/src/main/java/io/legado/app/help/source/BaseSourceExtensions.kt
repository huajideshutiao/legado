package io.legado.app.help.source

import com.script.quickjs.QuickJsContext
import io.legado.app.constant.SourceType
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.model.SharedJsScope
import kotlin.coroutines.CoroutineContext

fun BaseSource.getShareScope(coroutineContext: CoroutineContext? = null): QuickJsContext? {
    return SharedJsScope.getScope(jsLib, enableDangerousApi == true, coroutineContext)
}

fun BaseSource.getSourceType(): Int {
    return when (this) {
        is BookSource -> SourceType.book
        else -> error("unknown source type: ${this::class.simpleName}.")
    }
}
