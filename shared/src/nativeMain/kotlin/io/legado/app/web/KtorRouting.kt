package io.legado.app.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.legado.app.constant.AppLog
import io.legado.app.utils.stackTraceStr
import io.legado.app.web.api.WebApi
import io.legado.app.web.api.WebApiRequest
import io.legado.app.web.api.WebApiResponse
import io.legado.app.web.utils.AssetsWeb

/**
 * Ktor HTTP routing 配置: ApplicationCall → [WebApiRequest] → [WebApi.handle] → [WebApiResponse] → 原生响应。
 *
 * 对齐 [HttpServer.serve] (jvmAndAndroidMain NanoHTTPD 壳) 的三态响应 + CORS, 逐字等价:
 * - OPTIONS 预检直接回 (不进路由层)
 * - StaticAsset → [AssetsWeb.getResponse] → respondBytes
 * - Bytes → respondBytes
 * - Json → respondText (大列表流式优化暂不做, Ktor 自动 chunked)
 */
fun Application.configureHttpRouting(assetsWeb: AssetsWeb) {
    routing {
        // OPTIONS 预检: 直接回, 不进路由层 (对齐 HttpServer OPTIONS 分支)
        options("/{path...}") {
            val origin = call.request.headers["origin"]
            call.response.headers.append("Access-Control-Allow-Methods", "POST")
            call.response.headers.append("Access-Control-Allow-Headers", "content-type")
            call.response.headers.append("Access-Control-Allow-Origin", origin ?: "*")
            call.respondText("")
        }
        // API + 静态资源 (对齐 HttpServer serve GET/POST 分支)
        get("/{path...}") { handleApiCall(call, assetsWeb) }
        post("/{path...}") { handleApiCall(call, assetsWeb) }
    }
}

/** 处理单次 API/静态资源请求 (对齐 HttpServer.serve try 块)。 */
private suspend fun handleApiCall(call: ApplicationCall, assetsWeb: AssetsWeb) {
    // 拉起 Service 续命 (app 端) / no-op (桌面/iOS/鸿蒙端)
    WebServerManager.serve()
    val origin = call.request.headers["origin"]
    val startAt = kotlin.system.getTimeMillis()
    val uri = call.request.path()

    try {
        val request = call.toWebApiRequest()
        val apiResponse = WebApi.handle(request)

        when (apiResponse) {
            is WebApiResponse.StaticAsset -> {
                val asset = assetsWeb.getResponse(apiResponse.path)
                call.respondBytes(asset.bytes, ContentType.parse(asset.mimeType))
            }
            is WebApiResponse.Bytes -> {
                call.respondBytes(apiResponse.bytes, ContentType.parse(apiResponse.contentType))
            }
            is WebApiResponse.Json -> {
                call.respondText(
                    apiResponse.returnData.toJsonString(),
                    ContentType.Application.Json.withCharset(Charsets.UTF_8)
                )
            }
        }
        call.response.headers.append("Access-Control-Allow-Methods", "GET, POST")
        call.response.headers.append("Access-Control-Allow-Origin", origin ?: "*")
        AppLog.put("KtorHttp: ${request.method} - $uri - End($startAt)")
    } catch (e: Exception) {
        AppLog.put(
            "KtorHttp: - $uri - Error End($startAt)\n$e\n${e.stackTraceStr}",
            e
        )
        call.respondText(
            e.message ?: "",
            ContentType.Text.Plain,
            HttpStatusCode.InternalServerError
        )
    }
}

/** ApplicationCall → WebApiRequest (对齐 HttpServer 的 IHTTPSession → WebApiRequest 转换)。 */
private suspend fun ApplicationCall.toWebApiRequest(): WebApiRequest {
    val method = request.httpMethod.value
    val path = request.path()
    val origin = request.headers["origin"]
    val query = request.queryParameters.toMap()

    if (method == "POST") {
        val ct = request.contentType()
        if (ct.match(ContentType.MultiPart.FormData)) {
            // multipart/form-data: 解析文件 + 表单字段 (对齐 HttpServer session.parseBody(files))
            val result = parseMultipart()
            val mergedQuery = query.toMutableMap()
            result.formFields.forEach { (k, v) -> mergedQuery[k] = v }
            return WebApiRequest(method, path, mergedQuery, null, result.files, origin)
        }
        // JSON / form-urlencoded: 读 raw body (对齐 HttpServer files["postData"])
        val postData = receiveText()
        return WebApiRequest(method, path, query, postData, emptyMap(), origin)
    }

    return WebApiRequest(method, path, query, null, emptyMap(), origin)
}
