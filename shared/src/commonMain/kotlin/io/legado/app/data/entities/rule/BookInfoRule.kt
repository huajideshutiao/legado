package io.legado.app.data.entities.rule

import kotlinx.serialization.Serializable

/**
 * 书籍详情页规则
 */
@Serializable
data class BookInfoRule(
    var init: String? = null,
    var name: String? = null,
    var author: String? = null,
    var intro: String? = null,
    var kind: String? = null,
    var lastChapter: String? = null,
    var updateTime: String? = null,
    var coverUrl: String? = null,
    var tocUrl: String? = null,
    var wordCount: String? = null,
    var canReName: String? = null,
    var downloadUrls: String? = null
) {

    companion object {
        // 原 Gson jsonDeserializer 已移除, 反序列化由 RulePolymorphicSerializer 复刻
    }

}
