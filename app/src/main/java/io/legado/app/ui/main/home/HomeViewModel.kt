package io.legado.app.ui.main.home

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.HomeSectionHelp
import io.legado.app.model.webBook.WebBook.getBookListAwait

class HomeViewModel(application: Application) : BaseViewModel(application) {

    val sectionsLiveData = MutableLiveData<List<HomeSection>>()

    /** 携带 sectionId：该展示项书籍数据已更新 */
    val sectionUpdated = MutableLiveData<String>()

    /** 携带 sectionId：该展示项加载状态变化 */
    val sectionLoadingChanged = MutableLiveData<String>()

    val sectionBooksMap = mutableMapOf<String, List<SearchBook>>()
    val loadingSet = mutableSetOf<String>()

    var infiniteSection: HomeSection? = null
        private set
    private val infiniteBookSet = linkedSetOf<SearchBook>()
    private var infinitePage = 1
    private var infiniteHasMore = true
    private var infiniteLoading = false

    /** 无限流是否还有下一页 */
    val hasMoreInfinite get() = infiniteHasMore

    fun init() {
        val sections = HomeSectionHelp.getSections()
        sectionsLiveData.value = sections
        infiniteSection = sections.find { it.style == HomeSection.STYLE_INFINITE_GRID }
        sections.forEach { loadSection(it) }
    }

    fun isLoading(sectionId: String) = loadingSet.contains(sectionId)

    /** 下拉刷新：重新拉取所有展示项数据（无限流重置到第一页） */
    fun refresh() {
        sectionsLiveData.value?.forEach { loadSection(it) }
    }

    private fun loadSection(section: HomeSection) {
        if (section.style == HomeSection.STYLE_INFINITE_GRID) {
            loadInfinite(resetPage = true)
            return
        }
        loadingSet.add(section.id)
        sectionLoadingChanged.postValue(section.id)
        execute {
            val source = appDb.bookSourceDao.getBookSource(section.sourceUrl)
                ?: return@execute
            val result = getBookListAwait(source, section.exploreUrl, 1, isSearch = false)
            // 统一缓存完整一页，切换样式时不丢数据；排行榜仅在渲染层限 5 条
            sectionBooksMap[section.id] = result.books
            sectionUpdated.postValue(section.id)
        }.onError {
            AppLog.put("主页展示项[${section.title}]加载失败", it)
        }.onFinally {
            loadingSet.remove(section.id)
            sectionLoadingChanged.postValue(section.id)
        }
    }

    /** 增量添加：仅追加并加载新展示项，不刷新已加载的项 */
    fun addSection(section: HomeSection) {
        if (section.style == HomeSection.STYLE_INFINITE_GRID) {
            infiniteSection = section
        }
        sectionsLiveData.value = HomeSectionHelp.getSections()
        loadSection(section)
    }

    /**
     * 更新已有展示项。仅当数据源（书源 + 发现 url）变化时才重新拉取内容；
     * 只改标题/样式/封面比例等不触发内容刷新，复用已缓存的书籍数据。
     */
    fun updateSection(section: HomeSection) {
        val old = sectionsLiveData.value?.find { it.id == section.id }
        val sourceChanged = old == null ||
            old.sourceUrl != section.sourceUrl ||
            old.exploreUrl != section.exploreUrl
        // 维护无限流引用
        when {
            section.style == HomeSection.STYLE_INFINITE_GRID -> infiniteSection = section
            old?.style == HomeSection.STYLE_INFINITE_GRID -> {
                infiniteSection = null
                infiniteBookSet.clear()
            }
        }
        sectionsLiveData.value = HomeSectionHelp.getSections()
        if (sourceChanged) {
            if (section.style == HomeSection.STYLE_INFINITE_GRID) {
                infiniteBookSet.clear()
                infinitePage = 1
                infiniteHasMore = true
            } else {
                sectionBooksMap.remove(section.id)
            }
            loadSection(section)
        }
    }

    fun removeSection(section: HomeSection) {
        sectionBooksMap.remove(section.id)
        loadingSet.remove(section.id)
        if (section.style == HomeSection.STYLE_INFINITE_GRID) {
            infiniteSection = null
            infiniteBookSet.clear()
        }
        sectionsLiveData.value = HomeSectionHelp.getSections()
    }

    /** 仅按新顺序重排，不重新拉取任何展示项的数据 */
    fun reorderSections() {
        sectionsLiveData.value = HomeSectionHelp.getSections()
    }

    fun loadInfinite(resetPage: Boolean = false) {
        val section = infiniteSection ?: return
        if (infiniteLoading) return
        if (!resetPage && !infiniteHasMore) return
        if (resetPage) {
            infinitePage = 1
            infiniteBookSet.clear()
            infiniteHasMore = true
        }
        infiniteLoading = true
        loadingSet.add(section.id)
        sectionLoadingChanged.postValue(section.id)
        execute {
            val source = appDb.bookSourceDao.getBookSource(section.sourceUrl)
                ?: return@execute
            val result =
                getBookListAwait(source, section.exploreUrl, infinitePage, isSearch = false)
            infiniteBookSet.addAll(result.books)
            infiniteHasMore = result.hasNextPage && result.books.isNotEmpty()
            sectionBooksMap[section.id] = infiniteBookSet.toList()
            infinitePage++
            sectionUpdated.postValue(section.id)
        }.onError {
            AppLog.put("主页无限流[${section.title}]加载失败", it)
        }.onFinally {
            infiniteLoading = false
            loadingSet.remove(section.id)
            sectionLoadingChanged.postValue(section.id)
        }
    }
}
