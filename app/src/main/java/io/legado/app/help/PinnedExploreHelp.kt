package io.legado.app.help

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

object PinnedExploreHelp {
    private var pinnedExplores: List<PinnedExplore>? = null

    fun getPinnedExplores(): List<PinnedExplore> {
        if (pinnedExplores == null) {
            val json = appCtx.getPrefString("exploreFavorites")
            pinnedExplores = GSON.fromJsonArray<PinnedExplore>(json).getOrNull() ?: emptyList()
        }
        return pinnedExplores!!
    }

    fun addPinnedExplore(pinned: PinnedExplore) {
        val favorites = getPinnedExplores().toMutableList()
        favorites.add(pinned)
        pinnedExplores = favorites
        appCtx.putPrefString("exploreFavorites", GSON.toJson(favorites))
        postEvent(EventBus.UP_EXPLORE_PINNED, "add")
    }

    fun removePinnedExplore(pinned: PinnedExplore) {
        val favorites = getPinnedExplores().toMutableList()
        val index = favorites.indexOf(pinned)
        if (index != -1) {
            favorites.removeAt(index)
            pinnedExplores = favorites
            appCtx.putPrefString("exploreFavorites", GSON.toJson(favorites))
            postEvent(EventBus.UP_EXPLORE_PINNED, "remove:$index")
        }
    }
}
