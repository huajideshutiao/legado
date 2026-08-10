package io.legado.app.ui.book.read

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.ui.book.searchContent.SearchResult

/**
 * [SearchMenuState] 的 shared 实现（对照 app 端 `SearchMenu` View 的状态 + 桥接
 * `ReadBookActivity` 动作）。视觉由 [SearchMenuOverlay] 组合，本类只提供状态与动作。
 *
 * 状态映射（对照 app 端 `SearchMenu`）：
 * - [rootVisible] ← 根 FrameLayout 的 visible/invisible（"主菜单"按钮与 [exitSearchMenu] 时隐藏）
 * - [bottomVisibleState] ← llBottomMenu 的 menuBottomIn/Out 动画（[MutableTransitionState] 驱动）
 * - [fabsVisible] ← fabLeft/fabRight（menuBottomIn 动画开始显示，菜单收起后驻留）
 * - [bgVisible] ← vwMenuBg（随底部条出入场）
 * - [searchInfo] ← ll_search_base_info 文案（"search result: size / 当前章节: title"，
 *   label 即 R.string.search_content_size 的取值，与 shared strings.xml 同值，硬编码以
 *   在非 @Composable 上下文取用）
 *
 * 动作桥接（对照 app 端 `SearchMenu.CallBack` → `ReadBookActivity`）：
 * - [navigate] ← fabLeft/fabRight/ivSearchContentUp/ivSearchContentDown：索引钳制 + 跳转
 * - [clickResults] ← llSearchResults：收起后打开搜索页
 * - [clickMainMenu] ← llMainMenu：收起后取消选择 + 弹常规菜单 + 隐藏根（不退出搜索态）
 * - [clickExit] ← llSearchExit：收起后退出搜索态
 */
class SearchMenuStateImpl(
    private val model: ReaderScreenModel,
) : SearchMenuState {

    override var rootVisible by mutableStateOf(false)
    override val bottomVisibleState = MutableTransitionState(false)
    override var fabsVisible by mutableStateOf(false)
    override var bgVisible by mutableStateOf(false)
    override var searchInfo by mutableStateOf("")

    /** 底部条滑出动画结束（[onTransitionIdle] false）后执行，对照 runMenuOut 的 onMenuOutEnd */
    private var onMenuOutEnd: (() -> Unit)? = null

    // region 原版 SearchMenu 对外方法（由 ReaderScreenModel 的搜索态逻辑调用）

    /** 对照 `SearchMenu.upSearchResultList`：灌结果列表 + 刷新文案 */
    fun upSearchResultList(results: List<SearchResult>) {
        model.searchResultList = results
        updateSearchInfo()
    }

    /** 对照 `SearchMenu.updateSearchResultIndex`：钳制到 [0, size-1]（<0 → 0，>=size → size-1） */
    fun updateSearchResultIndex(index: Int) {
        val size = model.searchResultList?.size ?: 0
        model.searchResultIndex = when {
            index < 0 -> 0
            index >= size -> size - 1
            else -> index
        }
    }

    /** 对照 `SearchMenu.runMenuIn`：根可见 + 底部条滑入 + bg/FAB 显示（FAB 仅列表非空时） */
    fun runMenuIn() {
        rootVisible = true
        bgVisible = true
        fabsVisible = model.searchResultList?.isNotEmpty() == true
        bottomVisibleState.targetState = true
    }

    /**
     * 对照 `SearchMenu.runMenuOut`：底部条滑出，动画结束后执行 [onMenuOutEnd]。
     *
     * 时序补全：原版 startAnimation 在 View 已 invisible 时仍会走 onAnimationEnd 回调；
     * 而 MutableTransitionState 在 targetState 已为 false 时重复置值不触发 onTransitionIdle，
     * 故已收起状态下直接执行收尾（否则收起态点"退出/主菜单"回调永远不执行）。
     * 动画进行中：滑入中允许反向滑出；滑出中忽略本次调用保留首次回调（原版
     * isMenuOutAnimating 守卫语义）。
     */
    fun runMenuOut(onMenuOutEnd: (() -> Unit)? = null) {
        this.onMenuOutEnd = onMenuOutEnd
        when {
            !bottomVisibleState.isIdle -> {
                if (bottomVisibleState.targetState) {
                    // 滑入中：反向滑出（原版滑入中允许 startAnimation(menuBottomOut)）
                    bottomVisibleState.targetState = false
                }
                // 滑出中：忽略本次调用，首次回调在 onTransitionIdle(false) 执行
            }

            bottomVisibleState.targetState -> {
                // 展开中：正常滑出，回调在 onTransitionIdle(false) 执行
                bottomVisibleState.targetState = false
            }

            else -> {
                // 已收起：立即执行收尾（对照原版 startAnimation 仍触发 onAnimationEnd）
                onMenuOutEnd?.invoke()
                this.onMenuOutEnd = null
            }
        }
    }

    /** 对照 `SearchMenu.invisible()`：根 View 隐藏（"主菜单"按钮/退出搜索后） */
    fun hideRoot() {
        rootVisible = false
    }

    /**
     * 对照 `SearchMenu.updateSearchInfo`：`"search result: size / 当前章节: title"`。
     * 原版取 curTextChapter.title（排版完成才有）；KMP 取目录当前章标题（跨章跳转瞬间
     * 即显示目标章名，原版此时仍显示旧章名，差异仅在加载过渡期的文案，跳转完成后一致）。
     */
    fun updateSearchInfo() {
        val title = model.currentChapter?.title ?: return
        searchInfo = "search result: ${model.searchResultList?.size ?: 0} / 当前章节: $title"
    }

    // endregion

    // region SearchMenuState 接口

    override fun onTransitionIdle(shown: Boolean) {
        if (!shown) {
            // 对照 menuBottomOut.onAnimationEnd：llBottomMenu.invisible（AnimatedVisibility 出组合）
            // + vwMenuBg.invisible + onMenuOutEnd?.invoke()
            bgVisible = false
            onMenuOutEnd?.invoke()
            onMenuOutEnd = null
        }
        // shown=true 对照 menuBottomIn.onAnimationEnd 的 vwMenuBg 点击监听：Compose 端由
        // SearchMenuOverlay 的 clickable 处理（bgVisible 即监听生效），无需额外动作
    }

    override fun onBgClick() {
        // 原版 vwMenuBg.setOnClickListener { runMenuOut() }
        runMenuOut()
    }

    override fun navigate(delta: Int) {
        // 对照原版四个入口：updateSearchResultIndex(current ± 1) + navigateToSearch(result, index)
        val list = model.searchResultList ?: return
        if (list.isEmpty()) return
        updateSearchResultIndex(model.searchResultIndex + delta)
        val result = list.getOrNull(model.searchResultIndex) ?: return
        model.skipToSearch(result)
    }

    override fun clickResults() {
        // 原版 llSearchResults：runMenuOut { callBack.openSearchActivity(selectedSearchResult?.query) }
        runMenuOut {
            model.openSearchActivity(
                model.searchResultList?.getOrNull(model.searchResultIndex)?.query
            )
        }
    }

    override fun clickMainMenu() {
        // 原版 llMainMenu：runMenuOut { callBack.cancelSelect(); callBack.showMenuBar(); invisible() }
        // 注意：只隐藏搜索菜单，不退出搜索态（isShowingSearchResult 保持 true）
        runMenuOut {
            model.selection.cancel()
            model.menuController.showMenu()
            hideRoot()
        }
    }

    override fun clickExit() {
        // 原版 llSearchExit：runMenuOut { callBack.exitSearchMenu() }
        runMenuOut { model.exitSearchMenu() }
    }

    // endregion
}
