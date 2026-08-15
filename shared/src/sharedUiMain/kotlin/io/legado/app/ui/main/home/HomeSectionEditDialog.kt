package io.legado.app.ui.main.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.help.PinnedExploreHelp
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.postEvent
import io.legado.app.utils.randomUUIDString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.current_selection
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.explore_empty
import legado.shared.generated.resources.home_add_section
import legado.shared.generated.resources.home_cover_video
import legado.shared.generated.resources.home_edit_section
import legado.shared.generated.resources.home_infinite_only_one
import legado.shared.generated.resources.home_no_favorite
import legado.shared.generated.resources.home_pick_favorite
import legado.shared.generated.resources.home_section_title
import legado.shared.generated.resources.home_select_category
import legado.shared.generated.resources.home_select_source
import legado.shared.generated.resources.home_source_no_explore
import legado.shared.generated.resources.home_style
import legado.shared.generated.resources.home_style_cover_row
import legado.shared.generated.resources.home_style_four_row
import legado.shared.generated.resources.home_style_infinite_grid
import legado.shared.generated.resources.home_style_rank_list
import legado.shared.generated.resources.home_title_empty
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.search
import org.jetbrains.compose.resources.stringResource

/**
 * 主页展示项新增/编辑对话框，下沉自 app 端同名 DialogFragment (dialog_home_section_edit.xml)。
 * 三级联动：收藏 → 直接落 源+分类；书源 → 清空分类；分类 → 需先选书源。
 */
