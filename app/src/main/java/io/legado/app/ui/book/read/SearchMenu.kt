package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.R
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.searchContent.SearchResult

/**
 * 搜索界面菜单：Compose 状态持有者（app 端薄壳）。
 *
 * UI Composable 已下沉到 shared/sharedUiMain 的 [SearchMenuOverlay]，本类仅保留
 * 状态属性 + Android 专属逻辑（ReadBook / R.string / CallBack 桥接到
 * [ReadBookActivity] 等，属 L3 不可下沉），实现 [SearchMenuState] 供 shared
 * Composable 解耦访问。
 *
 * 对外 API(runMenuIn/runMenuOut/upSearchResultList/updateSearchResultIndex/invisible)
 * 与原 View 版一致，UI 由 shared [SearchMenuOverlay] 渲染。
 */
class SearchMenu(internal val activity: ReadBookActivity) : SearchMenuState {

    private val callBack: CallBack get() = activity

    private val searchResultList: MutableList<SearchResult> = mutableListOf()
    private var currentSearchResultIndex: Int = -1
    private var lastSearchResultIndex: Int = -1
    private val hasSearchResult: Boolean
        get() = searchResultList.isNotEmpty()
    val selectedSearchResult: SearchResult?
        get() = searchResultList.getOrNull(currentSearchResultIndex)

    /** 整体可见性(原 SearchMenu 根 View 的 visible/invisible) */
    override var rootVisible by mutableStateOf(false)
        private set

    /** 底部菜单出入场(原 ll_bottom_menu 动画) */
    override val bottomVisibleState = MutableTransitionState(false)
    val bottomMenuVisible: Boolean
        get() = rootVisible && (bottomVisibleState.currentState || bottomVisibleState.targetState)

    /** 上/下一处 FAB(原 fabLeft/fabRight，菜单收起后仍驻留) */
    override var fabsVisible by mutableStateOf(false)
        private set

    /** 原 vw_menu_bg 可见性(随底部菜单出入场) */
    override var bgVisible by mutableStateOf(false)
        private set
    private var bgClickEnabled = true

    override var searchInfo by mutableStateOf("")
        private set

    private var isMenuOutAnimating = false
    private var pendingInEnd = false
    private var pendingOutEnd = false
    private var onMenuOutEnd: (() -> Unit)? = null

    fun upSearchResultList(resultList: List<SearchResult>) {
        searchResultList.clear()
        searchResultList.addAll(resultList)
        updateSearchInfo()
    }

    @SuppressLint("SetTextI18n")
    fun updateSearchInfo() {
        ReadBook.curTextChapter?.let {
            searchInfo =
                """${activity.getString(R.string.search_content_size)}: ${searchResultList.size} / 当前章节: ${it.title}"""
        }
    }

    fun updateSearchResultIndex(updateIndex: Int) {
        lastSearchResultIndex = currentSearchResultIndex
        currentSearchResultIndex = when {
            updateIndex < 0 -> 0
            updateIndex >= searchResultList.size -> searchResultList.size - 1
            else -> updateIndex
        }
    }

    /** 隐藏整个搜索菜单(原 View invisible()) */
    fun invisible() {
        rootVisible = false
    }

    fun runMenuIn() {
        rootVisible = true
        bgVisible = true
        // 原 menuBottomIn.onAnimationStart
        fabsVisible = hasSearchResult
        callBack.upSystemUiVisibility()
        pendingOutEnd = false
        isMenuOutAnimating = false
        onMenuOutEnd = null
        if (bottomVisibleState.targetState && bottomVisibleState.isIdle) {
            menuInEnd()
            return
        }
        pendingInEnd = true
        bottomVisibleState.targetState = true
    }

    fun runMenuOut(onMenuOutEnd: (() -> Unit)? = null) {
        if (isMenuOutAnimating) {
            return
        }
        this.onMenuOutEnd = onMenuOutEnd
        if (rootVisible) {
            // 原 menuBottomOut.onAnimationStart
            isMenuOutAnimating = true
            bgClickEnabled = false
            pendingInEnd = false
            pendingOutEnd = true
            bottomVisibleState.targetState = false
        }
    }

    /** 原 menuBottomIn.onAnimationEnd */
    private fun menuInEnd() {
        bgClickEnabled = true
        callBack.upSystemUiVisibility()
    }

    /** 原 menuBottomOut.onAnimationEnd */
    private fun menuOutEnd() {
        isMenuOutAnimating = false
        bgVisible = false
        bgClickEnabled = true
        onMenuOutEnd?.invoke()
        onMenuOutEnd = null
        callBack.upSystemUiVisibility()
    }

    override fun onTransitionIdle(shown: Boolean) {
        if (shown && pendingInEnd) {
            pendingInEnd = false
            menuInEnd()
        } else if (!shown && pendingOutEnd) {
            pendingOutEnd = false
            menuOutEnd()
        }
    }

    override fun onBgClick() {
        if (bgClickEnabled) runMenuOut()
    }

    /** 上一处/下一处 */
    override fun navigate(delta: Int) {
        if (searchResultList.isEmpty()) return
        updateSearchResultIndex(currentSearchResultIndex + delta)
        callBack.navigateToSearch(
            searchResultList[currentSearchResultIndex],
            currentSearchResultIndex
        )
    }

    override fun clickResults() = runMenuOut {
        callBack.openSearchActivity(selectedSearchResult?.query)
    }

    override fun clickMainMenu() = runMenuOut {
        callBack.cancelSelect()
        callBack.showMenuBar()
        invisible()
    }

    override fun clickExit() = runMenuOut {
        callBack.exitSearchMenu()
    }

    interface CallBack {
        fun openSearchActivity(searchWord: String?)
        fun upSystemUiVisibility()
        fun exitSearchMenu()
        fun showMenuBar()
        fun navigateToSearch(searchResult: SearchResult, index: Int)
        fun cancelSelect()
    }
}
