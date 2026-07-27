package io.legado.desktop.ui.book.read

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.ui.book.read.SearchMenuState
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.compose.platform.jvmGetString

/**
 * 桌面端 [SearchMenuState] 实现 (对照 app 端 [io.legado.app.ui.book.read.SearchMenu])。
 *
 * 由 [io.legado.desktop.ui.reader.ReaderScreen] 创建并传给 shared
 * [io.legado.app.ui.book.read.SearchMenuOverlay] 渲染。
 *
 * # 状态字段
 *
 * - [rootVisible]: 整体可见性, 由 [runMenuIn]/[invisible] 控制
 * - [bottomVisibleState]: 底部菜单出入场, 由 [runMenuIn]/[runMenuOut] 驱动 targetState
 * - [fabsVisible]: 上/下一处 FAB 可见性, 有搜索结果时显示
 * - [bgVisible]: 遮罩背景可见性, 随底部菜单出入场
 * - [searchInfo]: 搜索信息文本 ("搜索结果: N / 当前章节: title")
 *
 * # 动作回调
 *
 * - [navigate]: 上一处(delta=-1)/下一处(delta=1), 更新索引 + 调 [onNavigate] 跳转章节
 * - [clickResults]: 收起后调 [onOpenSearchContent] 重新打开搜索页
 * - [clickMainMenu]: 收起后调 [onShowMainMenu] 显示主菜单 + invisible
 * - [clickExit]: 收起后调 [onExit] 退出搜索菜单
 *
 * # 与 app 端差异
 *
 * - **页面级定位**: app 端 navigate 后调 skipToSearch → jumpToPosition (searchResultPositions +
 *   ReadBook.skipToPage + selectStartMoveIndex 精确定位到页/行/字符);
 *   桌面端简化为章节级跳转 (viewModel.loadChapter), 精确页面定位待 pageDelegate actual 补全
 * - **searchInfo**: app 端格式 "${search_content_size}: N / 当前章节: title";
 *   桌面端同格式, 用 jvmGetString + chapterTitle 参数
 *
 * @param searchResults 搜索结果列表
 * @param initialIndex 初始结果索引
 * @param onNavigate 索引变化回调 (跳转章节)
 * @param onOpenSearchContent 重新打开搜索页
 * @param onShowMainMenu 显示主菜单
 * @param onExit 退出搜索菜单
 */
internal class DesktopSearchMenuState(
    private val searchResults: List<SearchResult>,
    initialIndex: Int,
    private val onNavigate: (Int) -> Unit,
    private val onOpenSearchContent: () -> Unit,
    private val onShowMainMenu: () -> Unit,
    private val onExit: () -> Unit,
) : SearchMenuState {

    private var currentIndex: Int = initialIndex.coerceIn(0, searchResults.lastIndex.coerceAtLeast(0))

    override var rootVisible by mutableStateOf(false)
        private set

    override val bottomVisibleState = MutableTransitionState(false)

    override var fabsVisible by mutableStateOf(searchResults.isNotEmpty())
        private set

    override var bgVisible by mutableStateOf(false)
        private set

    override var searchInfo by mutableStateOf("")
        private set

    private var pendingInEnd = false
    private var pendingOutEnd = false
    private var onMenuOutEnd: (() -> Unit)? = null

    /** 更新搜索信息文本 (对照 app 端 SearchMenu.updateSearchInfo) */
    fun updateSearchInfo(chapterTitle: String?) {
        // 格式与 app 端一致: "${search_content_size}: N / 当前章节: title"
        // "当前章节" 在 app 端为硬编码中文, 这里保留硬编码以不改变实现逻辑
        searchInfo = buildString {
            append(jvmGetString("search_content_size"))
            append(": ")
            append(searchResults.size)
            if (!chapterTitle.isNullOrBlank()) {
                append(" / 当前章节: ")
                append(chapterTitle)
            }
        }
    }

    /** 显示搜索菜单 (对照 app 端 SearchMenu.runMenuIn) */
    fun runMenuIn() {
        rootVisible = true
        bgVisible = true
        fabsVisible = searchResults.isNotEmpty()
        pendingOutEnd = false
        if (bottomVisibleState.targetState && bottomVisibleState.isIdle) {
            pendingInEnd = false
            return
        }
        pendingInEnd = true
        bottomVisibleState.targetState = true
    }

    /** 收起底部菜单 (对照 app 端 SearchMenu.runMenuOut) */
    private fun runMenuOut(onEnd: (() -> Unit)? = null) {
        this.onMenuOutEnd = onEnd
        pendingInEnd = false
        pendingOutEnd = true
        bottomVisibleState.targetState = false
    }

    override fun onTransitionIdle(shown: Boolean) {
        if (shown && pendingInEnd) {
            pendingInEnd = false
        } else if (!shown && pendingOutEnd) {
            pendingOutEnd = false
            bgVisible = false
            onMenuOutEnd?.invoke()
            onMenuOutEnd = null
        }
    }

    override fun onBgClick() {
        runMenuOut()
    }

    override fun navigate(delta: Int) {
        if (searchResults.isEmpty()) return
        val newIndex = (currentIndex + delta).coerceIn(0, searchResults.lastIndex)
        if (newIndex == currentIndex) return
        currentIndex = newIndex
        onNavigate(newIndex)
    }

    override fun clickResults() = runMenuOut {
        onOpenSearchContent()
    }

    override fun clickMainMenu() = runMenuOut {
        onShowMainMenu()
        rootVisible = false
    }

    override fun clickExit() = runMenuOut {
        onExit()
    }
}
