package io.legado.app.ui.book.read

/*
 * 下沉自 app 端 `ReadMenu.kt`（12 个 @Composable + enums + TopMenuState + 缓动函数）。
 * app 端 `ReadMenu` 状态持有类保留（深度依赖 Activity/lifecycleScope/alert/Intent 等
 * Android 专属 API，属 L3 不可下沉），实现 shared 端 [ReadMenuState] 接口作为薄壳。
 *
 * # 资源访问替换
 *
 * - `stringResource(R.string.xxx)` → `rememberString("xxx")` (key-based, 跨平台)
 * - `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")` (key-based, 跨平台)
 * - `colorResource(R.color.xxx)` → `rememberColor("xxx")` (key-based, 跨平台)
 *
 * # ResourceProvider key 需求清单
 *
 * ## Painter (drawable, 新增)
 * - ic_exchange (换源) / ic_refresh_black_24dp (刷新) / ic_download_line (离线缓存)
 * - ic_translate (设置编码)
 * - ic_auto_page (自动翻页) / ic_auto_page_stop (停止自动翻页)
 * - ic_find_replace (替换规则)
 * - ic_daytime (日间主题) / ic_brightness (夜间主题)
 * - ic_toc (目录) / ic_read_aloud (朗读)
 * - ic_interface_setting (界面设置) / ic_settings (设置)
 *   (ic_arrow_back / ic_search / ic_more_vert 已存在于 ResourceProvider.jvm.kt)
 *
 * ## String (strings.xml, 新增)
 * - change_origin, chapter_change_source, book_change_source
 * - refresh, menu_refresh_dur, menu_refresh_after, menu_refresh_all
 * - offline_cache, set_charset, bookmark_add, edit_content
 * - book_page_anim, sync_book_progress_t, simulated_reading
 * - replace_rule_title, same_title_removed, re_segment, review
 * - del_ruby_tag, del_h_tag, image_style, update_toc
 * - open_fun, use_browser_open, search_content
 * - auto_next_page_stop, auto_next_page, dark_theme
 * - previous_chapter, next_chapter, read_aloud
 * - interface_setting, setting
 * - chapter_pay, set_source_variable, set_book_variable
 * - edit_book_source, disable_book_source
 *   (book_source / more_menu / login / help / log / chapter_list / txt_toc_rule 已存在)
 *
 * ## Color (colors.xml, 新增)
 * - divider (eInk 模式下顶/底栏分隔线)
 *
 * # 复用已下沉的 shared 组件
 *
 * - ReadConfigPanel / TipConfigScreen / PaddingConfigScreen / EffectiveReplacesScreen
 *   均为下沉的配置对话框 Composable，由 app 端 DialogFragment 薄壳包装。
 *   本文件的 ReadMenu 顶/底栏 clickFont/clickSetting/clickReplaceRule 等回调
 *   通过 [ReadMenuState] 桥接到 app 端 ReadBookActivity，由其 showDialogFragment
 *   展示对应 Dialog 薄壳，不在此重复实现配置 UI。
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.ColorUtils
import kotlin.math.PI
import kotlin.math.cos

/** 复刻 View 动画默认 AccelerateDecelerateInterpolator */
val AccelerateDecelerateEasing = Easing { x ->
    (cos((x + 1) * PI) / 2.0 + 0.5).toFloat()
}

/** 阅读页顶栏菜单动作(原 R.menu.book_read 及两个长按子菜单) */
enum class ReadMenuAction {
    CHANGE_SOURCE, CHAPTER_CHANGE_SOURCE, BOOK_CHANGE_SOURCE,
    REFRESH, REFRESH_DUR, REFRESH_AFTER, REFRESH_ALL,
    DOWNLOAD, TOC_REGEX, SET_CHARSET,
    ADD_BOOKMARK, EDIT_CONTENT, PAGE_ANIM, SYNC_PROGRESS, SIMULATED_READING,
    ENABLE_REPLACE, SAME_TITLE_REMOVED, RE_SEGMENT, REVIEW,
    DEL_RUBY_TAG, DEL_H_TAG, IMAGE_STYLE, UPDATE_TOC, LOG, HELP,
}

/** 书源操作动作(原 book_read_source PopupMenu) */
enum class SourceAction {
    LOGIN, CHAPTER_PAY, SET_SOURCE_VARIABLE, SET_BOOK_VARIABLE, EDIT_SOURCE, DISABLE_SOURCE,
}

