package io.legado.app.utils

import io.legado.app.data.entities.BookChapter

fun BookChapter.internString() {
    title = title.platformIntern()
    bookUrl = bookUrl.platformIntern()
}
