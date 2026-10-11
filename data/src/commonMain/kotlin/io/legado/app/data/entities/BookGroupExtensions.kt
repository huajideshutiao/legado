package io.legado.app.data.entities

/**
 * 分组实际排序方式 (原 BookGroup.getRealBookSort)。
 *
 * bookSort < 0 时回退到全局书架排序配置; 全局值经参数注入, 分组不存在的回退由调用方处理。
 */
fun BookGroup.getRealBookSort(bookshelfSort: Int): Int {
    if (bookSort < 0) {
        return bookshelfSort
    }
    return bookSort
}
