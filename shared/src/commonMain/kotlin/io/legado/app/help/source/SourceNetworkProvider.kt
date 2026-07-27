package io.legado.app.help.source

object SourceNetworkProviders {
    @Volatile var impl: SourceNetworkProvider? = null
}

interface SourceNetworkProvider {
    fun getCookie(tag: String): String
    fun replaceCookie(tag: String, cookie: String)
    fun removeCookie(tag: String)
    /** 返回用于 JS 绑定的底层对象（保留原 @JsApi 分派表） */
    fun asBinding(): Any = this
}