/** 原 R.menu.book_read 的可见/勾选状态 */
class TopMenuState {
    var onLine by mutableStateOf(false)
    var isLocalTxt by mutableStateOf(false)
    var isEpub by mutableStateOf(false)
    var enableReplaceChecked by mutableStateOf(false)
    var reSegmentChecked by mutableStateOf(false)
    var delRubyChecked by mutableStateOf(false)
    var delHChecked by mutableStateOf(false)
    var syncProgressVisible by mutableStateOf(false)
    var sameTitleRemovedChecked by mutableStateOf(false)
    var reviewVisible by mutableStateOf(false)
}

/**
 * ReadMenu 状态接口：暴露 shared Composable 所需的状态属性 + 动作回调。
 *
 * app 端 [ReadMenu] 类实现此接口，保留 Android 专属逻辑（lifecycleScope / alert /
 * Intent / AppConfig / ReadBookConfig / ThemeConfig / ReadBook / AppWebDav 等深度依赖）。
 * shared 端 [ReadMenuOverlay] 等 Composable 仅依赖本接口，达成 KMP 解耦。
 *
 * # 设计说明
 *
 * - 所有 `val` 属性均为只读视图（app 端用 `mutableStateOf` + `private set` 实现）
 * - `fun` 为动作回调，由 app 端 [ReadMenu] 内部桥接到 [ReadBookActivity]（如
 *   `clickSearch()` → `runMenuOut { callBack.openSearchActivity(null) }`）
 * - `openBookInfoActivity` / `supportFinishAfterTransition` / `onTopMenuAction` 三个
 *   方法原本是 Composable 直接访问 `state.activity.xxx`，下沉后改为接口方法桥接
 */
interface ReadMenuState {
    // ---- 显隐与动画 ----
    val visibleState: MutableTransitionState<Boolean>
    val animate: Boolean
    val isVisible: Boolean
    val canShowMenu: Boolean

    // ---- 沉浸式菜单色彩 ----
    val immersive: Boolean
    val bgColor: Int
    val textColor: Int
    /** 是否设置了背景图（原 ThemeConfig.curBgImagePath 非空），背景图时顶栏透明 */
    val hasBgImage: Boolean

    // ---- 顶栏 ----
    val title: String?
    val chapterName: String?
    val chapterUrl: String?
    val chapterNameVisible: Boolean
    val chapterUrlVisible: Boolean
    val sourceActionText: String
    val sourceActionVisible: Boolean
    val titleBarAdditionVisible: Boolean
    val topMenu: TopMenuState

    // ---- 底栏 ----
    val seekMax: Int
    val seekValue: Int
    val prevEnabled: Boolean
    val nextEnabled: Boolean
    val autoPage: Boolean

    // ---- 动画生命周期回调 ----
    fun onTransitionIdle(shown: Boolean)
    fun onBgClick()

    // ---- 顶栏动作回调 ----
    fun onChapterViewClick()
    fun onChapterViewLongClick()
    fun onOverflowOpened()
    fun sourceLoginVisible(): Boolean
    fun sourcePayVisible(): Boolean
    fun onSourceAction(action: SourceAction)

    // ---- 宿主桥接（原 state.activity.xxx）----
    fun openBookInfoActivity()
    fun supportFinishAfterTransition()
    fun onTopMenuAction(action: ReadMenuAction)

    // ---- 底栏动作回调 ----
    fun onSeekDragStart()
    fun onSeekStop(progress: Int)
    fun clickSearch()
    fun clickAutoPage()
    fun clickReplaceRule()
    fun clickNightTheme()
    fun clickPre()
    fun clickNext()
    fun clickCatalog()
    fun clickReadAloud()
    fun longClickReadAloud()
    fun clickFont()
    fun clickSetting()
}

/**
 * 阅读菜单 Overlay：出入场动画语义对齐原 anim_readbook_top_in/out(200ms)、bottom_in(150ms)/out(200ms)，
 * 无动画(E-Ink)时 snap。菜单完全隐藏时除过渡簿记外零组合。
 */
@Composable
fun ReadMenuOverlay(state: ReadMenuState) {
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
            ReadMenuTopBar(state)
        }
        AnimatedVisibility(
            visibleState = vs,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spec(150)) { it },
            exit = slideOutVertically(spec(200)) { it },
        ) {
            ReadMenuBottom(state)
        }
    }
}

