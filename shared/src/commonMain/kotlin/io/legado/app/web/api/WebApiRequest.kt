package io.legado.app.web.api

/**
 * 平台无关的 web 请求模型 (零 android import)。
 * nanohttpd/Ktor 壳各自把原生 session 转成本模型后交给 [WebApi.handle]。
 *
 * 下沉 commonMain: 仅纯 Kotlin 数据类, 无任何平台依赖, 消费方 import 不变。
 */
data class WebApiRequest(
    /** "GET" / "POST" / "OPTIONS" 等，取原生 method 名 */
    val method: String,
    /** 请求路径, 即 uri */
    val path: String,
    /** 查询/表单参数 (GET query 或 POST multipart 表单字段) */
    val query: Map<String, List<String>> = emptyMap(),
    /** POST body (nanohttpd 的 postData) */
    val postData: String? = null,
    /** multipart 上传文件字段 -> 临时文件路径 (addLocalBook 用) */
    val files: Map<String, String> = emptyMap(),
    /** CORS 回显用的 origin header */
    val origin: String? = null,
)
