package io.legado.app.help

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.ui.compose.platform.PreferenceStoreProvider
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.postEvent
import io.legado.app.utils.toJson

/**
 * 发现页置顶条目管理。
 *
 * 原 app 端实现用 `appCtx.getPrefString` / `appCtx.putPrefString` (依赖
 * `defaultSharedPreferences`), 下沉后改走 [PreferenceStoreProvider] 间接,
 * 行为完全一致 (Android 端 [AndroidPreferenceStoreProvider] 包装同一
 * defaultSharedPreferences, 保证 backup/restore 与原数据兼容)。
 *
 * 注: 早期下沉误用 [io.legado.app.help.config.PreferenceProviders], 其 Android 端
 * actual ([io.legado.app.help.config.AndroidPreferenceProvider]) 写入独立的
 * `legado_config` SP 文件, 与原 defaultSharedPreferences 不同, 导致老用户
 * exploreFavorites 数据孤立 + 不被 Backup.kt 备份。改回 [PreferenceStoreProvider]
 * 修复该破坏性变更。
 */
object PinnedExploreHelp {
    private const val PREF_KEY = "exploreFavorites"
    private var pinnedExplores: List<PinnedExplore>? = null

    /**
     * 平台存储 provider, 由宿主启动早期注入 (app 端 App.onCreate)。
     * 必须用 defaultSharedPreferences 系 (app 端 AndroidPreferenceStoreProvider),
     * 与原 appCtx.getPrefString/putPrefString 行为一致。
     */
    @Volatile
    lateinit var prefs: PreferenceStoreProvider

    fun getPinnedExplores(): List<PinnedExplore> {
        if (pinnedExplores == null) {
            val json = prefs.getString(PREF_KEY)
            pinnedExplores = GSON.fromJsonArray<PinnedExplore>(json).getOrNull() ?: emptyList()
        }
        return pinnedExplores!!
    }

    fun addPinnedExplore(pinned: PinnedExplore) {
        val favorites = getPinnedExplores().toMutableList()
        favorites.add(pinned)
        pinnedExplores = favorites
        prefs.putString(PREF_KEY, GSON.toJson(favorites))
        postEvent(EventBus.UP_EXPLORE_PINNED, "add")
    }

    fun removePinnedExplore(pinned: PinnedExplore) {
        val favorites = getPinnedExplores().toMutableList()
        val index = favorites.indexOf(pinned)
        if (index != -1) {
            favorites.removeAt(index)
            pinnedExplores = favorites
            prefs.putString(PREF_KEY, GSON.toJson(favorites))
            postEvent(EventBus.UP_EXPLORE_PINNED, "remove:$index")
        }
    }
}
