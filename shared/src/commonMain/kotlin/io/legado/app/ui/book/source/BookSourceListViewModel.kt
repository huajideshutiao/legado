package io.legado.app.ui.book.source

import io.legado.app.data.AppDbProviders
import io.legado.app.data.cnCompareGroups
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * 书源列表数据修改 VM (KMP 版, 替代 app 端 `io.legado.app.ui.book.source.manage.BookSourceViewModel`)。
 *
 * 下沉改动:
 * - 去掉 `android.app.Application` / `BaseViewModel` 依赖, 改用 [CoroutineScope] + [Coroutine.async]
 *   替代 `BaseViewModel.execute`
 * - DAO 访问走 [AppDbProviders] 间接 (替代 `appDb.bookSourceDao`), 行为与 app 端一致
 * - 不实现 `saveToFile` (依赖 Android File/Context/GSON 输出流), 留 app 端
 * - 中文字符串排序走 `cnCompareGroups` (commonMain internal expect, 同模块可见), 与 app 端
 *   `String.cnCompare` 行为对齐 (androidMain 走 android.icu, jvmMain 走 java.text.Collator)
 *
 * @param scope 调用方提供的协程作用域 (Android: `lifecycleScope` / desktop: application scope)
 */
class BookSourceListViewModel(
    private val scope: CoroutineScope,
) {

    // ---- 数据流 ----

    /**
     * 书源列表数据流, 按 [filter]/[sort]/[sortAscending]/[groupSourcesByDomain] 处理。
     *
     * 对齐 app 端 `BookSourceActivity.upBookSource` 的 flow 选择 + 排序逻辑, 但不绑定
     * `Lifecycle` (host 自行用 `repeatOnLifecycle` 或 `collectAsState` 处理生命周期)。
     *
     * @param getSourceHost 由 host 提供的域名提取函数 (对应 app 端 `NetworkUtils.getSubDomainOrNull`),
     *   仅在 [groupSourcesByDomain] = true 时调用
     */
    fun flowSources(
        filter: SourceFilter,
        sort: BookSourceSort,
        sortAscending: Boolean,
        groupSourcesByDomain: Boolean,
        getSourceHost: (String) -> String,
    ): Flow<List<BookSourcePart>> {
        val dao = AppDbProviders.get().bookSourceDao
        val base: Flow<List<BookSourcePart>> = when (filter) {
            SourceFilter.All -> dao.flowAll()
            SourceFilter.Enabled -> dao.flowEnabled()
            SourceFilter.Disabled -> dao.flowEnabled(false)
            SourceFilter.NeedLogin -> dao.flowLogin()
            SourceFilter.NoGroup -> dao.flowNoGroup()
            SourceFilter.EnabledExplore -> dao.flowExplore()
            SourceFilter.DisabledExplore -> dao.flowExplore(false)
            is SourceFilter.Group -> dao.flowGroupSearch(filter.name)
            is SourceFilter.Search -> dao.flowSearch(filter.key)
        }
        return base.map { data ->
            if (groupSourcesByDomain) {
                data.sortedWith(
                    compareBy<BookSourcePart> { getSourceHost(it.bookSourceUrl) == "#" }
                        .thenBy { getSourceHost(it.bookSourceUrl) }
                        .thenByDescending { it.lastUpdateTime })
            } else {
                val tmp = when (sort) {
                    BookSourceSort.Weight -> data.sortedBy { it.weight }
                    BookSourceSort.Name -> data.sortedWith { o1, o2 ->
                        o1.bookSourceName.cnCompareGroups(o2.bookSourceName)
                    }
                    BookSourceSort.Url -> data.sortedBy { it.bookSourceUrl }
                    BookSourceSort.Update -> data.sortedByDescending { it.lastUpdateTime }
                    BookSourceSort.Respond -> data.sortedBy { it.respondTime }
                    BookSourceSort.Enable -> data.sortedWith { o1, o2 ->
                        var sortNum = -o1.enabled.compareTo(o2.enabled)
                        if (sortNum == 0) {
                            sortNum = o1.bookSourceName.cnCompareGroups(o2.bookSourceName)
                        }
                        sortNum
                    }
                    else -> data.sortedBy { it.customOrder }
                }
                if (!sortAscending) tmp.reversed() else tmp
            }
        }
    }

    /** 书源分组列表数据流 (对应 app 端 `initLiveDataGroup`)。 */
    fun flowGroups(): Flow<List<String>> = flow {
        emitAll(AppDbProviders.get().bookSourceDao.flowGroups())
    }

    // ---- CRUD (对应 app 端 BookSourceViewModel 同名方法) ----

    fun topSource(vararg sources: BookSourcePart) {
        Coroutine.async(scope = scope) {
            sources.sortBy { it.customOrder }
            val minOrder = AppDbProviders.get().bookSourceDao.minOrder() - 1
            val array = sources.mapIndexed { index, it ->
                it.copy(customOrder = minOrder - index)
            }
            AppDbProviders.get().bookSourceDao.upOrder(array)
        }
    }

    fun bottomSource(vararg sources: BookSourcePart) {
        Coroutine.async(scope = scope) {
            sources.sortBy { it.customOrder }
            val maxOrder = AppDbProviders.get().bookSourceDao.maxOrder() + 1
            val array = sources.mapIndexed { index, it ->
                it.copy(customOrder = maxOrder + index)
            }
            AppDbProviders.get().bookSourceDao.upOrder(array)
        }
    }

    fun del(sources: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            SourceHelp.deleteBookSourceParts(sources)
        }
    }

    fun update(vararg bookSource: BookSource) {
        Coroutine.async(scope = scope) {
            AppDbProviders.get().bookSourceDao.update(*bookSource)
        }
    }

    fun upOrder(items: List<BookSourcePart>) {
        if (items.isEmpty()) return
        Coroutine.async(scope = scope) {
            AppDbProviders.get().bookSourceDao.upOrder(items)
        }
    }

    fun enable(enable: Boolean, items: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            AppDbProviders.get().bookSourceDao.enable(enable, items)
        }
    }

    fun enableSelection(sources: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            AppDbProviders.get().bookSourceDao.enable(true, sources)
        }
    }

    fun disableSelection(sources: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            AppDbProviders.get().bookSourceDao.enable(false, sources)
        }
    }

    fun enableExplore(enable: Boolean, items: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            AppDbProviders.get().bookSourceDao.enableExplore(enable, items)
        }
    }

    fun enableSelectExplore(sources: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            AppDbProviders.get().bookSourceDao.enableExplore(true, sources)
        }
    }

    fun disableSelectExplore(sources: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            AppDbProviders.get().bookSourceDao.enableExplore(false, sources)
        }
    }

    fun selectionAddToGroups(sources: List<BookSourcePart>, groups: String) {
        Coroutine.async(scope = scope) {
            val array = sources.map {
                it.copy().apply { addGroup(groups) }
            }
            AppDbProviders.get().bookSourceDao.upGroup(array)
        }
    }

    fun selectionRemoveFromGroups(sources: List<BookSourcePart>, groups: String) {
        Coroutine.async(scope = scope) {
            val array = sources.map {
                it.copy().apply { removeGroup(groups) }
            }
            AppDbProviders.get().bookSourceDao.upGroup(array)
        }
    }

    fun addGroup(group: String) {
        Coroutine.async(scope = scope) {
            val sources = AppDbProviders.get().bookSourceDao.noGroup()
            sources.forEach { source ->
                source.bookSourceGroup = group
            }
            AppDbProviders.get().bookSourceDao.update(*sources.toTypedArray())
        }
    }

    fun upGroup(oldGroup: String, newGroup: String?) {
        Coroutine.async(scope = scope) {
            val sources = AppDbProviders.get().bookSourceDao.getByGroup(oldGroup)
            sources.forEach { source ->
                source.bookSourceGroup?.splitNotBlank(",")?.toHashSet()?.let {
                    it.remove(oldGroup)
                    if (!newGroup.isNullOrEmpty()) it.add(newGroup)
                    source.bookSourceGroup = it.joinToString(",")
                }
            }
            AppDbProviders.get().bookSourceDao.update(*sources.toTypedArray())
        }
    }

    fun delGroup(group: String) {
        Coroutine.async(scope = scope) {
            val sources = AppDbProviders.get().bookSourceDao.getByGroup(group)
            sources.forEach { source -> source.removeGroup(group) }
            AppDbProviders.get().bookSourceDao.update(*sources.toTypedArray())
        }
    }
}

