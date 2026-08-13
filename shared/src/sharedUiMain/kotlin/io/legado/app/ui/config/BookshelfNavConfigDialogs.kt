package io.legado.app.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.BottomNavTag
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfigConstants
import io.legado.app.help.config.AppConfigRanges
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.FlowBus
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookshelf
import legado.shared.generated.resources.bookshelf_layout
import legado.shared.generated.resources.bookshelf_list_intro_lines
import legado.shared.generated.resources.bookshelf_list_show_intro
import legado.shared.generated.resources.bookshelf_list_show_kind
import legado.shared.generated.resources.bookshelf_px_0
import legado.shared.generated.resources.bookshelf_px_1
import legado.shared.generated.resources.bookshelf_px_2
import legado.shared.generated.resources.bookshelf_px_3
import legado.shared.generated.resources.bookshelf_px_4
import legado.shared.generated.resources.bookshelf_px_5
import legado.shared.generated.resources.bookshelf_show_group_count
import legado.shared.generated.resources.bottom_bar_height
import legado.shared.generated.resources.bottom_bar_icon_size
import legado.shared.generated.resources.bottom_bar_items_order
import legado.shared.generated.resources.bottom_bar_label_auto
import legado.shared.generated.resources.bottom_bar_label_labeled
import legado.shared.generated.resources.bottom_bar_label_mode
import legado.shared.generated.resources.bottom_bar_label_selected
import legado.shared.generated.resources.bottom_bar_label_unlabeled
import legado.shared.generated.resources.bottom_nav_config
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.column_count
import legado.shared.generated.resources.discovery
import legado.shared.generated.resources.explore_item_style
import legado.shared.generated.resources.explore_style
import legado.shared.generated.resources.fixed_width_mode
import legado.shared.generated.resources.grid_width_dp
import legado.shared.generated.resources.group_style
import legado.shared.generated.resources.home
import legado.shared.generated.resources.ic_arrow_drop_down
import legado.shared.generated.resources.my
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.reset
import legado.shared.generated.resources.show_bookshelf_fast_scroller
import legado.shared.generated.resources.show_last_update_time
import legado.shared.generated.resources.show_unread
import legado.shared.generated.resources.sort
import legado.shared.generated.resources.view
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * 书架布局配置对话框 (shared Compose 重建, 对照 app 端 ThemeConfigHost.configBookshelf
 * + dialog_bookshelf_config.xml)。供桌面端平台能力调用; app 端仍走原 Fragment 实现。
 *
 * 分组样式/样式/固定宽/列数/简介行数/排序等逐项等价; 列表模式专属项按 isList 显隐。
 * 读取经 [io.legado.app.help.config.AppConfigProviders], 写回走 [PreferenceProviders]
 * (桌面端 AppConfigAccessor 缓存经 pref 变更监听刷新), 变更通知对照 app 端
 * NOTIFY_MAIN / BOOKSHELF_REFRESH / RECREATE 三路事件。
 */
