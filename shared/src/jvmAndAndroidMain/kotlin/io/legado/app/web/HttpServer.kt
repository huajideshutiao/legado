package io.legado.app.web

import fi.iki.elonen.NanoHTTPD
import io.legado.app.api.ReturnData
import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.stackTraceStr
import io.legado.app.web.api.WebApi
import io.legado.app.web.api.WebApiRequest
import io.legado.app.web.api.WebApiResponse
import io.legado.app.web.utils.AssetsWeb
import kotlinx.coroutines.runBlocking
import okio.Pipe
import okio.buffer
import java.io.ByteArrayInputStream

/**
 * nanohttpd 薄壳: IHTTPSession -> WebApiRequest -> WebApi.handle -> WebApiResponse -> Response。
 * 分派逻辑已上移至 [WebApi]; 本类只做原生请求转换、CORS、大列表流式与静态资源回写。
 *
 * # 下沉说明 (原 app 端 io.legado.app.web.HttpServer)
 * - `WebService.serve()` (app Android Service 续命) → [WebServerManager.serve] (commonMain)
 * - `LogUtils.d(TAG) { ... }` (app 专属) → [AppLog.put] (commonMain, 已下沉)
 * - 其余逻辑逐字等价, 保真红线
 */
class HttpServer(port: Int) : NanoHTTPD(port) {
    private val assetsWeb = AssetsWeb("web")

    override fun serve(session: IHTTPSession): Response {
        // 拉起 Service 续命 (app 端) / no-op (桌面端)
        WebServerManager.serve()
        val ct = ContentType(session.headers["content-type"]).tryUTF8()
        session.headers["content-type"] = ct.contentTypeHeader
        val uri = session.uri
        val origin = session.headers["origin"]

        val startAt = System.currentTimeMillis()
        AppLog.put("$TAG: ${session.method.name} - $uri - ${session.queryParameterString} - Start($startAt)")

        // OPTIONS 预检: 直接回, 不进路由层
        if (session.method == Method.OPTIONS) {
            val response = newFixedLengthResponse("")
            response.addHeader("Access-Control-Allow-Methods", "POST")
            response.addHeader("Access-Control-Allow-Headers", "content-type")
            response.addHeader("Access-Control-Allow-Origin", origin)
            return response
        }

        try {
            val files = HashMap<String, String>()
            val postData: String?
            if (session.method == Method.POST) {
                session.parseBody(files)
                postData = files["postData"]
            } else {
                postData = null
            }

            val request = WebApiRequest(
                method = session.method.name,
                path = uri,
                query = session.parameters,
                postData = postData,
                files = files,
                origin = origin,
            )

            val response = when (val apiResponse = runBlocking { WebApi.handle(request) }) {
                is WebApiResponse.StaticAsset -> {
                    // AssetsWeb 已下沉 commonMain, 返回 WebAssetResponse (bytes + mime)
                    val asset = runBlocking { assetsWeb.getResponse(apiResponse.path) }
                    return newChunkedResponse(
                        NanoHTTPD.Response.Status.OK,
                        asset.mimeType,
                        ByteArrayInputStream(asset.bytes)
                    )
                }
                is WebApiResponse.Bytes -> {
                    val inputStream = ByteArrayInputStream(apiResponse.bytes)
                    newFixedLengthResponse(
                        Response.Status.OK,
                        apiResponse.contentType,
                        inputStream,
                        apiResponse.bytes.size.toLong()
                    )
                }

                is WebApiResponse.Json -> {
                    val returnData = apiResponse.returnData
                    val data = returnData.data
                    if (data is List<*> && data.size > 3000) {
                        val pipe = Pipe(16 * 1024)
                        Coroutine.async {
                            pipe.sink.buffer().outputStream().bufferedWriter(Charsets.UTF_8).use {
                                // Phase D: GSON.toJson(returnData, it) 流式写入 → toJsonString() 一次性写入
                                it.write(returnData.toJsonString())
                            }
                        }
                        newChunkedResponse(
                            Response.Status.OK,
                            "application/json",
                            pipe.source.buffer().inputStream()
                        )
                    } else {
                        newFixedLengthResponse(returnData.toJsonString())
                    }
                }
            }
            response.addHeader("Access-Control-Allow-Methods", "GET, POST")
            response.addHeader("Access-Control-Allow-Origin", origin)
            AppLog.put("$TAG: ${session.method.name} - $uri - ${session.queryParameterString} - End($startAt)")
            return response
        } catch (e: Exception) {
            AppLog.put(
                "$TAG: ${session.method.name} - $uri - ${session.queryParameterString} - Error End($startAt)\n$e\n${e.stackTraceStr}",
                e
            )
            return newFixedLengthResponse(e.message)
        }
    }

    companion object {
        private const val TAG = "HttpServer"
    }

}
