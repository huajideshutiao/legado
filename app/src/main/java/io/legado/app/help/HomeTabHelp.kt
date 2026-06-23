package io.legado.app.help

import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.HomeTab
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import splitties.init.appCtx

/**
 * 主页分组（HomeTab）持久化。整树读写 SP；title 即 id。
 * 首次进入若无数据则自动建默认分组"主页"。
 */
object HomeTabHelp {
    private const val PREF_KEY = "homeTabs"
    private const val LEGACY_SECTIONS_KEY = "homeSections"
    private const val DEFAULT_TAB_TITLE = "主页"

    @Volatile
    private var tabs: MutableList<HomeTab>? = null

    @Synchronized
    private fun load(): MutableList<HomeTab> {
        tabs?.let { return it }
        val json = appCtx.getPrefString(PREF_KEY)
        val list = GSON.fromJsonArray<HomeTab>(json).getOrNull()?.toMutableList()
            ?: mutableListOf()
        if (list.isEmpty()) {
            list.add(HomeTab(title = DEFAULT_TAB_TITLE, sortOrder = 0))
            persist(list)
            appCtx.removePref(LEGACY_SECTIONS_KEY)
        }
        tabs = list
        return list
    }

    // ─── Tab 读 ──────────────────────────────────────────────────────────

    @Synchronized
    fun getTabs(): List<HomeTab> = load().toList().sortedBy { it.sortOrder }

    @Synchronized
    fun getTab(title: String): HomeTab? = load().find { it.title == title }

    @Synchronized
    fun getSections(tabTitle: String): List<HomeSection> =
        getTab(tabTitle)?.sections?.sortedBy { it.sortOrder } ?: emptyList()

    // ─── Tab 写 ──────────────────────────────────────────────────────────

    @Synchronized
    fun addTab(title: String): Boolean {
        val list = load()
        if (list.any { it.title == title }) return false
        list.add(HomeTab(title = title, sortOrder = list.size))
        persist(list)
        return true
    }

    @Synchronized
    fun removeTab(title: String) {
        val list = load()
        if (list.removeAll { it.title == title }) {
            val reSorted = list.mapIndexed { i, t -> t.copy(sortOrder = i) }.toMutableList()
            persist(reSorted)
        }
    }

    @Synchronized
    fun renameTab(oldTitle: String, newTitle: String): Boolean {
        if (oldTitle == newTitle) return true
        val list = load()
        if (list.any { it.title == newTitle }) return false
        val idx = list.indexOfFirst { it.title == oldTitle }
        if (idx < 0) return false
        list[idx] = list[idx].copy(title = newTitle)
        persist(list)
        return true
    }

    @Synchronized
    fun saveTabsOrder(ordered: List<HomeTab>) {
        val reSorted = ordered.mapIndexed { i, t -> t.copy(sortOrder = i) }.toMutableList()
        persist(reSorted)
    }

    // ─── Section 写（限定在某个 tab 内）─────────────────────────────────

    @Synchronized
    fun addSection(tabTitle: String, section: HomeSection) {
        mutateTab(tabTitle) { tab ->
            val list = tab.sections.toMutableList()
            list.add(section.copy(sortOrder = list.size))
            tab.copy(sections = list)
        }
    }

    @Synchronized
    fun updateSection(tabTitle: String, section: HomeSection) {
        mutateTab(tabTitle) { tab ->
            val list = tab.sections.toMutableList()
            val idx = list.indexOfFirst { it.id == section.id }
            if (idx < 0) tab else {
                list[idx] = section
                tab.copy(sections = list)
            }
        }
    }

    @Synchronized
    fun removeSection(tabTitle: String, sectionId: String) {
        mutateTab(tabTitle) { tab ->
            tab.copy(sections = tab.sections.filterNot { it.id == sectionId })
        }
    }

    @Synchronized
    fun saveSectionsOrder(tabTitle: String, ordered: List<HomeSection>) {
        mutateTab(tabTitle) { tab ->
            tab.copy(sections = ordered.mapIndexed { i, s -> s.copy(sortOrder = i) })
        }
    }

    @Synchronized
    private fun mutateTab(tabTitle: String, transform: (HomeTab) -> HomeTab) {
        val list = load()
        val idx = list.indexOfFirst { it.title == tabTitle }
        if (idx < 0) return
        list[idx] = transform(list[idx])
        persist(list)
    }

    @Synchronized
    private fun persist(list: List<HomeTab>) {
        tabs = list.toMutableList()
        appCtx.putPrefString(PREF_KEY, GSON.toJson(list))
    }
}
