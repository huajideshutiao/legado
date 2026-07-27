package io.legado.app.ui.book.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.book.read.AccelerateDecelerateEasing
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk

/** 漫画菜单动作(原 R.menu.book_manga) */
enum class MangaMenuAction {
    REFRESH, CATALOG,
    PRE_DOWNLOAD_NUM, HIDE_TITLE, AUTO_PAGE, AUTO_PAGE_SPEED,
    GIF_AUTO_NEXT, HORIZONTAL_SCROLL, DISABLE_PAGE_ANIM,
    FOOTER_CONFIG, CLICK_REGION_CONFIG, COLOR_FILTER, REVIEW, ADD_BOOKMARK,
}

/**
 * 漫画菜单：Compose 状态持有者。
 * 对外 API(runMenuIn/runMenuOut/upSeekBar/canShowMenu/isVisible)与原 View 版一致，
 * UI 由 [MangaMenuOverlay] 渲染。
 */
class MangaMenu(internal val activity: ReadMangaActivity) {

    val visibleState = MutableTransitionState(false)
    val isVisible: Boolean get() = visibleState.currentState || visibleState.targetState
    var canShowMenu: Boolean = false
    var isMenuOutAnimating = false
        private set
    var animate by mutableStateOf(true)
        private set

    /** 原 vw_menu_bg click listener 有无(出场动画中/拖动进度中禁点) */
    private var bgClickEnabled = true
    private var pendingInEnd = false
    private var pendingOutEnd = false

    /** 原 TitleBar 标题(书名) */
    var title by mutableStateOf<String?>(null)

    var seekMax by mutableIntStateOf(0)
        private set
    var seekValue by mutableIntStateOf(0)
        private set

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        animate = anim
        // 原 menuInListener.onAnimationStart
        activity.upSystemUiVisibility(true)
        // 打断出场：原监听器不会再回调，丢弃出场收尾(与 View 替换动画语义一致)
        pendingOutEnd = false
        isMenuOutAnimating = false
        if (visibleState.targetState && visibleState.isIdle) {
            bgClickEnabled = true
            return
        }
        pendingInEnd = true
        visibleState.targetState = true
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode) {
        if (isMenuOutAnimating) {
            return
        }
        if (isVisible) {
            animate = anim
            // 原 menuOutListener.onAnimationStart
            isMenuOutAnimating = true
            bgClickEnabled = false
            pendingInEnd = false
            pendingOutEnd = true
            visibleState.targetState = false
        }
    }

    fun upSeekBar(value: Int, count: Int) {
        seekMax = count - 1
        seekValue = value
    }

    internal fun onTransitionIdle(shown: Boolean) {
        if (shown && pendingInEnd) {
            pendingInEnd = false
            // 原 menuInListener.onAnimationEnd
            bgClickEnabled = true
        } else if (!shown && pendingOutEnd) {
            pendingOutEnd = false
            // 原 menuOutListener.onAnimationEnd
            canShowMenu = false
            isMenuOutAnimating = false
            activity.upSystemUiVisibility(false)
        }
    }

    internal fun onBgClick() {
        if (bgClickEnabled) runMenuOut()
    }

    internal fun onSeekDrag(value: Int) {
        seekValue = value
    }

    internal fun onSeekDragStart() {
        bgClickEnabled = false
    }

    internal fun onSeekDragStop() {
        bgClickEnabled = true
    }
}

/**
 * 漫画菜单 Overlay：出入场动画语义对齐原 anim_readbook_top_in/out(200ms)、bottom_in(150ms)/out(200ms)，
 * 无动画(E-Ink)时 snap。菜单完全隐藏时除过渡簿记外零组合。
 */
@Composable
fun MangaMenuOverlay(state: MangaMenu) {
    val vs = state.visibleState
    LaunchedEffect(vs.isIdle, vs.currentState) {
        if (vs.isIdle) state.onTransitionIdle(vs.currentState)
    }
    if (!vs.currentState && !vs.targetState) {
        return
    }
    fun spec(duration: Int): FiniteAnimationSpec<IntOffset> =
        if (state.animate) tween(duration, easing = AccelerateDecelerateEasing) else snap()
    Box(Modifier.fillMaxSize()) {
        // 原 vw_menu_bg：菜单期间全屏拦截触摸，点击(在允许时)收起菜单
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { state.onBgClick() }
        )
        AnimatedVisibility(
            visibleState = vs,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(spec(200)) { -it },
            exit = slideOutVertically(spec(200)) { -it },
        ) {
            MangaMenuTopBar(state)
        }
        AnimatedVisibility(
            visibleState = vs,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spec(150)) { it },
            exit = slideOutVertically(spec(200)) { it },
        ) {
            MangaMenuBottom(state)
        }
    }
}

