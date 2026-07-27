package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.serialization.Serializable

/**
 * F2: HttpTTS 已下沉到 shared jvmAndAndroidMain, 去掉 JsExtensions 继承。
 *
 * JS 可见的 JsExtensions 面由 app 端 [HttpTTSJsExt] 包装器补回,
 * 通过 [io.legado.app.help.JsExtProviders] 在 [BaseSource.evalJS] 注入 bindings。
 *
 * log 不再 override (原 super<JsExtensions>.log), 走 [BaseSource.log] 默认实现
 * (已对齐 JsExtensions.log 行为)。
 */
@Serializable
@Entity(tableName = "httpTTS")
data class HttpTTS(
    @PrimaryKey
    val id: Long = systemCurrentTimeMillis(),
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
    var lastUpdateTime: Long = systemCurrentTimeMillis()
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

