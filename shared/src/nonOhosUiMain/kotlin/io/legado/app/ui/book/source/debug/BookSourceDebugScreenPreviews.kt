package io.legado.app.ui.book.source.debug

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.Composable
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [BookSourceDebugScreen] 的 @Preview。
 *
 * 假数据: 纯内存 [BookSourceDebugUiState] + no-op [BookSourceDebugUiActions] +
 * 透传 [linkifyText] (直接包 [AnnotatedString], 不做网址链接)。
 */

private val previewState = BookSourceDebugUiState(
    logs = listOf(
        "调试开始",
        "搜索: 我的 https://www.test.com/search?q=我的",
        "详情: 获取成功",
        "ERROR: 目录解析失败",
    ),
    query = "我的",
    helpVisible = false,
    loading = true,
    textMy = "我的",
    textFx = "https://www.test.com/explore",
    clearFocusTick = 0,
)

private val previewStateHelp = previewState.copy(helpVisible = true, loading = false)

private val previewStateEmpty = BookSourceDebugUiState(
    logs = emptyList(),
    query = "",
    helpVisible = false,
    loading = false,
    textMy = "我的",
    textFx = "",
    clearFocusTick = 0,
)

/** no-op actions, 所有回调空实现 */
private object NoOpDebugActions : BookSourceDebugUiActions {
    override fun onBack() {}
    override fun onQueryChange(text: String) {}
    override fun onSubmitQuery() {}
    override fun onSearchFocusChanged(focused: Boolean) {}
    override fun onChipMyClick() {}
    override fun onChipSystemClick() {}
    override fun onChipFxClick() {}
    override fun onChipFxLongClick() {}
    override fun onChipDetailClick() {}
    override fun onChipTocClick() {}
    override fun onChipContentClick() {}
    override fun onShowSearchSrc() {}
    override fun onShowBookSrc() {}
    override fun onShowTocSrc() {}
    override fun onShowContentSrc() {}
    override fun onShowReviewSrc() {}
    override fun onRefreshExplore() {}
    override fun onShowHelp() {}
}

/** 透传 linkifyText: 不做网址链接, 直接包成 AnnotatedString */
private fun fakeLinkify(text: String, color: Color): AnnotatedString = AnnotatedString(text)

@AppPreview
@Composable
fun BookSourceDebugScreenPreview() = LegadoThemePreview {
    BookSourceDebugScreen(previewState, NoOpDebugActions, ::fakeLinkify)
}

@AppPreview
@Composable
fun BookSourceDebugScreenHelpPreview() = LegadoThemePreview {
    BookSourceDebugScreen(previewStateHelp, NoOpDebugActions, ::fakeLinkify)
}

@AppPreview
@Composable
fun BookSourceDebugScreenEmptyPreview() = LegadoThemePreview {
    BookSourceDebugScreen(previewStateEmpty, NoOpDebugActions, ::fakeLinkify)
}

@AppPreview
@Composable
fun BookSourceDebugScreenDarkPreview() = LegadoThemePreview(dark = true) {
    BookSourceDebugScreen(previewState, NoOpDebugActions, ::fakeLinkify)
}
