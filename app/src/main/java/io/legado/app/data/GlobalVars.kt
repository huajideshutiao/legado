package io.legado.app.data

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter

object GlobalVars {
    var nowBook : Book? = null
    var nowSource : BaseSource? = null
    var nowChapterList :List<BookChapter>? = null
}