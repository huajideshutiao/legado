package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.utils.readJsonString
import io.legado.app.utils.readLong
import io.legado.app.utils.readString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray

/**
 * 在线朗读引擎
 */
@Entity(tableName = "httpTTS")
data class HttpTTS(
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    var name: String = "",
    var url: String = "",
    var contentType: String? = null,
    @ColumnInfo(defaultValue = "0")
    override var concurrentRate: String? = "0",
    override var loginUrl: String? = null,
    override var loginUi: String? = null,
    override var header: String? = null,
    override var jsLib: String? = null,
    @ColumnInfo(defaultValue = "0")
    override var enabledCookieJar: Boolean? = false,
    @ColumnInfo(defaultValue = "0")
    override var enableDangerousApi: Boolean? = false,
    var loginCheckJs: String? = null,
    @ColumnInfo(defaultValue = "0")
    var lastUpdateTime: Long = System.currentTimeMillis()
) : BaseSource {

    override fun getTag(): String {
        return name
    }

    override fun getKey(): String {
        return "httpTts:$id"
    }

    override fun getSourceType(): Int {
        return io.legado.app.constant.SourceType.tts
    }

    @Suppress("unused")
    companion object
}

fun HttpTTS.Companion.fromJsonElement(doc: JsonElement): Result<HttpTTS> {
    return kotlin.runCatching {
        //loginUi 必须保留 JSON 数组格式, 用 readJsonString 避免 readString 展开数组破坏结构
        HttpTTS(
            id = doc.readLong("$.id") ?: System.currentTimeMillis(),
            name = doc.readString("$.name")!!,
            url = doc.readString("$.url")!!,
            contentType = doc.readString("$.contentType"),
            concurrentRate = doc.readString("$.concurrentRate"),
            loginUrl = doc.readString("$.loginUrl"),
            loginUi = doc.readJsonString("$.loginUi"),
            header = doc.readString("$.header"),
            loginCheckJs = doc.readString("$.loginCheckJs"),
            lastUpdateTime = doc.readLong("$.lastUpdateTime") ?: System.currentTimeMillis()
        )
    }
}

fun HttpTTS.Companion.fromJson(json: String): Result<HttpTTS> {
    return fromJsonElement(io.legado.app.utils.parseJsonElement(json))
}

fun HttpTTS.Companion.fromJsonArray(jsonArray: String): Result<ArrayList<HttpTTS>> {
    return kotlin.runCatching {
        val sources = arrayListOf<HttpTTS>()
        val jsonElement = io.legado.app.utils.parseJsonElement(jsonArray)
        val doc = jsonElement.jsonArray
        doc.forEach {
            fromJsonElement(it).getOrThrow().let { source ->
                sources.add(source)
            }
        }
        return@runCatching sources
    }
}
