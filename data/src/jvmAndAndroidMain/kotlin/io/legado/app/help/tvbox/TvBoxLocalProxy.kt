package io.legado.app.help.tvbox

import com.github.catvod.Proxy
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.AppLog
import java.io.InputStream

/**
 * TVBox 本地代理 (FongMi Server/Nano + process/Proxy 同构): 监听 /proxy 与 /file,
 * jar/JS spider 构造的 `http://127.0.0.1:{port}/proxy?do=…` 播放与资源链路由此进站。
 *
 * 分发语义对齐 FongMi BaseLoader.proxy, 由调用方注入 dispatcher (解耦 model 层):
 * 带 siteKey → 按站点 key 找 Spider 实例; 否则交 jar 自带
 * com.github.catvod.spider.Proxy.proxy(Map) 静态方法 (do 值由 jar 自己定义, 如 B站的 "bili")。
 *
 * `/file/` 对齐 FongMi server/process/Local.java: 把 `Path.local()` 下的本地文件
 * 当 HTTP 资源回给 spider (生态配置里已有 `http://127.0.0.1:9978/file/TV/x.txt` 形态的
 * ext, 如 csp_FourKHDR/csp_Hdh)。
 *
 * 端口自 9978 起逐个尝试 (FongMi Server.start 同语义), 成功后回填 catvod Proxy 与
 * [TvBoxJsProxy] 两侧, 此后 spider 构造的代理地址才可达。
 */
object TvBoxLocalProxy {

    private const val TAG = "TvBoxProxy"
    private const val PORT_FIRST = 9978
    private const val PORT_LAST = 9998

    @Volatile
    private var nano: ProxyNano? = null

    /** 起本地代理并回填两侧端口接线; 幂等 (已起直接返回)。 */
    @Synchronized
    fun start(dispatcher: (Map<String, String>) -> Array<Any?>?) {
        if (nano != null) return
        for (port in PORT_FIRST..PORT_LAST) {
            val server = ProxyNano(port, dispatcher)
            try {
                server.start(500)
            } catch (e: Throwable) {
                AppLog.put("$TAG: :$port 起失败: ${e.message}")
                continue
            }
            nano = server
            Proxy.set(port)
            TvBoxJsProxy.port = port
            TvBoxJsProxy.urlProvider = { local -> Proxy.getUrl(local) }
            AppLog.put("$TAG: 本地代理已启动 :$port")
            return
        }
        AppLog.put("$TAG: 启动失败, $PORT_FIRST..$PORT_LAST 均不可用")
    }

    /** 停本地代理并复位端口接线 (配置清除场景; 未起时静默)。 */
    @Synchronized
    fun stop() {
        nano?.let { runCatching { it.stop() } }
        nano = null
        Proxy.set(-1)
        TvBoxJsProxy.port = 0
        TvBoxJsProxy.urlProvider = null
    }

    private class ProxyNano(
        port: Int,
        private val dispatcher: (Map<String, String>) -> Array<Any?>?,
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): NanoHTTPD.Response {
            val uri = session.uri.orEmpty().trim()
            if (uri == FILE_PATH || uri.startsWith("$FILE_PATH/")) {
                return try {
                    fileResponse(uri)
                } catch (e: Throwable) {
                    AppLog.put("$TAG: /file 服务失败: ${e.message}")
                    plain(NanoHTTPD.Response.Status.NOT_FOUND, e.message ?: e.toString())
                }
            }
            if (!uri.startsWith("/proxy")) {
                return plain(NanoHTTPD.Response.Status.NOT_FOUND, "TVBox proxy: unsupported path $uri")
            }
            return try {
                responseOf(dispatcher(paramsOf(session)))
            } catch (e: Throwable) {
                AppLog.put("$TAG: /proxy 分发失败: ${e.message}")
                plain(NanoHTTPD.Response.Status.INTERNAL_ERROR, e.message ?: e.toString())
            }
        }

        /** 参数合并 (FongMi process/Proxy 同序): query → headers → POST 表单/文件。 */
        private fun paramsOf(session: IHTTPSession): Map<String, String> {
            val params = LinkedHashMap<String, String>()
            for ((key, values) in session.parameters) {
                params[key] = values.firstOrNull().orEmpty()
            }
            params.putAll(session.headers)
            if (session.method == NanoHTTPD.Method.POST) {
                val files = HashMap<String, String>()
                runCatching { session.parseBody(files) }
                params.putAll(files)
            }
            return params
        }

        /** spider 返回面 → 响应 (FongMi process/Proxy.createResponse 同语义):
         *  [code, mime, stream] 必需, 第 4 位可选 headers; 首位已是 Response 时原样透传。 */
        private fun responseOf(rs: Array<Any?>?): NanoHTTPD.Response {
            if (rs.isNullOrEmpty()) return plain(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Invalid proxy response")
            (rs[0] as? NanoHTTPD.Response)?.let { return it }
            if (rs.size < 3) return plain(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Invalid proxy response")
            val response = NanoHTTPD.newChunkedResponse(
                statusOf(rs[0] as Int),
                rs[1] as String,
                rs[2] as InputStream,
            )
            if (rs.size > 3 && rs[3] != null) {
                for ((key, value) in rs[3] as Map<*, *>) {
                    response.addHeader(key.toString(), value.toString())
                }
            }
            return response
        }

        private fun statusOf(code: Int): NanoHTTPD.Response.IStatus {
            NanoHTTPD.Response.Status.lookup(code)?.let { return it }
            if (code !in 100..599) return NanoHTTPD.Response.Status.INTERNAL_ERROR
            return object : NanoHTTPD.Response.IStatus {
                override fun getRequestStatus() = code
                override fun getDescription() = "$code Proxy Status"
            }
        }

        private fun plain(status: NanoHTTPD.Response.Status, text: String): NanoHTTPD.Response =
            NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text)

        /**
         * `/file/<path>` → 本地文件响应 (FongMi `Local.getFile` 同语义)。
         *
         * 路径按 [com.github.catvod.utils.Path.local] 解析 (FongMi 同源: 先试 root 下, 不存在再按
         * 原路径), 只服务文件 (目录列表是 FongMi 的 WebDAV/文件管理面, spider 取数链路不需要),
         * 目录请求如实 404。目录不存在/不可读同样如实报错, 不静默回空。
         */
        private fun fileResponse(uri: String): NanoHTTPD.Response {
            val path = java.net.URLDecoder.decode(uri.removePrefix(FILE_PATH), "UTF-8")
            val file = com.github.catvod.utils.Path.local(path)
            if (!file.isFile) {
                throw java.io.FileNotFoundException("File not found: $path")
            }
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                NanoHTTPD.getMimeTypeForFile(path),
                java.io.FileInputStream(file),
                file.length(),
            )
        }
    }

    /** FongMi `server/process/Local.FILE` 同值。 */
    private const val FILE_PATH = "/file"
}
