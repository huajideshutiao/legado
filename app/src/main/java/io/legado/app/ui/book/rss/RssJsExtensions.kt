package io.legado.app.ui.rss.read

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.JsExtensions
import io.legado.app.ui.association.AddToBookshelfHelper
import io.legado.app.ui.book.rss.ReadRssActivity
import io.legado.app.ui.book.search.SearchActivity

@Suppress("unused")
class RssJsExtensions(private val activity: ReadRssActivity) : JsExtensions {

    override fun getSource(): BaseSource? {
        return activity.getSource()
    }

    fun searchBook(key: String) {
        SearchActivity.start(activity, key)
    }

    fun addBook(bookUrl: String) {
        AddToBookshelfHelper.add(activity, bookUrl)
    }

}
