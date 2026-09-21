package io.legado.app.data.entities.rule

import kotlinx.serialization.Serializable

/**
 * 发现结果规则
 */
@Serializable
data class ExploreRule(
    /**是否还有下一页（请求级 JS 规则，result 为整页响应 body）**/
    override var hasMoreRule: String? = null,
    override var bookList: String? = null,
    override var name: String? = null,
    override var author: String? = null,
    override var intro: String? = null,
    override var kind: String? = null,
    override var lastChapter: String? = null,
    override var updateTime: String? = null,
    override var bookUrl: String? = null,
    override var coverUrl: String? = null,
    override var wordCount: String? = null
) : BookListRule {

    companion object {
        // 原 Gson jsonDeserializer 已移除, 反序列化由 RulePolymorphicSerializer 复刻
    }

}
