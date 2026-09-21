package io.legado.app.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.toMap
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.writeFully
import io.legado.app.constant.AppLog
import io.legado.app.utils.stackTraceStr
import io.legado.app.web.api.WebApi
import io.legado.app.web.api.WebApiRequest
import io.legado.app.web.api.WebApiResponse
import io.legado.app.web.utils.AssetsWeb
import io.legado.app.utils.File
import io.legado.app.utils.systemCurrentTimeMillis

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
        // OPTIONS 预检: mediaStream 已永久下线，固定 410；其余接口按原规则回 CORS
        options("/{path...}") {
            val path = call.request.path()
            if (path == "/mediaStream") {
                call.response.headers.append("Cache-Control", "no-store")
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.respondText(
                    "Media stream proxy is gone",
                    ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                    HttpStatusCode.Gone,
                )
                return@options
            }
            val origin = call.request.headers["origin"]
            call.response.headers.append("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
            call.response.headers.append("Access-Control-Allow-Headers", "content-type")
            call.response.headers.append("Access-Control-Allow-Origin", origin ?: "*")
            call.respondText("")
        }
        // API + 静态资源 (对齐 HttpServer serve GET/POST 分支)
        get("/{path...}") { handleApiCall(call, assetsWeb) }
        post("/{path...}") { handleApiCall(call, assetsWeb) }
        head("/{path...}") { handleApiCall(call, assetsWeb) }
    }
}

/** 处理单次 API/静态资源请求 (对齐 HttpServer.serve try 块)。 */
private suspend fun handleApiCall(call: ApplicationCall, assetsWeb: AssetsWeb) {
    // 拉起 Service 续命 (app 端) / no-op (桌面/iOS/鸿蒙端)
    WebServerManager.serve()
    val origin = call.request.headers["origin"]
    val startAt = systemCurrentTimeMillis()
    val uri = call.request.path()
    var tempFiles: Collection<String> = emptyList()

    try {
        val request = call.toWebApiRequest()
        tempFiles = request.files.values
        val apiResponse = WebApi.handle(request)

        // CORS 头必须在 respond 之前写, 响应提交后追加无效。
        // mediaStream 已下线，刻意不附加 CORS，避免继续把它暴露为可用代理。
        if (request.path != "/mediaStream") {
            call.response.headers.append("Access-Control-Allow-Methods", "GET, POST")
            call.response.headers.append("Access-Control-Allow-Origin", origin ?: "*")
        }

        when (apiResponse) {
            is WebApiResponse.StaticAsset -> {
                val asset = assetsWeb.getResponse(apiResponse.path)
                call.respondBytes(asset.bytes, ContentType.parse(asset.mimeType))
            }
            is WebApiResponse.Stream -> {
                apiResponse.headers.forEach { (name, value) ->
                    if (!name.startsWith("Access-Control-", ignoreCase = true)) {
                        call.response.headers.append(name, value)
                    }
                }
                // 外层 finally 覆盖 respondBytesWriter 在进入 writer block 前即被取消的路径；
                // 对备份的 DeleteOnCloseInputStream 而言，close 同时负责删除独占临时 zip。
                try {
                    call.respondBytesWriter(
                        contentType = ContentType.parse(apiResponse.contentType),
                        status = HttpStatusCode.fromValue(apiResponse.statusCode)
                    ) {
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = apiResponse.inputStream.read(buffer)
                            if (read <= 0) break
                            writeFully(buffer, 0, read)
                        }
                    }
                } finally {
                    runCatching { apiResponse.inputStream.close() }
                }
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
        // 对齐原版 LogUtils.d(TAG){...} 的调试开关门控；410 下线路径是预期响应，不写异常/调试日志。
        if (request.path != "/mediaStream") {
            AppLog.putDebug("KtorHttp: ${request.method} - $uri - End($startAt)")
        }
    } catch (e: Exception) {
        AppLog.putDebug(
            "KtorHttp: - $uri - Error End($startAt)\n$e\n${e.stackTraceStr}",
            e
        )
        call.respondText(
            e.message ?: "",
            ContentType.Text.Plain,
            HttpStatusCode.InternalServerError
        )
    } finally {
        // 对齐 NanoHTTPD tempFileManager 请求结束即清理 multipart 临时文件
        tempFiles.forEach { runCatching { File(it).delete() } }
    }
}

/** ApplicationCall → WebApiRequest (对齐 HttpServer 的 IHTTPSession → WebApiRequest 转换)。 */
private suspend fun ApplicationCall.toWebApiRequest(): WebApiRequest {
    val method = request.httpMethod.value
    val path = request.path()
    val origin = request.headers["origin"]
    val query = request.queryParameters.toMap()
    val headers = mutableMapOf<String, String>()
    request.headers.forEach { name, values ->
        headers[name.lowercase()] = values.firstOrNull() ?: ""
    }

    if (method == "POST") {
        val ct = request.contentType()
        if (ct.match(ContentType.MultiPart.FormData)) {
            // multipart/form-data: 解析文件 + 表单字段 (对齐 HttpServer session.parseBody(files))
            val result = parseMultipart()
            val mergedQuery = query.toMutableMap()
            result.formFields.forEach { (k, v) -> mergedQuery[k] = v }
            return WebApiRequest(method, path, mergedQuery, null, result.files, origin, headers)
        }
        // JSON / form-urlencoded: 读 raw body (对齐 HttpServer files["postData"])
        val postData = receiveText()
        return WebApiRequest(method, path, query, postData, emptyMap(), origin, headers)
    }

    return WebApiRequest(method, path, query, null, emptyMap(), origin, headers)
}
