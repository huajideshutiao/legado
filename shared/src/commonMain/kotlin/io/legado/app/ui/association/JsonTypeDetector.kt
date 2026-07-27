package io.legado.app.ui.association

import io.legado.app.utils.parseJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * JSON 导入类型 (KMP 共享)。
 *
 * 对应 app 端 `BaseAssociationViewModel.importJson` 中按 key 判断的导入分支,
 * 每个枚举值映射一种导入源类型。原 app 端用字符串字面量 ("bookSource" / "rssSource" / ...)
 * 推送给 LiveData, 下沉后改为枚举, 由 app 端再映射回字符串做平台专属操作。
 */
enum class JsonType {
    /** 书源 (key: bookSourceUrl) → "bookSource" */
    BOOK_SOURCE,

    /** RSS 源 (key: sourceUrl) → "rssSource" */
    RSS_SOURCE,

    /** 替换规则 (key: pattern) → "replaceRule" */
    REPLACE_RULE,

    /** 主题 (key: themeName) → "theme" */
    THEME,

    /** 字典规则 (key: showRule) → "dictRule" */
    DICT_RULE,

    /** TXT 目录规则 (key: name + rule) → "txtRule" */
    TXT_RULE,

    /** HttpTTS (key: name + url) → "httpTts" */
    HTTP_TTS,
}

/**
 * 从 JSON 文本检测导入类型 (KMP 共享)。
 *
 * 原 app 端 `BaseAssociationViewModel.importJson` 中的纯数据解析逻辑下沉:
 * 解析 JSON → 取首个数组元素或对象 → 按 key 判断类型。零 Android 依赖
 * (不含 Uri / LiveData / context), app 端调用本函数后再做平台专属的
 * Uri 读取与 LiveData 推送。
 *
 * 解析流程 (与原 importJson 完全一致):
 * 1. `parseJsonElement(json)` 解析文本, 失败返回 null;
 * 2. 数组格式取首个元素 (模拟旧版 jayway SUPPRESS_EXCEPTIONS 行为),
 *    失败则当对象处理; 既非数组首元素对象也非对象则返回 null;
 * 3. 按 key 顺序判断: bookSourceUrl / sourceUrl / pattern / themeName / showRule /
 *    (name + rule) / (name + url), 命中即返回对应 [JsonType];
 * 4. 其余返回 null (调用方按"格式不对"处理)。
 *
 * @param json JSON 文本
 * @return 匹配的 [JsonType], 格式无效或未知类型返回 null
 */
fun detectJsonType(json: String): JsonType? {
    val jsonElement = try {
        parseJsonElement(json)
    } catch (e: Exception) {
        return null
    }
    //先尝试数组格式取首个元素, 失败则当作对象处理 (模拟旧版 jayway SUPPRESS_EXCEPTIONS 行为)
    val map = (jsonElement as? JsonArray)
        ?.firstOrNull()
        ?.let { it as? JsonObject }
        ?: jsonElement as? JsonObject
        ?: return null

    return when {
        map.containsKey("bookSourceUrl") -> JsonType.BOOK_SOURCE

        map.containsKey("sourceUrl") -> JsonType.RSS_SOURCE

        map.containsKey("pattern") -> JsonType.REPLACE_RULE

        map.containsKey("themeName") -> JsonType.THEME

        map.containsKey("showRule") -> JsonType.DICT_RULE

        //TxtTocRule 含 name+rule 字段
        map.containsKey("name") && map.containsKey("rule") -> JsonType.TXT_RULE

        //HttpTTS 含 name+url 字段
        map.containsKey("name") && map.containsKey("url") -> JsonType.HTTP_TTS

        else -> null
    }
}
