package io.legado.app.help

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.postEvent
import io.legado.app.utils.toJson

/**
 * 发现页置顶条目管理。
 *
 * 原 app 端实现用 `appCtx.getPrefString` / `appCtx.putPrefString` (依赖
 * `SharedPreferences`), 下沉后改走 [PreferenceProviders.get()] provider 间接,
 * 行为完全一致。其他依赖 (GSON/postEvent/EventBus/PinnedExplore) 均已下沉 commonMain。
 */
object PinnedExploreHelp {
    private const val PREF_KEY = "exploreFavorites"
    private var pinnedExplores: List<PinnedExplore>? = null

    fun getPinnedExplores(): List<PinnedExplore> {
        if (pinnedExplores == null) {
            val json = PreferenceProviders.get().getString(PREF_KEY)
            pinnedExplores = GSON.fromJsonArray<PinnedExplore>(json).getOrNull() ?: emptyList()
        }
        return pinnedExplores!!
    }

    fun addPinnedExplore(pinned: PinnedExplore) {
        val favorites = getPinnedExplores().toMutableList()
        favorites.add(pinned)
        pinnedExplores = favorites
        PreferenceProviders.get().putString(PREF_KEY, GSON.toJson(favorites))
        postEvent(EventBus.UP_EXPLORE_PINNED, "add")
    }

    fun removePinnedExplore(pinned: PinnedExplore) {
        val favorites = getPinnedExplores().toMutableList()
        val index = favorites.indexOf(pinned)
        if (index != -1) {
            favorites.removeAt(index)
            pinnedExplores = favorites
            PreferenceProviders.get().putString(PREF_KEY, GSON.toJson(favorites))
            postEvent(EventBus.UP_EXPLORE_PINNED, "remove:$index")
        }
    }
}
