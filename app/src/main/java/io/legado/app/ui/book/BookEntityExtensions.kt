package io.legado.app.ui.book

import android.content.Context
import io.legado.app.help.i18n.androidAppString
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig

/**
 * 分组管理显示名（原 BookGroup.getManageName，去 entity 安卓渗漏后上移）。
 */
fun BookGroup.getManageName(context: Context): String {
    return when (groupId) {
        BookGroup.IdAll -> "$groupName(${androidAppString("all")})"
        BookGroup.IdLocal -> "$groupName(${androidAppString("local")})"
        BookGroup.IdUngrouped -> "$groupName(${androidAppString("no_group")})"
        BookGroup.IdError -> "$groupName(${androidAppString("update_book_fail")})"
        else -> groupName
    }
}

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

/**
 * 简介展示文本（原 SearchBook.trimIntro，去 entity 安卓渗漏后上移）。
 */
fun SearchBook.trimIntro(context: Context): String {
    val trimIntro = intro?.trim()
    return if (trimIntro.isNullOrEmpty()) {
        androidAppString("intro_show_null")
    } else trimIntro
}