@Composable
private fun ReadMenuTopBar(state: ReadMenuState) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    // hasBgImage 走 app 端 ThemeConfig.curBgImagePath 判断（通过 ReadMenuState 桥接）
    val topBg = when {
        eInk -> Color.White
        state.immersive -> Color(state.bgColor)
        state.hasBgImage -> Color.Transparent
        else -> colors.background
    }
    val topText = when {
        state.immersive -> Color(state.textColor)
        eInk -> Color(0xDE000000)
        else -> colors.primaryText
    }
    val chapterText = if (state.immersive) {
        Color(ColorUtils.withAlpha(ColorUtils.lightenColor(state.textColor), 0.75f))
    } else {
        topText
    }
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
                ) { state.openBookInfoActivity() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.supportFinishAfterTransition() }) {
                Icon(
                    painter = rememberPainter("ic_arrow_back"),
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
            val menu = state.topMenu
            val onAction = state::onTopMenuAction
            if (menu.onLine) {
                TopBarActionIcon(
                    iconKey = "ic_exchange",
                    descKey = "change_origin",
                    tint = topText,
                    onClick = { onAction(ReadMenuAction.CHANGE_SOURCE) },
                    longPressMenu = listOf(
                        "chapter_change_source" to ReadMenuAction.CHAPTER_CHANGE_SOURCE,
                        "book_change_source" to ReadMenuAction.BOOK_CHANGE_SOURCE,
                    ),
                    onAction = onAction,
                )
                TopBarActionIcon(
                    iconKey = "ic_refresh_black_24dp",
                    descKey = "refresh",
                    tint = topText,
                    onClick = { onAction(ReadMenuAction.REFRESH) },
                    longPressMenu = listOf(
                        "menu_refresh_dur" to ReadMenuAction.REFRESH_DUR,
                        "menu_refresh_after" to ReadMenuAction.REFRESH_AFTER,
                        "menu_refresh_all" to ReadMenuAction.REFRESH_ALL,
                    ),
                    onAction = onAction,
                )
                TopBarActionIcon(
                    iconKey = "ic_download_line",
                    descKey = "offline_cache",
                    tint = topText,
                    onClick = { onAction(ReadMenuAction.DOWNLOAD) },
                )
            }
            if (menu.isLocalTxt) {
                TopBarActionIcon(
                    iconKey = "ic_exchange",
                    descKey = "txt_toc_rule",
                    tint = topText,
                    onClick = { onAction(ReadMenuAction.TOC_REGEX) },
                )
            }
            if (!menu.onLine) {
                TopBarActionIcon(
                    iconKey = "ic_translate",
                    descKey = "set_charset",
                    tint = topText,
                    onClick = { onAction(ReadMenuAction.SET_CHARSET) },
                )
            }
            TopOverflowMenu(state, topText)
        }
        if (state.titleBarAdditionVisible) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    if (state.chapterNameVisible) {
                        Text(
                            text = state.chapterName.orEmpty(),
                            color = chapterText,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { state.onChapterViewClick() },
                                    onLongClick = { state.onChapterViewLongClick() },
                                )
                                .padding(horizontal = 16.dp),
                        )
                    }
                    if (state.chapterUrlVisible) {
                        Text(
                            text = state.chapterUrl.orEmpty(),
                            color = chapterText,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { state.onChapterViewClick() },
                                    onLongClick = { state.onChapterViewLongClick() },
                                )
                                .padding(horizontal = 16.dp),
                        )
                    }
                }
                if (state.sourceActionVisible) {
                    SourceActionButton(state)
                }
            }
        }
        if (eInk) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(rememberColor("divider"))
            )
        }
    }
}

/** 顶栏图标动作，长按弹出子菜单(原 iconItemOnLongClick + PopupMenu) */
@Composable
private fun TopBarActionIcon(
    iconKey: String,
    descKey: String,
    tint: Color,
    onClick: () -> Unit,
    longPressMenu: List<Pair<String, ReadMenuAction>>? = null,
    onAction: ((ReadMenuAction) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = longPressMenu?.let { { expanded = true } },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = rememberPainter(iconKey),
                contentDescription = rememberString(descKey),
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
        if (longPressMenu != null && onAction != null) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                longPressMenu.forEach { (textKey, action) ->
                    DropdownMenuItem(
                        text = { Text(rememberString(textKey), color = AppTheme.colors.primaryText) },
                        onClick = {
                            expanded = false
                            onAction(action)
                        },
                    )
                }
            }
        }
    }
}

