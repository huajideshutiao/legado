package io.legado.app.ui.book.read

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [ReadMenu.kt] 中 [ReadMenuOverlay] / [ReadMenuFab] / [BottomMenuItem] 的 @Preview。
 *
 * [ReadMenuState] 是接口 (app 端 ReadMenu 类实现), Preview 期用内存 stub 实现。
 * 颜色用 Int ARGB (与 ReadMenu 内部 `Color(state.bgColor)` 用法一致)。
 */

/** Preview 期 [ReadMenuState] stub。 */
private class PreviewReadMenuState(
    override val visibleState: MutableTransitionState<Boolean> =
        MutableTransitionState<Boolean>(true).apply { targetState = true },
    override val animate: Boolean = true,
    override val isVisible: Boolean = true,
    override val canShowMenu: Boolean = true,
    override val immersive: Boolean = false,
    override val bgColor: Int = 0xFFF7F8FA.toInt(),
    override val textColor: Int = 0xFF212121.toInt(),
    override val hasBgImage: Boolean = false,
    override val title: String? = "三体",
    override val chapterName: String? = "第一章 科学边界",
    override val chapterUrl: String? = "https://example.com/chapter/1",
    override val chapterNameVisible: Boolean = true,
    override val chapterUrlVisible: Boolean = true,
    override val sourceActionText: String = "书源",
    override val sourceActionVisible: Boolean = true,
    override val titleBarAdditionVisible: Boolean = true,
    override val topMenu: TopMenuState = TopMenuState().apply {
        onLine = true
        isLocalTxt = false
        isEpub = false
        enableReplaceChecked = true
        reSegmentChecked = false
        delRubyChecked = false
        delHChecked = false
        syncProgressVisible = true
        sameTitleRemovedChecked = false
        reviewVisible = false
    },
    override val seekMax: Int = 100,
    override val seekValue: Int = 35,
    override val prevEnabled: Boolean = true,
    override val nextEnabled: Boolean = true,
    override val autoPage: Boolean = false,
    override val isNightTheme: Boolean = false,
) : ReadMenuState {
    override fun onTransitionIdle(shown: Boolean) {}
    override fun onBgClick() {}
    override fun onChapterViewClick() {}
    override fun onChapterViewLongClick() {}
    override fun onOverflowOpened() {}
    override fun sourceLoginVisible(): Boolean = true
    override fun sourcePayVisible(): Boolean = false
    override fun onSourceAction(action: SourceAction) {}
    override fun openBookInfoActivity() {}
    override fun supportFinishAfterTransition() {}
    override fun onTopMenuAction(action: ReadMenuAction) {}
    override fun onSeekDragStart() {}
    override fun onSeekStop(progress: Int) {}
    override fun clickSearch() {}
    override fun clickAutoPage() {}
    override fun clickReplaceRule() {}
    override fun clickNightTheme() {}
    override fun clickPre() {}
    override fun clickNext() {}
    override fun clickCatalog() {}
    override fun clickReadAloud() {}
    override fun longClickReadAloud() {}
    override fun clickFont() {}
    override fun clickSetting() {}
}

// ===== ReadMenuOverlay =====

@AppPreview
@Composable
fun ReadMenuOverlayPreview() = LegadoThemePreview {
    ReadMenuOverlay(state = PreviewReadMenuState())
}

@AppPreview
@Composable
fun ReadMenuOverlayDarkPreview() = LegadoThemePreview(dark = true) {
    ReadMenuOverlay(state = PreviewReadMenuState(
        bgColor = 0xFF17191C.toInt(),
        textColor = 0xFFF8F8F8.toInt(),
        isNightTheme = true,
    ))
}

@AppPreview
@Composable
fun ReadMenuOverlayImmersivePreview() = LegadoThemePreview {
    // 沉浸式: 用正文背景色, 顶栏透明叠加
    ReadMenuOverlay(state = PreviewReadMenuState(
        immersive = true,
        bgColor = 0xFFF5DEB3.toInt(),
        textColor = 0xFF5B4636.toInt(),
    ))
}

@AppPreview
@Composable
fun ReadMenuOverlayLocalTxtPreview() = LegadoThemePreview {
    // 本地 TXT: 顶栏显示 txt_toc_rule, 不显示书源操作
    ReadMenuOverlay(
        state = PreviewReadMenuState(
            sourceActionVisible = false,
            topMenu = TopMenuState().apply {
                onLine = false
                isLocalTxt = true
            },
        )
    )
}

// ===== ReadMenuFab =====

@AppPreview
@Composable
fun ReadMenuFabPreview() = LegadoThemePreview {
    ReadMenuFab(
        iconKey = "ic_search",
        contentDescription = "搜索",
        bg = Color(0xFFF7F8FA),
        pressedBg = Color(0xFFE5E6EB),
        tint = Color(0xFF212121),
        onClick = {},
    )
}

@AppPreview
@Composable
fun ReadMenuFabAutoPagePreview() = LegadoThemePreview {
    ReadMenuFab(
        iconKey = "ic_auto_page",
        contentDescription = "自动翻页",
        bg = Color(0xFF17191C),
        pressedBg = Color(0xFF2A2D32),
        tint = Color(0xFFF8F8F8),
        onClick = {},
    )
}

// ===== BottomMenuItem =====

@AppPreview
@Composable
fun BottomMenuItemPreview() = LegadoThemePreview {
    BottomMenuItem(
        iconKey = "ic_toc",
        labelKey = "chapter_list",
        tint = Color(0xFF212121),
        onClick = {},
    )
}

@AppPreview
@Composable
fun BottomMenuItemReadAloudPreview() = LegadoThemePreview {
    BottomMenuItem(
        iconKey = "ic_read_aloud",
        labelKey = "read_aloud",
        tint = Color(0xFF212121),
        onLongClick = {},
        onClick = {},
    )
}
