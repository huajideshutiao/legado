package io.legado.jshost

/**
 * 接收 Map 参数的宿主对象, 模拟 JsExtensions.post/get/head(headers: Map)。
 *
 * 刻意放在非 `com.script` 包: 真实书源宿主对象 (JsExtensions) 位于 io.legado.app,
 * 不命中安全名单; fixture 若嵌在 com.script.quickjs 会被 `com.script` 前缀拦截,
 * binding 注入被跳过导致 `recv is not defined`, 掩盖真正要验的 coercion 行为。
 */
class MapParamReceiver {
    fun echoHeaders(headers: Map<String, String>): String =
        headers.entries.joinToString(",") { "${it.key}=${it.value}" }
}
