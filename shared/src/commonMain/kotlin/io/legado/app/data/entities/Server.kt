package io.legado.app.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import io.legado.app.utils.decodeOrNull
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.serialization.Serializable

/**
 * 服务器
 *
 * 已从 androidMain 下沉 commonMain; 原 getConfigJsonObject() 依赖 org.json.JSONObject
 * (Android 平台特有), 已抽取到 androidMain/ServerExt.kt 作为扩展函数,
 * commonMain 只保留纯数据模型。
 */
@Serializable
@Entity(tableName = "servers")
data class Server(
    @PrimaryKey
    var id: Long = systemCurrentTimeMillis(),
    var name: String = "",
    var type: TYPE = TYPE.WEBDAV,
    var config: String? = null,
    var sortNumber: Int = 0
) {

    enum class TYPE {
        WEBDAV
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is Server) {
            return id == other.id
        }
        return false
    }

    fun getWebDavConfig(): WebDavConfig? {
        // GSON.fromJsonObject<WebDavConfig>(config).getOrNull() → KS_JSON.decodeOrNull<WebDavConfig>(config)
        return if (type == TYPE.WEBDAV) decodeOrNull<WebDavConfig>(config) else null
    }

    @Serializable
    data class WebDavConfig(
        var url: String,
        var username: String,
        var password: String
    )

}
