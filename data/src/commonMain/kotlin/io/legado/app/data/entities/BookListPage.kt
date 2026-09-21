package io.legado.app.data.entities

/**
 * 书籍列表一页的解析结果。
 * 把"还有没有下一页"提到请求级，而不是从 list 是否为空猜，
 * 避免最后一页非空时调用方再多发一次空请求。
 */
data class BookListPage(
    val books: ArrayList<SearchBook>,
    val hasNextPage: Boolean,
)