@Composable
fun HomeSectionEditDialog(
    tabTitle: String,
    editing: HomeSection? = null,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()

    var title by remember(editing) { mutableStateOf(editing?.title.orEmpty()) }
    var style by remember(editing) {
        mutableStateOf(editing?.style ?: HomeSection.STYLE_COVER_ROW)
    }
    var coverVideo by remember(editing) { mutableStateOf(editing?.coverVideo == true) }
    var selectedSource by remember(editing) { mutableStateOf<BookSource?>(null) }
    var selectedExploreUrl by remember(editing) { mutableStateOf(editing?.exploreUrl) }
    var selectedExploreName by remember(editing) { mutableStateOf(editing?.exploreName) }
    var pinnedExplores by remember { mutableStateOf<List<PinnedExplore>>(emptyList()) }

    // 三个选择器各自的候选项 (非空即展示对应选择弹窗)
    var favoriteOptions by remember { mutableStateOf<List<PinnedExplore>?>(null) }
    var sourceOptions by remember { mutableStateOf<List<BookSourceLabel>?>(null) }
    var categoryOptions by remember { mutableStateOf<List<CategoryLabel>?>(null) }

    val titleEmptyText = stringResource(Res.string.home_title_empty)
    val selectSourceText = stringResource(Res.string.home_select_source)
    val selectCategoryText = stringResource(Res.string.home_select_category)
    val pickFavoriteText = stringResource(Res.string.home_pick_favorite)
    val noFavoriteText = stringResource(Res.string.home_no_favorite)
    val noExploreText = stringResource(Res.string.home_source_no_explore)
    val exploreEmptyText = stringResource(Res.string.explore_empty)
    val infiniteOnlyOneText = stringResource(Res.string.home_infinite_only_one)

    // 对照原版 loadData: 拉收藏列表 + 按 sourceUrl 回填已选书源
    LaunchedEffect(editing) {
        pinnedExplores = withContext(IoDispatcher) { PinnedExploreHelp.getPinnedExplores() }
        editing?.let { section ->
            selectedSource = withContext(IoDispatcher) {
                AppDbProviders.get().bookSourceDao.getBookSource(section.sourceUrl)
            }
        }
    }

    // 对照 refreshSelectionUI: 选到分类而标题仍空时用分类名补全
    LaunchedEffect(selectedExploreName) {
        if (title.isBlank() && !selectedExploreName.isNullOrBlank()) {
            title = selectedExploreName.orEmpty()
        }
    }

    val sourceName = selectedSource?.bookSourceName
    val resultText = when {
        sourceName == null -> ""
        selectedExploreName == null -> stringResource(Res.string.current_selection, sourceName)
        else -> stringResource(
            Res.string.current_selection,
            "$sourceName · ${selectedExploreName.orEmpty()}",
        )
    }

    /** 对照原版 save(): 标题/书源/分类三项校验 + 瀑布网格唯一性校验 */
    fun save() {
        val trimmed = title.trim()
        if (trimmed.isBlank()) {
            Toasters.get().toast(titleEmptyText)
            return
        }
        val source = selectedSource ?: run {
            Toasters.get().toast(selectSourceText)
            return
        }
        val exploreUrl = selectedExploreUrl ?: run {
            Toasters.get().toast(selectCategoryText)
            return
        }
        val newSection = HomeSection(
            id = editing?.id ?: randomUUIDString(),
            title = trimmed,
            sourceUrl = source.bookSourceUrl,
            sourceName = source.bookSourceName,
            exploreUrl = exploreUrl,
            exploreName = selectedExploreName ?: trimmed,
            style = style,
            sortOrder = editing?.sortOrder ?: HomeTabHelpShared.getSections(tabTitle).size,
            coverVideo = coverVideo,
        )
        if (newSection.style == HomeSection.STYLE_INFINITE_GRID) {
            val existingInfinite = HomeTabHelpShared.getSections(tabTitle)
                .firstOrNull { it.style == HomeSection.STYLE_INFINITE_GRID }
            if (existingInfinite != null && existingInfinite.id != newSection.id) {
                Toasters.get().toast(infiniteOnlyOneText)
                return
            }
        }
        // 瀑布网格强制排到最后
        val finalSection = if (newSection.style == HomeSection.STYLE_INFINITE_GRID) {
            newSection.copy(sortOrder = Int.MAX_VALUE - 1)
        } else {
            newSection
        }
        scope.launch {
            withContext(IoDispatcher) {
                if (editing == null) HomeTabHelpShared.addSection(tabTitle, finalSection)
                else HomeTabHelpShared.updateSection(tabTitle, finalSection)
            }
            postEvent(
                EventBus.HOME_SECTION,
                HomeSectionEvent(
                    if (editing == null) HomeSectionEvent.ADD else HomeSectionEvent.UPDATE,
                    tabTitle,
                    finalSection,
                )
            )
            onDismiss()
        }
    }

    fun delete() {
        val section = editing ?: return
        scope.launch {
            withContext(IoDispatcher) {
                HomeTabHelpShared.removeSection(tabTitle, section.id)
            }
            postEvent(
                EventBus.HOME_SECTION,
                HomeSectionEvent(HomeSectionEvent.REMOVE, tabTitle, section)
            )
            onDismiss()
        }
    }

    /** 对照 pickSource: 拉启用发现的书源分片列表, 空则 toast */
    fun pickSource() {
        scope.launch {
            val parts = withContext(IoDispatcher) {
                AppDbProviders.get().bookSourceDao.flowExplore(enabled = true).first()
            }
            if (parts.isEmpty()) {
                Toasters.get().toast(exploreEmptyText)
                return@launch
            }
            sourceOptions = parts.map { BookSourceLabel(it.bookSourceUrl, it.bookSourceName) }
        }
    }

    /** 对照 pickCategory: 解析该书源的发现分类, 过滤掉无 url 的项 */
    fun pickCategory() {
        val source = selectedSource ?: run {
            Toasters.get().toast(selectSourceText)
            return
        }
        scope.launch {
            val kinds = withContext(IoDispatcher) {
                runCatching { source.exploreKinds() }.getOrDefault(emptyList())
            }.filter { !it.url.isNullOrBlank() }
            if (kinds.isEmpty()) {
                Toasters.get().toast(noExploreText)
                return@launch
            }
            categoryOptions = kinds.map { CategoryLabel(it.title, it.url.orEmpty()) }
        }
    }

    fun pickFavorite() {
        if (pinnedExplores.isEmpty()) {
            Toasters.get().toast(noFavoriteText)
            return
        }
        favoriteOptions = pinnedExplores
    }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        // 不能套 fillMaxSize: 撑满窗口会让整窗都算"框内", 点外部永远关不掉; 居中由 RootMeasurePolicy 负责。
        // 自适应高度, 上限 0.8 屏高
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = stringResource(
                        if (editing != null) Res.string.home_edit_section
                        else Res.string.home_add_section
                    ),
                    onBack = onDismiss,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp),
                ) {
                    AppUnderlineTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = stringResource(Res.string.home_section_title),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 结果显示区 (对照 tv_result: 48dp 高, 未选时显示 hint)
                    Box(
                        Modifier.fillMaxWidth().height(DesignTokens.viewHeightXl),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = resultText.ifEmpty { selectSourceText },
                            color = if (resultText.isEmpty()) {
                                colors.secondaryText
                            } else {
                                colors.primaryText
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 操作按钮区 (对照三个 TextView: 收藏 / 书源 / 分类)
                    Row(Modifier.fillMaxWidth()) {
                        PickEntry(pickFavoriteText, true) { pickFavorite() }
                        PickEntry(selectSourceText, true) { pickSource() }
                        PickEntry(selectCategoryText, selectedSource != null) { pickCategory() }
                    }
                    Text(
                        text = stringResource(Res.string.home_style),
                        color = colors.accent,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StyleRadio(
                            stringResource(Res.string.home_style_cover_row),
                            style == HomeSection.STYLE_COVER_ROW,
                        ) { style = HomeSection.STYLE_COVER_ROW }
                        StyleRadio(
                            stringResource(Res.string.home_style_rank_list),
                            style == HomeSection.STYLE_RANK_LIST,
                        ) { style = HomeSection.STYLE_RANK_LIST }
                        StyleRadio(
                            stringResource(Res.string.home_style_four_row),
                            style == HomeSection.STYLE_FOUR_ROW,
                        ) { style = HomeSection.STYLE_FOUR_ROW }
                        StyleRadio(
                            stringResource(Res.string.home_style_infinite_grid),
                            style == HomeSection.STYLE_INFINITE_GRID,
                        ) { style = HomeSection.STYLE_INFINITE_GRID }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clickable { coverVideo = !coverVideo },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppCheckbox(checked = coverVideo, onCheckedChange = { coverVideo = it })
                        Text(
                            text = stringResource(Res.string.home_cover_video),
                            color = colors.primaryText,
                        )
                    }
                }
                // 底栏: 删除(编辑模式) 靠左, 取消/确定 靠右
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (editing != null) {
                        AppTextButton(text = stringResource(Res.string.delete)) { delete() }
                    }
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = stringResource(Res.string.cancel), onClick = onDismiss)
                    AppTextButton(text = stringResource(Res.string.ok)) { save() }
                }
            }
        }
    }

    // ─── 三个选择弹窗 (对照原版泛型 searchPick: 搜索框 + 360dp 列表) ─────────
    favoriteOptions?.let { options ->
        HomeSearchPickDialog(
            title = pickFavoriteText,
            items = options,
            label = { "${it.sourceName} · ${it.categoryName}" },
            onDismiss = { favoriteOptions = null },
        ) { fav ->
            favoriteOptions = null
            scope.launch {
                val source = withContext(IoDispatcher) {
                    AppDbProviders.get().bookSourceDao.getBookSource(fav.sourceUrl)
                }
                if (source == null) {
                    Toasters.get().toast(noExploreText)
                    return@launch
                }
                selectedSource = source
                selectedExploreUrl = fav.categoryUrl
                selectedExploreName = fav.categoryName
            }
        }
    }
    sourceOptions?.let { options ->
        HomeSearchPickDialog(
            title = selectSourceText,
            items = options,
            label = { it.name },
            onDismiss = { sourceOptions = null },
        ) { part ->
            sourceOptions = null
            scope.launch {
                val source = withContext(IoDispatcher) {
                    AppDbProviders.get().bookSourceDao.getBookSource(part.url)
                } ?: return@launch
                selectedSource = source
                // 换源后分类必须重选 (对照原版 pickSource 清空 exploreUrl/Name)
                selectedExploreUrl = null
                selectedExploreName = null
            }
        }
    }
    categoryOptions?.let { options ->
        HomeSearchPickDialog(
            title = selectCategoryText,
            items = options,
            label = { it.title },
            onDismiss = { categoryOptions = null },
        ) { kind ->
            categoryOptions = null
            selectedExploreUrl = kind.url
            selectedExploreName = kind.title
        }
    }
}

