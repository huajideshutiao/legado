package io.legado.app.data.entities

/**
 * 书籍列表一页的解析结果。
 * 把"还有没有下一页"提到请求级，而不是从 list 是否为空猜，
 * 避免最后一页非空时调用方再多发一次空请求。
 */
data class BookListPage(
    val books: ArrayList<SearchBook>,
    val hasNextPage: Boolean,
    /**下一页地址 (配置了下一页地址规则且本页解析出链接时非空, 下一次翻页直接请求它) */
    val nextPageUrl: String? = null,
)