@Composable
private fun MangaMenuTopBar(state: MangaMenu) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    val hasBgImage = remember {
        !ThemeConfig.curBgImagePath.isNullOrBlank()
    }
    val topBg = when {
        eInk -> Color.White
        hasBgImage -> Color.Transparent
        else -> colors.background
    }
    val topText = if (eInk) Color(0xDE000000) else colors.primaryText
    Column(
        Modifier
            .fillMaxWidth()
            .background(topBg)
            .statusBarsPadding()
    ) {
        // toolbar 行：点击空白处打开书籍详情(原 toolbar click)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { state.activity.openBookInfoActivity() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.activity.supportFinishAfterTransition() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = topText,
                )
            }
            Text(
                text = state.title.orEmpty(),
                color = topText,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val onAction = state.activity::onMangaMenuAction
            TopBarActionIcon(R.drawable.ic_refresh_black_24dp, R.string.refresh, topText) {
                onAction(MangaMenuAction.REFRESH)
            }
            TopBarActionIcon(R.drawable.ic_toc, R.string.chapter_list, topText) {
                onAction(MangaMenuAction.CATALOG)
            }
            MangaOverflowMenu(state, topText)
        }
        if (eInk) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colorResource(R.color.divider))
            )
        }
    }
}

@Composable
private fun TopBarActionIcon(iconRes: Int, descRes: Int, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(descRes),
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 顶栏溢出菜单(原 book_manga 菜单 showAsAction=never 项，动态标题/可见性与原 upMenu/onMenuOpened 一致) */
@Composable
private fun MangaOverflowMenu(state: MangaMenu, tint: Color) {
    var expanded by remember { mutableStateOf(false) }
    val activity = state.activity
    Box {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.more_menu),
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val click: (MangaMenuAction) -> Unit = {
                expanded = false
                activity.onMangaMenuAction(it)
            }
            OverflowItem(stringResource(R.string.pre_download_m, AppConfig.mangaPreDownloadNum)) {
                click(MangaMenuAction.PRE_DOWNLOAD_NUM)
            }
            OverflowCheckItem(stringResource(R.string.hide_manga_title), AppConfig.hideMangaTitle) {
                click(MangaMenuAction.HIDE_TITLE)
            }
            OverflowCheckItem(
                stringResource(R.string.enable_auto_page_scroll), activity.enableAutoPage
            ) {
                click(MangaMenuAction.AUTO_PAGE)
            }
            if (activity.enableAutoPage) {
                OverflowItem(
                    stringResource(R.string.manga_auto_page_speed, AppConfig.mangaAutoPageSpeed)
                ) {
                    click(MangaMenuAction.AUTO_PAGE_SPEED)
                }
            }
            if (AppConfig.enableMangaHorizontalScroll) {
                OverflowCheckItem(
                    stringResource(R.string.manga_gif_auto_next), AppConfig.enableMangaGifAutoNext
                ) {
                    click(MangaMenuAction.GIF_AUTO_NEXT)
                }
            }
            OverflowCheckItem(
                stringResource(R.string.enable_manga_horizontal_scroll),
                AppConfig.enableMangaHorizontalScroll,
            ) {
                click(MangaMenuAction.HORIZONTAL_SCROLL)
            }
            OverflowCheckItem(
                stringResource(R.string.disable_manga_page_anim), AppConfig.disableMangaPageAnim
            ) {
                click(MangaMenuAction.DISABLE_PAGE_ANIM)
            }
            OverflowItem(stringResource(R.string.manga_footer_config)) {
                click(MangaMenuAction.FOOTER_CONFIG)
            }
            OverflowItem(stringResource(R.string.click_regional_config)) {
                click(MangaMenuAction.CLICK_REGION_CONFIG)
            }
            OverflowItem(stringResource(R.string.manga_color_filter)) {
                click(MangaMenuAction.COLOR_FILTER)
            }
            if (activity.viewModel.curBookSource?.reviewRule?.reviewUrl.isNullOrBlank() == false) {
                OverflowItem(stringResource(R.string.review)) { click(MangaMenuAction.REVIEW) }
            }
            OverflowItem(stringResource(R.string.bookmark_add)) {
                click(MangaMenuAction.ADD_BOOKMARK)
            }
        }
    }
}

@Composable
private fun OverflowItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = AppTheme.colors.primaryText) },
        onClick = onClick,
    )
}

@Composable
private fun OverflowCheckItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = AppTheme.colors.primaryText) },
        trailingIcon = { AppMenuCheckbox(checked = checked) },
        onClick = onClick,
    )
}

@Composable
private fun MangaMenuBottom(state: MangaMenu) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    val viewModel = state.activity.viewModel
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (eInk) Color.White else colors.bottomBackground)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
    ) {
        if (eInk) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colorResource(R.color.divider))
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.previous_chapter),
                color = colors.primaryText,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .clickable { viewModel.moveToPrevChapter(true) }
                    .padding(vertical = 12.dp),
            )
            MangaSeekBar(state, Modifier.weight(1f))
            Text(
                text = stringResource(R.string.next_chapter),
                color = colors.primaryText,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .clickable { viewModel.moveToNextChapter(true) }
                    .padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun MangaSeekBar(state: MangaMenu, modifier: Modifier = Modifier) {
    // 原 SeekBarChangeListener 语义：拖动中即跳页(fromUser)，拖动期间 bg 禁点
    var dragging by remember { mutableStateOf(false) }
    AppSlider(
        value = state.seekValue,
        max = state.seekMax,
        onValueChange = {
            if (!dragging) {
                dragging = true
                state.onSeekDragStart()
            }
            state.onSeekDrag(it)
            state.activity.skipToPage(it)
        },
        onValueChangeFinished = {
            dragging = false
            state.onSeekDragStop()
        },
        modifier = modifier,
    )
}
