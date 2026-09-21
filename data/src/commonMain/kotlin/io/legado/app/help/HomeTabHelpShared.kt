package io.legado.app.help

import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.HomeTab
import io.legado.app.help.HomeTabHelpShared.FILE_NAME
import io.legado.app.help.storage.FilesJsonStore
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.toJson
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

/**
 * 主页分组（HomeTab）持久化 - 跨平台共享逻辑。
 *
 * 真身存 filesDir JSON 文件 [FILE_NAME] (桌面端 java.util.prefs value ≤ 8192,
 * 整树 JSON 超限抛异常), 备份/恢复经 config.json 走文件内容 (见 BackupShared/
 * RestoreShared)。
 *
 * 首次进入若无数据则自动建默认分组"主页"。title 即 id, 重命名 = 改 title。
 *
 * 同步: 原 @Synchronized 仅 JVM 端有效, Native 端 (iOS/鸿蒙) 无效,
 * 改用 SynchronizedObject + synchronized 块保证全平台一致 (与 UpdateBookShared 同模式)。
 */
object HomeTabHelpShared {
    const val PREF_KEY = "homeTabs"
    const val FILE_NAME = "homeTabs.json"

    private const val DEFAULT_TAB_TITLE = "主页"

    @Volatile
    private var tabs: MutableList<HomeTab>? = null

    // 全平台同步锁 (替代 @Synchronized, Native 端亦生效)
    private val lock = SynchronizedObject()

    private fun load(): MutableList<HomeTab> = synchronized(lock) {
        tabs?.let { return it }
        val list = GSON.fromJsonArray<HomeTab>(FilesJsonStore.readText(FILE_NAME))
            .getOrNull()?.toMutableList() ?: mutableListOf()
        if (list.isEmpty()) {
            list.add(HomeTab(title = DEFAULT_TAB_TITLE, sortOrder = 0))
            persist(list)
        } else {
            tabs = list
        }
        return list
    }

    // ─── Tab 读 ──────────────────────────────────────────────────────────

    fun getTabs(): List<HomeTab> = synchronized(lock) {
        load().toList().sortedBy { it.sortOrder }
    }

    fun getTab(title: String): HomeTab? = synchronized(lock) {
        load().find { it.title == title }
    }

    fun getSections(tabTitle: String): List<HomeSection> = synchronized(lock) {
        getTab(tabTitle)?.sections?.sortedBy { it.sortOrder } ?: emptyList()
    }

    // ─── Tab 写 ──────────────────────────────────────────────────────────

    fun addTab(title: String): Boolean = synchronized(lock) {
        val list = load()
        if (list.any { it.title == title }) return@synchronized false
        list.add(HomeTab(title = title, sortOrder = list.size))
        persist(list)
        true
    }

    fun removeTab(title: String) = synchronized(lock) {
        val list = load()
        if (list.removeAll { it.title == title }) {
            val reSorted = list.mapIndexed { i, t -> t.copy(sortOrder = i) }.toMutableList()
            persist(reSorted)
        }
    }

    fun renameTab(oldTitle: String, newTitle: String): Boolean = synchronized(lock) {
        if (oldTitle == newTitle) return@synchronized true
        val list = load()
        if (list.any { it.title == newTitle }) return@synchronized false
        val idx = list.indexOfFirst { it.title == oldTitle }
        if (idx < 0) return@synchronized false
        list[idx] = list[idx].copy(title = newTitle)
        persist(list)
        true
    }

    fun saveTabsOrder(ordered: List<HomeTab>) = synchronized(lock) {
        val reSorted = ordered.mapIndexed { i, t -> t.copy(sortOrder = i) }.toMutableList()
        persist(reSorted)
    }

    // ─── Section 写（限定在某个 tab 内）─────────────────────────────────

    fun addSection(tabTitle: String, section: HomeSection) = synchronized(lock) {
        mutateTab(tabTitle) { tab ->
            val list = tab.sections.toMutableList()
            list.add(section.copy(sortOrder = list.size))
            tab.copy(sections = list)
        }
    }

    fun updateSection(tabTitle: String, section: HomeSection) = synchronized(lock) {
        mutateTab(tabTitle) { tab ->
            val list = tab.sections.toMutableList()
            val idx = list.indexOfFirst { it.id == section.id }
            if (idx < 0) tab else {
                list[idx] = section
                tab.copy(sections = list)
            }
        }
    }

    fun removeSection(tabTitle: String, sectionId: String) = synchronized(lock) {
        mutateTab(tabTitle) { tab ->
            tab.copy(sections = tab.sections.filterNot { it.id == sectionId })
        }
    }

    fun saveSectionsOrder(tabTitle: String, ordered: List<HomeSection>) = synchronized(lock) {
        mutateTab(tabTitle) { tab ->
            tab.copy(sections = ordered.mapIndexed { i, s -> s.copy(sortOrder = i) })
        }
    }

    private fun mutateTab(tabTitle: String, transform: (HomeTab) -> HomeTab) = synchronized(lock) {
        val list = load()
        val idx = list.indexOfFirst { it.title == tabTitle }
        if (idx < 0) return@synchronized
        list[idx] = transform(list[idx])
        persist(list)
    }

    private fun persist(list: List<HomeTab>) = synchronized(lock) {
        tabs = list.toMutableList()
        FilesJsonStore.writeText(FILE_NAME, GSON.toJson(list))
    }

    /** 恢复备份写回文件后清内存缓存 (见 RestoreShared) */
    fun invalidate() = synchronized(lock) {
        tabs = null
    }
}
