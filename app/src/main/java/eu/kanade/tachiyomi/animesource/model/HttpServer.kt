// Copyright The Aniyomi Contributors. Apache-2.0.
// 外部约束: 上游 HttpServer 继承 NanoHTTPD (宿主未引入该依赖), 此处仅保留类型面,
// 供 lib17 扩展 createHttpServer()/usesHttpServer() 引用的类链接不破; 不提供真实本地服务。
package eu.kanade.tachiyomi.animesource.model

open class HttpServer {

    val url: String
        get() = "http://localhost:$listeningPort"

    @Volatile
    private var isRunning = false

    private val listeningPort: Int = PLACEHOLDER_PORT

    fun isRunning(): Boolean {
        return isRunning
    }

    fun start() {
        isRunning = false
    }

    fun stop() {
        isRunning = false
    }

    companion object {
        const val PLACEHOLDER_URL = "http://localhost:1"
        private const val PLACEHOLDER_PORT = 0
    }
}
