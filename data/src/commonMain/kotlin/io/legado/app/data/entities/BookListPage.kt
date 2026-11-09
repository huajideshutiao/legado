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
) {
    /**
     * 按 bookUrl 判同一本书的身份，保留先出现的那条，其余字段与顺序不变。
     * SearchBook.time 是构造时刻，同一本书两次解析结构不相等，只能用 bookUrl 判身份；
     * 劣质源翻页与插件页会把上一页的书再返回一次，分页是否增长与列表项身份都按本结果算。
     */
    internal fun distinctByBookUrl(): BookListPage =
        BookListPage(ArrayList(books.distinctBy { it.bookUrl }), hasNextPage, nextPageUrl)
}