@Composable
fun BookshelfLayoutConfigDialog(onDismiss: () -> Unit) {
    val prefs = remember { PreferenceProviders.get() }
    val eventBus = LocalEventBusProvider.current
    val appConfig = io.legado.app.help.config.AppConfigProviders.get()

    // 校验态 (对照原版 spGroupStyle 越界回 0 / rgSort 越界回 0, 并回写 pref)
    var initGroupStyle = appConfig.bookGroupStyle
    if (initGroupStyle !in 0..1) {
        initGroupStyle = 0
        prefs.putInt(PreferKey.bookGroupStyle, 0)
    }
    var initSort = appConfig.bookshelfSort
    if (initSort !in 0..5) {
        initSort = 0
        prefs.putInt(PreferKey.bookshelfSort, 0)
    }
    val groupStyle = remember { mutableIntStateOf(initGroupStyle) }
    val bookshelfSort = remember { mutableIntStateOf(initSort) }
    val fixedWidthMode = remember { mutableStateOf(appConfig.bookshelfFixedWidthMode) }
    val gridWidthText = remember { mutableStateOf(appConfig.bookshelfGridWidth.toString()) }
    val introLines = remember { mutableIntStateOf(appConfig.bookshelfListIntroLines) }
    val selectedCols = remember { mutableIntStateOf(BookSource.exploreStyleCols(appConfig.bookshelfLayout)) }
    val isVideo = remember { mutableStateOf(BookSource.exploreStyleIsVideo(appConfig.bookshelfLayout)) }
    val showUnread = remember { mutableStateOf(appConfig.showUnread) }
    val showFastScroller = remember { mutableStateOf(appConfig.showBookshelfFastScroller) }
    val showLastUpdateTime = remember { mutableStateOf(appConfig.showLastUpdateTime) }
    val showGroupCount = remember { mutableStateOf(appConfig.bookshelfShowGroupCount) }
    val showKind = remember { mutableStateOf(appConfig.bookshelfListShowKind) }
    val showIntro = remember { mutableStateOf(appConfig.bookshelfListShowIntro) }

    val colors = AppTheme.colors
    val groupStyles = stringArrayResource(Res.array.group_style)
    val itemStyles = stringArrayResource(Res.array.explore_item_style)
    val sortLabelRes = listOf(
        Res.string.bookshelf_px_0, Res.string.bookshelf_px_1, Res.string.bookshelf_px_2,
        Res.string.bookshelf_px_3, Res.string.bookshelf_px_4, Res.string.bookshelf_px_5,
    )

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        // 圆角/底色对齐 alert DSL AppAlertDialogContent (AppDialog 窗口无背景)
        Surface(
            modifier = Modifier.appDialogSize(fullHeight = true),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column(Modifier.fillMaxSize()) {
                DialogTitleBar(
                    title = stringResource(Res.string.bookshelf_layout),
                    onBack = onDismiss,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    ConfigDropdownRow(
                        label = stringResource(Res.string.group_style),
                        options = groupStyles,
                        selectedIndex = groupStyle.intValue,
                        onSelect = { groupStyle.intValue = it },
                    )
                    ConfigDropdownRow(
                        label = stringResource(Res.string.explore_style),
                        options = itemStyles,
                        selectedIndex = if (isVideo.value) 1 else 0,
                        onSelect = { isVideo.value = it == 1 },
                    )
                    ConfigSwitchRow(stringResource(Res.string.show_unread), showUnread.value) {
                        showUnread.value = it
                    }
                    // 快速滚动条 (对照原版 sw_show_bookshelf_fast_scroller, 位于固定宽模式前)
                    ConfigSwitchRow(
                        stringResource(Res.string.show_bookshelf_fast_scroller),
                        showFastScroller.value,
                    ) {
                        showFastScroller.value = it
                    }
                    ConfigSwitchRow(
                        stringResource(Res.string.bookshelf_show_group_count),
                        showGroupCount.value,
                    ) {
                        showGroupCount.value = it
                    }
                    ConfigSwitchRow(
                        stringResource(Res.string.fixed_width_mode),
                        fixedWidthMode.value
                    ) {
                        fixedWidthMode.value = it
                    }
                    // 视图小节 (对照原版 tv_layout_title)
                    Text(
                        stringResource(Res.string.view),
                        color = colors.accent,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    // 列数 (对照原版 sb_column_count 0..6, 固定宽模式隐藏)
                    if (!fixedWidthMode.value) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(Res.string.column_count),
                                color = colors.primaryText,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            AppSlider(
                                value = selectedCols.intValue,
                                max = 6,
                                onValueChange = { selectedCols.intValue = it },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                selectedCols.intValue.toString(),
                                color = colors.primaryText,
                                fontSize = 16.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    // 列表模式专属项 (对照原版 updateListOnlyVisibility)
                    val isList = !fixedWidthMode.value && selectedCols.intValue <= 1
                    if (isList) {
                        ConfigSwitchRow(
                            stringResource(Res.string.bookshelf_list_show_kind),
                            showKind.value,
                        ) {
                            showKind.value = it
                        }
                        ConfigSwitchRow(
                            stringResource(Res.string.bookshelf_list_show_intro),
                            showIntro.value,
                        ) {
                            showIntro.value = it
                        }
                        // 简介行数 1..5 (对照原版 tv_intro_lines_minus/plus, 未开简介降透明度)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .alpha(if (showIntro.value) 1f else 0.4f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(Res.string.bookshelf_list_intro_lines),
                                color = colors.primaryText,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "-",
                                color = colors.primaryText,
                                fontSize = 18.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable { if (introLines.intValue > 1) introLines.intValue-- },
                            )
                            Text(
                                introLines.intValue.toString(),
                                color = colors.primaryText,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                "+",
                                color = colors.primaryText,
                                fontSize = 18.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable { if (introLines.intValue < 5) introLines.intValue++ },
                            )
                        }
                        ConfigSwitchRow(
                            stringResource(Res.string.show_last_update_time),
                            showLastUpdateTime.value,
                        ) {
                            showLastUpdateTime.value = it
                        }
                    }
                    // 固定宽模式: 网格宽度 dp (对照原版 ll_fixed_width / et_grid_width)
                    if (fixedWidthMode.value) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(Res.string.grid_width_dp),
                                color = colors.primaryText
                            )
                            AppTextField(
                                value = gridWidthText.value,
                                onValueChange = {
                                    gridWidthText.value = it.filter { c -> c.isDigit() }
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Text("dp", color = colors.primaryText)
                        }
                    }
                    // 排序小节 (对照原版 rg_sort 6 项单选)
                    Text(
                        stringResource(Res.string.sort),
                        color = colors.accent,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    Column(Modifier.selectableGroup()) {
                        sortLabelRes.forEachIndexed { i, res ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = bookshelfSort.intValue == i,
                                        role = Role.RadioButton,
                                        onClick = { bookshelfSort.intValue = i },
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppRadioButton(
                                    selected = bookshelfSort.intValue == i,
                                    onClick = null
                                )
                                Text(
                                    stringResource(res),
                                    color = colors.primaryText,
                                    fontSize = 15.sp,
                                )
                            }
                        }
                    }
                }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                AppTextButton(text = stringResource(Res.string.cancel), onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                AppTextButton(text = stringResource(Res.string.ok)) {
                    onDismiss()
                    var notifyMain = false
                    var recreate = false
                    if (appConfig.bookGroupStyle != groupStyle.intValue) {
                        prefs.putInt(PreferKey.bookGroupStyle, groupStyle.intValue)
                        notifyMain = true
                    }
                    if (appConfig.showUnread != showUnread.value) {
                        prefs.putBoolean(PreferKey.showUnread, showUnread.value)
                        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
                    }
                    if (appConfig.showLastUpdateTime != showLastUpdateTime.value) {
                        prefs.putBoolean(PreferKey.showLastUpdateTime, showLastUpdateTime.value)
                        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
                    }
                    if (appConfig.bookshelfShowGroupCount != showGroupCount.value) {
                        prefs.putBoolean(PreferKey.bookshelfShowGroupCount, showGroupCount.value)
                        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
                    }
                    if (appConfig.showBookshelfFastScroller != showFastScroller.value) {
                        prefs.putBoolean(PreferKey.showBookshelfFastScroller, showFastScroller.value)
                        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
                    }
                    if (appConfig.bookshelfListShowKind != showKind.value) {
                        prefs.putBoolean(PreferKey.bookshelfListShowKind, showKind.value)
                        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
                    }
                    if (appConfig.bookshelfListShowIntro != showIntro.value) {
                        prefs.putBoolean(PreferKey.bookshelfListShowIntro, showIntro.value)
                        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
                    }
                    if (appConfig.bookshelfListIntroLines != introLines.intValue) {
                        prefs.putInt(PreferKey.bookshelfListIntroLines, introLines.intValue)
                        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
                    }
                    if (appConfig.bookshelfSort != bookshelfSort.intValue) {
                        prefs.putInt(PreferKey.bookshelfSort, bookshelfSort.intValue)
                        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
                    }
                    // 对照原版 makeLayoutStyle: 视频置 EXPLORE_STYLE_VIDEO_FLAG, 列数取低 3 位
                    val newLayout =
                        (if (isVideo.value) BookSource.EXPLORE_STYLE_VIDEO_FLAG else 0) or
                            (selectedCols.intValue and BookSource.EXPLORE_STYLE_COLS_MASK)
                    val newGridWidth = gridWidthText.value.toIntOrNull() ?: 120
                    if (appConfig.bookshelfLayout != newLayout ||
                        appConfig.bookshelfFixedWidthMode != fixedWidthMode.value ||
                        appConfig.bookshelfGridWidth != newGridWidth
                    ) {
                        prefs.putInt(PreferKey.bookshelfLayout, newLayout)
                        prefs.putBoolean(PreferKey.bookshelfFixedWidthMode, fixedWidthMode.value)
                        prefs.putInt(PreferKey.bookshelfGridWidth, newGridWidth)
                        recreate = true
                    }
                    if (recreate) {
                        eventBus.emitRecreate()
                    } else if (notifyMain) {
                        FlowBus.with(EventBus.NOTIFY_MAIN).tryEmit("")
                    }
                }
            }
            }
        }
    }
}

