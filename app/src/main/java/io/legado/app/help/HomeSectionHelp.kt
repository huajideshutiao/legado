package io.legado.app.help

import io.legado.app.data.entities.HomeSection
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

object HomeSectionHelp {
    private const val PREF_KEY = "homeSections"
    private var sections: MutableList<HomeSection>? = null

    fun getSections(): List<HomeSection> {
        if (sections == null) {
            val json = appCtx.getPrefString(PREF_KEY)
            sections = GSON.fromJsonArray<HomeSection>(json).getOrNull()?.toMutableList()
                ?: mutableListOf()
        }
        // 无限流展示项强制排到最后（其后的展示项会被无限流挤走，没有意义）
        return sections!!.sortedWith(
            compareBy({ it.style == HomeSection.STYLE_INFINITE_GRID }, { it.sortOrder })
        )
    }

    fun addSection(section: HomeSection) {
        val list = getSections().toMutableList()
        list.add(section)
        sections = list
        persist(list)
    }

    fun updateSection(section: HomeSection) {
        val list = getSections().toMutableList()
        val index = list.indexOfFirst { it.id == section.id }
        if (index >= 0) {
            list[index] = section
            sections = list
            persist(list)
        }
    }

    fun removeSection(section: HomeSection) {
        val list = getSections().toMutableList()
        list.removeAll { it.id == section.id }
        sections = list
        persist(list)
    }

    fun moveSections(from: Int, to: Int) {
        val list = getSections().toMutableList()
        if (from < 0 || to < 0 || from >= list.size || to >= list.size) return
        val item = list.removeAt(from)
        list.add(to, item)
        val reordered = list.mapIndexed { index, s -> s.copy(sortOrder = index) }
        sections = reordered.toMutableList()
        persist(reordered)
    }

    /** 按给定顺序整体写回，并重排 sortOrder */
    fun saveOrder(ordered: List<HomeSection>) {
        val reSorted = ordered.mapIndexed { index, s -> s.copy(sortOrder = index) }
        sections = reSorted.toMutableList()
        persist(reSorted)
    }

    fun invalidate() {
        sections = null
    }

    private fun persist(list: List<HomeSection>) {
        appCtx.putPrefString(PREF_KEY, GSON.toJson(list))
    }
}
