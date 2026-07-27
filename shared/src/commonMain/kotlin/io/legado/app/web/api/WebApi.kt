package io.legado.app.web.api

import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.ReplaceRuleController

/**
 * 平台无关的 web API 路由层。
 *
 * 把原 HttpServer.serve 的 method/path when 分派上移至此 (11 GET + 11 POST 逐字等价)，
 * nanohttpd/Ktor/ContentProvider 各壳只负责「原生请求 -> [WebApiRequest] -> handle ->
 * [WebApiResponse] -> 原生响应」的薄适配。路径/参数名/错误分支零改动。
 */
object WebApi {

    suspend fun handle(request: WebApiRequest): WebApiResponse {
        val returnData: ReturnData? = when (request.method) {
            "POST" -> handlePost(request)
            "GET" -> handleGet(request)
            else -> null
        }

        if (returnData == null) {
            // 非 API 路由 -> 平台静态资源 (尾斜杠补 index.html)
            var path = request.path
            if (path.endsWith("/")) path += "index.html"
            return WebApiResponse.StaticAsset(path)
        }

        val data = returnData.data
        return if (data is ByteArray) {
            WebApiResponse.Bytes(data, "image/png", returnData)
        } else {
            WebApiResponse.Json(returnData)
        }
    }

    private suspend fun handlePost(request: WebApiRequest): ReturnData? {
        val postData = request.postData
        return when (request.path) {
            "/saveBookSource" -> BookSourceController.saveSource(postData)
            "/saveBookSources" -> BookSourceController.saveSources(postData)
            "/deleteBookSources" -> BookSourceController.deleteSources(postData)
            "/saveBook" -> BookController.saveBook(postData)
            "/deleteBook" -> BookController.deleteBook(postData)
            "/saveBookProgress" -> BookController.saveBookProgress(postData)
            "/addLocalBook" -> BookController.addLocalBook(request.query, request.files)
            "/saveReadConfig" -> BookController.saveWebReadConfig(postData)
            "/saveReplaceRule" -> ReplaceRuleController.saveRule(postData)
            "/deleteReplaceRule" -> ReplaceRuleController.delete(postData)
            "/testReplaceRule" -> ReplaceRuleController.testRule(postData)
            else -> null
        }
    }

    private suspend fun handleGet(request: WebApiRequest): ReturnData? {
        val parameters = request.query
        return when (request.path) {
            "/getBookSource" -> BookSourceController.getSource(parameters)
            "/getBookSources" -> BookSourceController.sources()
            "/getBookshelf" -> BookController.getBooks(parameters)
            "/getGroups" -> BookController.groups()
            "/getChapterList" -> BookController.getChapterList(parameters)
            "/refreshToc" -> BookController.refreshToc(parameters)
            "/getBookContent" -> BookController.getBookContent(parameters)
            "/cover" -> BookController.getCover(parameters)
            "/image" -> BookController.getImg(parameters)
            "/getReadConfig" -> BookController.getWebReadConfig()
            "/getReplaceRules" -> ReplaceRuleController.allRules()
            else -> null
        }
    }
}