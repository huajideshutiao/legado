package io.legado.app.data.entities

import org.json.JSONObject

/**
 * Server.getConfigJsonObject() 扩展函数。
 *
 * 原 Server.kt 在 androidMain 时为成员函数, 下沉 commonMain 后因依赖 org.json.JSONObject
 * (Android 平台特有) 抽取为 androidMain 扩展函数。调用方式不变, 仅需 import 本包。
 */
fun Server.getConfigJsonObject(): JSONObject? {
    val json = config
    json ?: return null
    return JSONObject(json)
}