/**
 * 书源排序方式 (KMP 版, 替代 app 端 `io.legado.app.ui.book.source.manage.BookSourceSort`)。
 *
 * 放在 `io.legado.app.ui.book.source` 包 (非 `manage` 子包) 避免与 app 端
 * 同名 enum 跨模块同包冲突; app 端 BookSourceViewModel 仍用原 enum, 互不干扰。
 */
enum class BookSourceSort {
    Default, Name, Url, Weight, Update, Enable, Respond
}

/**
 * 书源列表过滤模式 (KMP 版, 替代 app 端按 `R.string` 字面串匹配 searchKey 的语义)。
 *
 * app 端 `setQuery("启用")` 等走 [SourceFilter.Enabled]; host 端负责把 searchKey 字面串
 * 映射到本 sealed class, 解耦 commonMain 对 `R.string` 资源的依赖。
 */
sealed class SourceFilter {
    /** 全部 (对应空 searchKey) */
    object All : SourceFilter()
    /** 启用 (对应 R.string.enabled) */
    object Enabled : SourceFilter()
    /** 禁用 (对应 R.string.disabled) */
    object Disabled : SourceFilter()
    /** 需登录 (对应 R.string.need_login) */
    object NeedLogin : SourceFilter()
    /** 未分组 (对应 R.string.no_group) */
    object NoGroup : SourceFilter()
    /** 启用发现 (对应 R.string.enabled_explore) */
    object EnabledExplore : SourceFilter()
    /** 禁用发现 (对应 R.string.disabled_explore) */
    object DisabledExplore : SourceFilter()
    /** 按分组名筛选 (对应 searchKey.startsWith("group:")) */
    data class Group(val name: String) : SourceFilter()
    /** 关键字搜索 (对应 else 分支) */
    data class Search(val key: String) : SourceFilter()
}