/** 顶栏溢出菜单(原 book_read 菜单 showAsAction=never 项) */
@Composable
private fun TopOverflowMenu(state: ReadMenuState, tint: Color) {
    var expanded by remember { mutableStateOf(false) }
    val menu = state.topMenu
    val onAction = state::onTopMenuAction
    Box {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable {
                    state.onOverflowOpened()
                    expanded = true
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = rememberPainter("ic_more_vert"),
                contentDescription = rememberString("more_menu"),
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val click: (ReadMenuAction) -> Unit = {
                expanded = false
                onAction(it)
            }
            OverflowItem("bookmark_add") { click(ReadMenuAction.ADD_BOOKMARK) }
            OverflowItem("edit_content") { click(ReadMenuAction.EDIT_CONTENT) }
            OverflowItem("book_page_anim") { click(ReadMenuAction.PAGE_ANIM) }
            if (menu.syncProgressVisible) {
                OverflowItem("sync_book_progress_t") { click(ReadMenuAction.SYNC_PROGRESS) }
            }
            OverflowItem("simulated_reading") { click(ReadMenuAction.SIMULATED_READING) }
            OverflowCheckItem("replace_rule_title", menu.enableReplaceChecked) {
                click(ReadMenuAction.ENABLE_REPLACE)
            }
            OverflowCheckItem("same_title_removed", menu.sameTitleRemovedChecked) {
                click(ReadMenuAction.SAME_TITLE_REMOVED)
            }
            OverflowCheckItem("re_segment", menu.reSegmentChecked) {
                click(ReadMenuAction.RE_SEGMENT)
            }
            if (menu.reviewVisible) {
                OverflowItem("review") { click(ReadMenuAction.REVIEW) }
            }
            if (menu.isEpub) {
                OverflowCheckItem("del_ruby_tag", menu.delRubyChecked) {
                    click(ReadMenuAction.DEL_RUBY_TAG)
                }
                OverflowCheckItem("del_h_tag", menu.delHChecked) {
                    click(ReadMenuAction.DEL_H_TAG)
                }
            }
            OverflowItem("image_style") { click(ReadMenuAction.IMAGE_STYLE) }
            OverflowItem("update_toc") { click(ReadMenuAction.UPDATE_TOC) }
            OverflowItem("log") { click(ReadMenuAction.LOG) }
            OverflowItem("help") { click(ReadMenuAction.HELP) }
        }
    }
}

@Composable
private fun OverflowItem(textKey: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(rememberString(textKey), color = AppTheme.colors.primaryText) },
        onClick = onClick,
    )
}

@Composable
private fun OverflowCheckItem(textKey: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(rememberString(textKey), color = AppTheme.colors.primaryText) },
        trailingIcon = { AppMenuCheckbox(checked = checked) },
        onClick = onClick,
    )
}

/** 书源操作按钮(原 ArcoButton Primary Small + book_read_source PopupMenu) */
@Composable
private fun SourceActionButton(state: ReadMenuState) {
    var expanded by remember { mutableStateOf(false) }
    var loginVisible by remember { mutableStateOf(false) }
    var payVisible by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .padding(end = 16.dp)
                .height(28.dp)
                .widthIn(max = 120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AppTheme.colors.accent)
                .clickable {
                    loginVisible = state.sourceLoginVisible()
                    payVisible = state.sourcePayVisible()
                    expanded = true
                }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.sourceActionText,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val click: (SourceAction) -> Unit = {
                expanded = false
                state.onSourceAction(it)
            }
            if (loginVisible) {
                OverflowItem("login") { click(SourceAction.LOGIN) }
            }
            if (payVisible) {
                OverflowItem("chapter_pay") { click(SourceAction.CHAPTER_PAY) }
            }
            OverflowItem("set_source_variable") {
                click(SourceAction.SET_SOURCE_VARIABLE)
            }
            OverflowItem("set_book_variable") {
                click(SourceAction.SET_BOOK_VARIABLE)
            }
            OverflowItem("edit_book_source") { click(SourceAction.EDIT_SOURCE) }
            OverflowItem("disable_book_source") {
                click(SourceAction.DISABLE_SOURCE)
            }
        }
    }
}

