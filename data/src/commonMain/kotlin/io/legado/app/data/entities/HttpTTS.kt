package io.legado.app.data.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import io.legado.app.utils.systemCurrentTimeMillis
import com.script.jsdispatch.JsApi
import kotlinx.serialization.Serializable

/**
 * F2: HttpTTS 已下沉到 shared jvmAndAndroidMain, JS 可见的 JsExtensions 面由 JsExtensionsCommon
 * 接口默认实现承载, evalJS 直接绑定源实例自身。
 *
 * @JsApi: KSP 生成静态分派表 (app 端 jsapi.extraClasses 接线), JS 桥属性/方法访问先查表、miss 落反射。
 */
@Serializable
@Entity(tableName = "httpTTS")
@JsApi
data class HttpTTS(
    @PrimaryKey
    val id: Long = systemCurrentTimeMillis(),
    var name: String = "",
    var url: String = "",
    var contentType: String? = null,
    @ColumnInfo(defaultValue = "0")
    override var concurrentRate: String? = "0",
    override var loginUrl: String? = null,
    // loginUi 的 JSON 值可能是数组/对象, 需原样转字符串 (复刻原 GSON 全局 StringJsonDeserializer)
    @Serializable(with = RawJsonStringSerializer::class)
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

