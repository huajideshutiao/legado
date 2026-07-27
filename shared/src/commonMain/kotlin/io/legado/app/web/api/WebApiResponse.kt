package io.legado.app.web.api

import io.legado.app.api.ReturnData

/**
 * 平台无关的 web 响应模型 (零 android import)。
 * 传输编码 (JSON 序列化/流式/静态资源读取) 留给各平台壳。
 *
 * 下沉 commonMain: 仅依赖 [ReturnData] (已下沉), sealed interface + data class 纯 Kotlin, 消费方 import 不变。
 */
sealed interface WebApiResponse {

    /** 命中路由的原始 ReturnData (ContentProvider 直接 Gson 序列化本字段) */
    val returnData: ReturnData?

    /** 普通 JSON 结果; 壳按 data 是否为大 List 决定定长/流式 */
    data class Json(override val returnData: ReturnData) : WebApiResponse

    /**
     * 原始字节 + mime (封面/正文图, image/png)。
     * 同时保留 [returnData], 供 ContentProvider 走 Gson 序列化 (行为等价旧实现)。
     */
    class Bytes(
        val bytes: ByteArray,
        val contentType: String,
        override val returnData: ReturnData,
    ) : WebApiResponse

    /** 非 API 路由, 回退到平台静态资源 */
    data class StaticAsset(val path: String) : WebApiResponse {
        override val returnData: ReturnData? get() = null
    }
}