/**
 * 底栏配置对话框 (shared Compose 重建, 对照 app 端 ThemeConfigHost.configBottomNav
 * + dialog_bottom_nav_config.xml)。供桌面端平台能力调用; app 端仍走原 Fragment 实现。
 *
 * 顺序网格: 点按开关启用, 横向拖拽换序 (对照 rv_nav_items + ItemTouchHelper);
 * 高度/图标大小滑条 (对照 sb_height/sb_icon); 标签模式单选 (对照 rg_label_mode)。
 */
@Composable
fun BottomNavConfigDialog(onDismiss: () -> Unit) {
    val prefs = remember { PreferenceProviders.get() }
    val eventBus = LocalEventBusProvider.current
    val appConfig = io.legado.app.help.config.AppConfigProviders.get()
    val colors = AppTheme.colors

    val defaultNavItems = listOf(
        BottomNavConfigItem(BottomNavTag.HOME, Res.string.home, appConfig.showHome),
        BottomNavConfigItem(BottomNavTag.BOOKSHELF, Res.string.bookshelf, true),
        BottomNavConfigItem(BottomNavTag.DISCOVERY, Res.string.discovery, appConfig.showDiscovery),
        BottomNavConfigItem(BottomNavTag.MY, Res.string.my, true),
    )
    // 对照原版: 保存顺序合法才采用, 否则回退默认顺序
    val savedOrder = appConfig.bottomNavItemOrder.orEmpty().split(",").filter { it.isNotEmpty() }
    val defaultTags = defaultNavItems.map { it.tag }.toSet()
    val initialItems = if (savedOrder.size == defaultNavItems.size
        && savedOrder.toSet() == defaultTags
    ) {
        savedOrder.mapNotNull { tag -> defaultNavItems.find { it.tag == tag } }
    } else {
        defaultNavItems
    }
    val navItems = remember { SnapshotStateList<BottomNavConfigItem>().apply { addAll(initialItems) } }
    val height = remember { mutableIntStateOf(appConfig.bottomBarHeight) }
    val iconSize = remember { mutableIntStateOf(appConfig.bottomBarIconSize) }
    val labelMode = remember { mutableIntStateOf(appConfig.bottomBarLabelMode) }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        // 圆角/底色对齐 alert DSL AppAlertDialogContent (AppDialog 窗口无背景)
        Surface(
            modifier = Modifier.appDialogSize(fullHeight = true),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column(Modifier.fillMaxSize()) {
                DialogTitleBar(
                    title = stringResource(Res.string.bottom_nav_config),
                    onBack = onDismiss,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Text(
                        stringResource(Res.string.bottom_bar_items_order),
                        color = colors.primaryText,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    var dragIndex by remember { mutableIntStateOf(-1) }
                    var dragAccum by remember { mutableFloatStateOf(0f) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(navItems) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val cellWidth =
                                            (size.width / navItems.size).coerceAtLeast(1)
                                        dragIndex = (offset.x / cellWidth).toInt()
                                            .coerceIn(0, navItems.lastIndex)
                                        dragAccum = 0f
                                    },
                                    onDragEnd = { dragIndex = -1; dragAccum = 0f },
                                    onDragCancel = { dragIndex = -1; dragAccum = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        if (dragIndex in navItems.indices) {
                                            dragAccum += amount.x
                                            val cellWidth =
                                                (size.width / navItems.size).coerceAtLeast(1)
                                            // 越过半个格宽换一位 (对照 ItemTouchHelper 默认阈值)
                                            while (dragAccum >= cellWidth / 2f && dragIndex < navItems.lastIndex) {
                                                swapItems(navItems, dragIndex, dragIndex + 1)
                                                dragIndex++
                                                dragAccum -= cellWidth
                                            }
                                            while (dragAccum <= -cellWidth / 2f && dragIndex > 0) {
                                                swapItems(navItems, dragIndex, dragIndex - 1)
                                                dragIndex--
                                                dragAccum += cellWidth
                                            }
                                        }
                                    },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        navItems.forEachIndexed { index, item ->
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable(enabled = !item.locked) {
                                        navItems[index] = item.copy(enabled = !item.enabled)
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val tint = if (item.enabled) colors.accent else colors.primaryText
                                Icon(
                                    painter = rememberPainter(
                                        bottomNavIconKey(
                                            item.tag,
                                            item.enabled
                                        )
                                    ),
                                    contentDescription = stringResource(item.nameRes),
                                    tint = tint,
                                    modifier = Modifier.size(iconSize.intValue.dp),
                                )
                                Text(
                                    stringResource(item.nameRes),
                                    color = tint,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 高度滑条 (对照 sb_height: 36..80)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.bottom_bar_height),
                            color = colors.primaryText,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${height.intValue}dp", color = colors.primaryText)
                    }
                    AppSlider(
                        value = height.intValue - AppConfigRanges.bottomBarHeight.first,
                        max = AppConfigRanges.bottomBarHeight.last - AppConfigRanges.bottomBarHeight.first,
                        onValueChange = {
                            height.intValue = it + AppConfigRanges.bottomBarHeight.first
                        },
                    )
                    // 图标大小滑条 (对照 sb_icon: 18..36)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.bottom_bar_icon_size),
                            color = colors.primaryText,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${iconSize.intValue}dp", color = colors.primaryText)
                    }
                    AppSlider(
                        value = iconSize.intValue - AppConfigRanges.bottomBarIconSize.first,
                        max = AppConfigRanges.bottomBarIconSize.last - AppConfigRanges.bottomBarIconSize.first,
                        onValueChange = {
                            iconSize.intValue = it + AppConfigRanges.bottomBarIconSize.first
                        },
                    )
                    // 标签模式单选 (对照 rg_label_mode: 0=隐藏 1=常显 2=仅选中 3=自动)
                    Text(
                        stringResource(Res.string.bottom_bar_label_mode),
                        color = colors.primaryText,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .selectableGroup(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val labelRes = listOf(
                            Res.string.bottom_bar_label_unlabeled,
                            Res.string.bottom_bar_label_labeled,
                            Res.string.bottom_bar_label_selected,
                            Res.string.bottom_bar_label_auto,
                        )
                        labelRes.forEachIndexed { i, res ->
                            Row(
                                Modifier
                                    .selectable(
                                        selected = labelMode.intValue == i,
                                        onClick = { labelMode.intValue = i },
                                    )
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppRadioButton(selected = labelMode.intValue == i, onClick = null)
                                Text(stringResource(res), color = colors.primaryText)
                            }
                        }
                    }
                }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                AppTextButton(text = stringResource(Res.string.reset)) {
                    // 对照原版 neutralButton: 恢复默认值但不关闭对话框
                    navItems.clear()
                    navItems.addAll(defaultNavItems.map { it.copy(enabled = true) })
                    height.intValue = AppConfigConstants.BOTTOM_BAR_HEIGHT_DEFAULT
                    iconSize.intValue = AppConfigConstants.BOTTOM_BAR_ICON_DEFAULT
                    labelMode.intValue = AppConfigConstants.BOTTOM_BAR_LABEL_DEFAULT
                }
                Spacer(Modifier.width(8.dp))
                AppTextButton(text = stringResource(Res.string.cancel), onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                AppTextButton(text = stringResource(Res.string.ok)) {
                    onDismiss()
                    val newShowHome = navItems.find { it.tag == BottomNavTag.HOME }?.enabled ?: true
                    val newShowDiscovery =
                        navItems.find { it.tag == BottomNavTag.DISCOVERY }?.enabled ?: true
                    val newOrder = navItems.joinToString(",") { it.tag }
                    var changed = appConfig.showHome != newShowHome
                        || appConfig.showDiscovery != newShowDiscovery
                        || appConfig.bottomNavItemOrder != newOrder
                    prefs.putBoolean(PreferKey.showHome, newShowHome)
                    prefs.putBoolean(PreferKey.showDiscovery, newShowDiscovery)
                    prefs.putString(PreferKey.bottomNavItemOrder, newOrder)
                    if (appConfig.bottomBarHeight != height.intValue) {
                        prefs.putInt(PreferKey.bottomBarHeight, height.intValue)
                        changed = true
                    }
                    if (appConfig.bottomBarIconSize != iconSize.intValue) {
                        prefs.putInt(PreferKey.bottomBarIconSize, iconSize.intValue)
                        changed = true
                    }
                    if (appConfig.bottomBarLabelMode != labelMode.intValue) {
                        prefs.putInt(PreferKey.bottomBarLabelMode, labelMode.intValue)
                        changed = true
                    }
                    // 对照原版: 有变更才 recreateActivities()
                    if (changed) eventBus.emitRecreate()
                }
            }
            }
        }
    }
}

/** 底栏配置条目 (对照 app 端 BottomNavConfigItem, 书架/我的不可隐藏) */
private data class BottomNavConfigItem(
    val tag: String,
    val nameRes: org.jetbrains.compose.resources.StringResource,
    val enabled: Boolean,
) {
    val locked get() = tag == BottomNavTag.BOOKSHELF || tag == BottomNavTag.MY
}

/** 对照 MainNavItem.iconKey: 启用取实心, 禁用取空心 */
private fun bottomNavIconKey(tag: String, enabled: Boolean): String = when (tag) {
    BottomNavTag.HOME -> if (enabled) "ic_bottom_home_s" else "ic_bottom_home_e"
    BottomNavTag.BOOKSHELF -> if (enabled) "ic_bottom_books_s" else "ic_bottom_books_e"
    BottomNavTag.DISCOVERY -> if (enabled) "ic_bottom_explore_s" else "ic_bottom_explore_e"
    else -> if (enabled) "ic_bottom_person_s" else "ic_bottom_person_e"
}

/** commonMain 无 java.util.Collections, 手写交换 (SnapshotStateList 支持索引写) */
private fun <T> swapItems(list: SnapshotStateList<T>, a: Int, b: Int) {
    val tmp = list[a]
    list[a] = list[b]
    list[b] = tmp
}

/** 标签 + 下拉单行 (对照原版 AppCompatSpinner 行) */
@Composable
private fun ConfigDropdownRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.primaryText, modifier = Modifier.weight(1f))
        Box {
            Row(
                Modifier
                    .clickable { expanded = true }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    options.getOrElse(selectedIndex) { "" },
                    color = colors.primaryText,
                    fontSize = 14.sp,
                )
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    tint = colors.secondaryText,
                )
            }
            AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { i, item ->
                    DropdownMenuItem(onClick = { expanded = false; onSelect(i) }) {
                        Text(item, color = colors.primaryText)
                    }
                }
            }
        }
    }
}

/** 标签 + 开关行 (对照原版 SwitchCompat 行) */
@Composable
private fun ConfigSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AppTheme.colors.primaryText, modifier = Modifier.weight(1f))
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
