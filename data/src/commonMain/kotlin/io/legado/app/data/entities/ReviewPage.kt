package io.legado.app.data.entities

/**
 * 段评一页的解析结果。
 * 把"还有没有下一页"提到请求级，而不是从 list 是否为空猜，
 * 避免最后一页非空时调用方再多发一次空请求。
 */
data class ReviewPage(
    val reviews: List<Review>,
    val hasNextPage: Boolean,
    // 段评总数文本，null=书源未配置该规则（UI 不显示）；非空=规则解析得到的原始字符串，UI 直接展示
    val totalCount: String? = null,
)
