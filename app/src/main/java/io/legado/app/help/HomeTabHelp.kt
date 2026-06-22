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

    private var tabs: MutableList<HomeTab>? = null

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

    fun getTabs(): List<HomeTab> = load().sortedBy { it.sortOrder }.map { sortInside(it) }

    fun getTab(title: String): HomeTab? = load().find { it.title == title }?.let { sortInside(it) }

    fun getSections(tabTitle: String): List<HomeSection> =
        getTab(tabTitle)?.sections ?: emptyList()

    /** 分组内排序：无限流强制最后，否则按 sortOrder */
    private fun sortInside(tab: HomeTab): HomeTab {
        val sorted = tab.sections.sortedWith(
            compareBy({ it.style == HomeSection.STYLE_INFINITE_GRID }, { it.sortOrder })
        )
        return if (sorted == tab.sections) tab else tab.copy(sections = sorted)
    }

    // ─── Tab 写 ──────────────────────────────────────────────────────────

    /** 添加分组。重名返回 false。 */
    fun addTab(title: String): Boolean {
        val list = load()
        if (list.any { it.title == title }) return false
        list.add(HomeTab(title = title, sortOrder = list.size))
        persist(list)
        return true
    }

    fun removeTab(title: String) {
        val list = load()
        if (list.removeAll { it.title == title }) {
            val reSorted = list.mapIndexed { i, t -> t.copy(sortOrder = i) }.toMutableList()
            persist(reSorted)
        }
    }

    /** 重命名分组。新名重名返回 false。 */
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

    /** 按给定顺序整体写回 tabs，并重排 sortOrder。 */
    fun saveTabsOrder(ordered: List<HomeTab>) {
        val reSorted = ordered.mapIndexed { i, t -> t.copy(sortOrder = i) }.toMutableList()
        persist(reSorted)
    }

    // ─── Section 写（限定在某个 tab 内）─────────────────────────────────

    fun addSection(tabTitle: String, section: HomeSection) {
        mutateTab(tabTitle) { tab ->
            val list = tab.sections.toMutableList()
            list.add(section.copy(sortOrder = list.size))
            tab.copy(sections = list)
        }
    }

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

    fun removeSection(tabTitle: String, sectionId: String) {
        mutateTab(tabTitle) { tab ->
            tab.copy(sections = tab.sections.filterNot { it.id == sectionId })
        }
    }

    /** 按给定顺序整体写回 tab 内 sections，并重排 sortOrder。 */
    fun saveSectionsOrder(tabTitle: String, ordered: List<HomeSection>) {
        mutateTab(tabTitle) { tab ->
            tab.copy(sections = ordered.mapIndexed { i, s -> s.copy(sortOrder = i) })
        }
    }

    private fun mutateTab(tabTitle: String, transform: (HomeTab) -> HomeTab) {
        val list = load()
        val idx = list.indexOfFirst { it.title == tabTitle }
        if (idx < 0) return
        list[idx] = transform(list[idx])
        persist(list)
    }

    fun invalidate() {
        tabs = null
    }

    private fun persist(list: List<HomeTab>) {
        tabs = list.toMutableList()
        appCtx.putPrefString(PREF_KEY, GSON.toJson(list))
    }
}
