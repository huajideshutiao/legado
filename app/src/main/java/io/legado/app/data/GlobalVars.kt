package io.legado.app.data

import io.legado.app.data.entities.Book

object GlobalVars {
    var nowBook : Book? = null
        set(book) {
            field = book?.copy()
        }
}