package io.legado.app.ui.book

import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig

/**
 * 获取分组实际排序方式（原 BookGroup.getRealBookSort，下沉 shared 去 AppConfig 耦合后上移）。
 * bookSort < 0 时回退到全局书架排序配置。
 */
fun BookGroup.getRealBookSort(): Int {
    if (bookSort < 0) {
        return AppConfig.bookshelfSort
    }
    return bookSort
}