/** 书源选择项 (只需 url + 名称, 避免把 BookSourcePart 整行带进 UI 状态) */
private data class BookSourceLabel(val url: String, val name: String)

/** 分类选择项 (对照 ExploreKind 的 title + 非空 url) */
private data class CategoryLabel(val title: String, val url: String)

/** 操作入口文本 (对照 tv_pick_*: 48dp 高, 未选书源时"选择分类"置灰不可点) */
@Composable
private fun PickEntry(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .height(DesignTokens.viewHeightXl)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) colors.primaryText else colors.secondaryText,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/** 样式单选项 (对照 rg_style 内 RadioButton) */
@Composable
private fun StyleRadio(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppRadioButton(selected = selected, onClick = onClick)
        Text(text = text, color = AppTheme.colors.primaryText)
    }
}

/**
 * 通用搜索选择弹窗，下沉自原版私有泛型 `searchPick`：
 * 搜索框即时按 label 模糊过滤，列表 360dp 高，点击回调后关闭。
 */
@Composable
private fun <T> HomeSearchPickDialog(
    title: String,
    items: List<T>,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    val colors = AppTheme.colors
    var query by remember { mutableStateOf("") }
    val shown = remember(items, query) {
        if (query.isEmpty()) items else items.filter { label(it).contains(query, true) }
    }
    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        // 不能套 fillMaxSize: 撑满窗口会让整窗都算"框内", 点外部永远关不掉; 居中由 RootMeasurePolicy 负责。
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Text(
                    text = title,
                    color = colors.primaryText,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                AppSearchField(
                    value = query,
                    onValueChange = { query = it },
                    hint = stringResource(Res.string.search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                FastScrollLazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier
                        .fillMaxWidth()
                        // 对照原版 searchPick 的 ListView 固定 360dp 高
                        .heightIn(max = 360.dp)
                        .padding(top = 8.dp),
                ) {
                    items(shown) { item ->
                        Text(
                            text = label(item),
                            color = colors.primaryText,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(item) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AppTextButton(text = stringResource(Res.string.cancel), onClick = onDismiss)
                }
            }
        }
    }
}
