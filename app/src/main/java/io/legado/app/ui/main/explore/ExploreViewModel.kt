package io.legado.app.ui.main.explore

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefString
import splitties.init.appCtx

class ExploreViewModel(application: Application) : BaseViewModel(application) {

    val pinnedExploresData = MutableLiveData<List<PinnedExplore>>()

    fun upPinnedExplores() {
        val json = appCtx.getPrefString("exploreFavorites")
        pinnedExploresData.postValue(
            GSON.fromJsonArray<PinnedExplore>(json).getOrNull() ?: emptyList()
        )
    }

    fun topSource(bookSource: BookSourcePart) {
        execute {
            val minXh = appDb.bookSourceDao.minOrder
            bookSource.customOrder = minXh - 1
            appDb.bookSourceDao.upOrder(bookSource)
        }
    }

    fun deleteSource(source: BookSourcePart) {
        execute {
            SourceHelp.deleteBookSource(source.bookSourceUrl)
        }
    }

}