@Composable
private fun ReadMenuBottom(state: ReadMenuState) {
    val eInk = LocalEInk.current
    val bg = Color(state.bgColor)
    val text = Color(state.textColor)
    val pressedBg = Color(ColorUtils.darkenColor(state.bgColor))
    Column(Modifier.fillMaxWidth()) {
        // 悬浮按钮行(原 ll_floating_button，透明底，空白处点击穿透到 bg 收起菜单)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReadMenuFab(
                iconKey = "ic_search",
                contentDescription = rememberString("search_content"),
                bg = bg, pressedBg = pressedBg, tint = text,
            ) { state.clickSearch() }
            Spacer(Modifier.weight(1f))
            ReadMenuFab(
                iconKey = if (state.autoPage) "ic_auto_page_stop" else "ic_auto_page",
                contentDescription = rememberString(
                    if (state.autoPage) "auto_next_page_stop" else "auto_next_page"
                ),
                bg = bg, pressedBg = pressedBg, tint = text,
            ) { state.clickAutoPage() }
            Spacer(Modifier.weight(1f))
            ReadMenuFab(
                iconKey = "ic_find_replace",
                contentDescription = rememberString("replace_rule_title"),
                bg = bg, pressedBg = pressedBg, tint = text,
            ) { state.clickReplaceRule() }
            Spacer(Modifier.weight(1f))
            ReadMenuFab(
                iconKey = "ic_brightness",
                contentDescription = rememberString("dark_theme"),
                bg = bg, pressedBg = pressedBg, tint = text,
            ) { state.clickNightTheme() }
        }
        // 底部设置栏(原 ll_bottom_bg)
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (eInk) Color.White else bg)
                .windowInsetsPadding(
                    WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                ),
        ) {
            if (eInk) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(rememberColor("divider"))
                )
            }
            // 章节进度行
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChapterNavText(
                    text = rememberString("previous_chapter"),
                    color = text,
                    enabled = state.prevEnabled,
                ) { state.clickPre() }
                ReadSeekBar(state, Modifier.weight(1f))
                ChapterNavText(
                    text = rememberString("next_chapter"),
                    color = text,
                    enabled = state.nextEnabled,
                ) { state.clickNext() }
            }
            // 目录/朗读/界面/设置
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                BottomMenuItem("ic_toc", "chapter_list", text) {
                    state.clickCatalog()
                }
                Spacer(Modifier.weight(2f))
                BottomMenuItem(
                    "ic_read_aloud", "read_aloud", text,
                    onLongClick = { state.longClickReadAloud() },
                ) { state.clickReadAloud() }
                Spacer(Modifier.weight(2f))
                BottomMenuItem("ic_interface_setting", "interface_setting", text) {
                    state.clickFont()
                }
                Spacer(Modifier.weight(2f))
                BottomMenuItem("ic_settings", "setting", text) {
                    state.clickSetting()
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ReadSeekBar(state: ReadMenuState, modifier: Modifier = Modifier) {
    // 拖动中仅移动滑块，抬手才跳转(原 SeekBarChangeListener 语义)；-1 表示未拖动
    var dragValue by remember { mutableIntStateOf(-1) }
    AppSlider(
        value = if (dragValue >= 0) dragValue else state.seekValue,
        max = state.seekMax,
        onValueChange = {
            if (dragValue < 0) state.onSeekDragStart()
            dragValue = it
        },
        onValueChangeFinished = {
            val v = dragValue
            dragValue = -1
            if (v >= 0) state.onSeekStop(v)
        },
        modifier = modifier,
    )
}

@Composable
private fun ChapterNavText(
    text: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // 禁用时仍消费点击(原 View disabled 仍拦截触摸，不落到 bg 收起菜单)
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        color = color,
        fontSize = 14.sp,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clickable(
                interactionSource = interaction,
                indication = if (enabled) LocalIndication.current else null,
            ) { if (enabled) onClick() }
            .padding(vertical = 12.dp),
    )
}

/** 复刻 mini FloatingActionButton：40dp 圆、底栏色底、按压加深(原 Selector pressed) */
@Composable
fun ReadMenuFab(
    iconKey: String,
    contentDescription: String?,
    bg: Color,
    pressedBg: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .padding(16.dp)
            .size(40.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(if (pressed) pressedBg else bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = contentDescription,
            tint = tint,
            modifier = iconModifier.size(24.dp),
        )
    }
}

/** 底栏图标+文字项(原 60dp 竖排按钮) */
@Composable
fun BottomMenuItem(
    iconKey: String,
    labelKey: String,
    tint: Color,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(60.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(top = 4.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = rememberString(labelKey),
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = rememberString(labelKey),
            color = tint,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
