package io.legado.app.ui.book.rss

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.JsExtensions
import io.legado.app.ui.association.AddToBookshelfHelper
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute

@Suppress("unused")
class RssJsExtensions(private val activity: ReadRssActivity) : JsExtensions {

    override fun getSource(): BaseSource? {
        return activity.getSource()
    }

    fun searchBook(key: String) {
        AppNavigatorProviders.getOrNull()?.push(AppRoute.Search(key = key))
    }

    fun addBook(bookUrl: String) {
        AddToBookshelfHelper.add(AppNavigatorProviders.get(), activity, bookUrl)
    }

}
