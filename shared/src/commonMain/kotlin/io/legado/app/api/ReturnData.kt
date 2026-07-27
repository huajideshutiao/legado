package io.legado.app.api

import io.legado.app.utils.toJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Web API / ContentProvider 统一返回结构 (shared commonMain 下沉版)。
 *
 * 原 app 端实现仅依赖 kotlinx-serialization 与 [toJsonElement] 扩展 (已下沉 commonMain),
 * 唯一 Android 特有处是 `@androidx.annotation.Keep` (ProGuard 保留注解, 与运行逻辑无关)。
 * 下沉后去除 @Keep; commonMain 无对应注解, 混淆规则由各平台 actual 模块自行配置。
 *
 * 字段/方法签名/序列化逻辑零改动, 消费方 import `io.legado.app.api.ReturnData` 不变。
 */
class ReturnData {

    var isSuccess: Boolean = false
        private set

    var errorMsg: String = "未知错误,请联系开发者!"
        private set

    var data: Any? = null
        private set

    fun setErrorMsg(errorMsg: String): ReturnData {
        this.isSuccess = false
        this.errorMsg = errorMsg
        return this
    }

    fun setData(data: Any): ReturnData {
        this.isSuccess = true
        this.errorMsg = ""
        this.data = data
        return this
    }

    /**
     * Phase D: 替代 `Gson().toJson(this)` / `GSON.toJson(this)`, 用 kotlinx-serialization 手动构造 JSON。
     *
     * data 字段为 Any?, 用 [Any?.toJsonElement] 处理常见类型 (String/Boolean/Number/ByteArray/List/@Serializable)。
     * 输出结构与原 Gson 反射序列化等价: {"isSuccess":...,"errorMsg":"...","data":...}。
     */
    fun toJsonString(): String = buildJsonObject {
        put("isSuccess", isSuccess)
        put("errorMsg", errorMsg)
        // data 用 toJsonElement 处理任意类型, null 输出为 JsonNull 保持结构完整
        put("data", data.toJsonElement())
    }.toString()
}
