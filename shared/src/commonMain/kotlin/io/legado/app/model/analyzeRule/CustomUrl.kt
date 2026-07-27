package io.legado.app.model.analyzeRule

import io.legado.app.constant.AppPattern
import io.legado.app.utils.AnyMapSerializer
import io.legado.app.utils.decodeAnyMapOrNull
import kotlinx.serialization.json.Json

/**
 * 与原 GSON(setPrettyPrinting + disableHtmlEscaping) 字节级一致的输出格式: 2 空格缩进 + `": "` 分隔。
 * [CustomUrl.toString] 结果会写入 Book.origin 并参与字节等值查询, 格式变化会导致老库书籍查不中而重复建书。
 */
private val CUSTOM_URL_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

@Suppress("unused")
class CustomUrl(url: String) {

    private val mUrl: String
    private val attribute = hashMapOf<String, Any>()

    init {
        val urlMatch = AppPattern.urlParamPattern.find(url)
        mUrl = if (urlMatch != null) {
            val attr = url.substring(urlMatch.range.last + 1)
            // decodeAnyMapOrNull 返回 Map<String, Any?>?, Gson 旧路径也实际返回 Any? value
            // (MapDeserializer 不会过滤 null value), 此处 unchecked cast 对齐原行为
            @Suppress("UNCHECKED_CAST")
            val attrMap = decodeAnyMapOrNull(attr) as? Map<String, Any>
            attrMap?.let {
                attribute.putAll(it)
            }
            url.take(urlMatch.range.first)
        } else {
            url
        }
    }

    fun putAttribute(key: String, value: Any?): CustomUrl {
        if (value == null) {
            attribute.remove(key)
        } else {
            attribute[key] = value
        }
        return this
    }

    fun getUrl(): String {
        return mUrl
    }

    fun getAttr(): Map<String, Any> {
        return attribute
    }

    override fun toString(): String {
        if (attribute.isEmpty()) {
            return mUrl
        }
        // AnyMapSerializer 期望 Map<String, Any?>, attribute 是 Map<String, Any> (Any 是 Any? 子类型)
        @Suppress("UNCHECKED_CAST")
        val anyMap = attribute as Map<String, Any?>
        return mUrl + "," + CUSTOM_URL_JSON.encodeToString(AnyMapSerializer, anyMap)
    }

}
