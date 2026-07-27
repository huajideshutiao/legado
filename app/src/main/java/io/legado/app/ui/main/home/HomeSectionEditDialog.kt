package io.legado.app.ui.main.home

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.help.HomeTabHelp
import io.legado.app.help.PinnedExploreHelp
import io.legado.app.help.source.exploreKinds
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class HomeSectionEditDialog() : BaseComposeDialogFragment() {

    private val tabTitle: String get() = arguments?.getString(ARG_TAB_TITLE).orEmpty()

    private var editing: HomeSection? = null
    private var pinnedExplores: List<PinnedExplore> = emptyList()

    private var selectedSource: BookSource? = null

    private var title by mutableStateOf("")
    private var sourceName by mutableStateOf<String?>(null)
    private var selectedExploreUrl by mutableStateOf<String?>(null)
    private var selectedExploreName by mutableStateOf<String?>(null)
    private var style by mutableStateOf(HomeSection.STYLE_COVER_ROW)
    private var coverVideo by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editing = arguments?.getString(ARG_SECTION_ID)?.let { id ->
            HomeTabHelp.getSections(tabTitle).firstOrNull { it.id == id }
        }
        loadData()
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = getString(
                    if (editing != null) R.string.home_edit_section else R.string.home_add_section
                ),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                AppOutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = stringResource(R.string.home_section_title),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 结果显示区（对齐 tv_result：无选择时显示 hint 色占位）
                val resultText = sourceName?.let { name ->
                    val content = selectedExploreName?.let { "$name · $it" } ?: name
                    getString(R.string.current_selection, content)
                }
                Text(
                    text = resultText ?: stringResource(R.string.home_select_source),
                    color = if (resultText != null) colors.primaryText else colors.secondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(vertical = 8.dp),
                )
                // 操作按钮区
                Row(Modifier.fillMaxWidth()) {
                    PickAction(R.string.home_pick_favorite) { pickFavorite() }
                    PickAction(R.string.home_select_source) { pickSource() }
                    PickAction(
                        R.string.home_select_category,
                        enabled = sourceName != null,
                    ) { pickCategory() }
                }
                Text(
                    text = stringResource(R.string.home_style),
                    color = colors.accent,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StyleOption(R.string.home_style_cover_row, HomeSection.STYLE_COVER_ROW)
                    StyleOption(R.string.home_style_rank_list, HomeSection.STYLE_RANK_LIST)
                    StyleOption(R.string.home_style_four_row, HomeSection.STYLE_FOUR_ROW)
                    StyleOption(R.string.home_style_infinite_grid, HomeSection.STYLE_INFINITE_GRID)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .toggleable(value = coverVideo, role = Role.Checkbox) { coverVideo = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppCheckbox(checked = coverVideo, onCheckedChange = null)
                    Text(
                        text = stringResource(R.string.home_cover_video),
                        color = colors.primaryText,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editing != null) {
                    AppTextButton(text = stringResource(R.string.delete)) { delete() }
                }
                Spacer(Modifier.weight(1f))
                AppTextButton(text = stringResource(R.string.cancel)) { dismiss() }
                AppTextButton(text = stringResource(R.string.ok)) { save() }
            }
        }
    }

    @Composable
    private fun PickAction(textRes: Int, enabled: Boolean = true, onClick: () -> Unit) {
        val colors = AppTheme.colors
        Row(
            Modifier
                .height(48.dp)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(textRes),
                color = if (enabled) colors.primaryText else colors.secondaryText.copy(alpha = 0.5f),
                fontSize = 14.sp,
            )
        }
    }

    @Composable
    private fun StyleOption(textRes: Int, value: Int) {
        val colors = AppTheme.colors
        Row(
            Modifier
                .clickable { style = value }
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppRadioButton(selected = style == value, onClick = null)
            Text(
                text = stringResource(textRes),
                color = colors.primaryText,
                fontSize = 14.sp,
            )
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            pinnedExplores = withContext(IO) { PinnedExploreHelp.getPinnedExplores() }
            editing?.let { section ->
                title = section.title
                selectedExploreUrl = section.exploreUrl
                selectedExploreName = section.exploreName
                // 对齐原 RadioGroup：未知值回落封面横排
                style = when (section.style) {
                    HomeSection.STYLE_RANK_LIST,
                    HomeSection.STYLE_FOUR_ROW,
                    HomeSection.STYLE_INFINITE_GRID -> section.style
                    else -> HomeSection.STYLE_COVER_ROW
                }
                coverVideo = section.coverVideo
                selectedSource = withContext(IO) {
                    appDb.bookSourceDao.getBookSource(section.sourceUrl)
                }
            }
            refreshSelectionUI()
        }
    }

    private fun refreshSelectionUI() {
        sourceName = selectedSource?.bookSourceName
        if (title.isBlank() && !selectedExploreName.isNullOrBlank()) {
            title = selectedExploreName.orEmpty()
        }
    }

    private fun pickSource() {
        lifecycleScope.launch {
            if (!isAdded) return@launch
            val parts = withContext(IO) {
                appDb.bookSourceDao.flowExplore(enabled = true).first()
            }
            if (parts.isEmpty()) {
                toastOnUi(R.string.explore_empty)
                return@launch
            }
            searchPick(R.string.home_select_source, parts, { it.bookSourceName }) { part ->
                lifecycleScope.launch {
                    if (!isAdded) return@launch
                    val source = withContext(IO) {
                        appDb.bookSourceDao.getBookSource(part.bookSourceUrl)
                    } ?: return@launch
                    selectedSource = source
                    selectedExploreUrl = null
                    selectedExploreName = null
                    refreshSelectionUI()
                }
            }
        }
    }

    private fun pickCategory() {
        val source = selectedSource ?: run {
            toastOnUi(R.string.home_select_source)
            return
        }
        lifecycleScope.launch {
            if (!isAdded) return@launch
            val kinds = withContext(IO) {
                runCatching { source.exploreKinds() }.getOrDefault(emptyList())
            }.filter { !it.url.isNullOrBlank() }
            if (kinds.isEmpty()) {
                toastOnUi(R.string.home_source_no_explore)
                return@launch
            }
            searchPick(R.string.home_select_category, kinds, { it.title }) { kind ->
                selectedExploreUrl = kind.url
                selectedExploreName = kind.title
                refreshSelectionUI()
            }
        }
    }

    private fun pickFavorite() {
        if (pinnedExplores.isEmpty()) {
            toastOnUi(R.string.home_no_favorite)
            return
        }
        searchPick(
            R.string.home_pick_favorite,
            pinnedExplores, { String.format("%s · %s", it.sourceName, it.categoryName) }
        ) { fav ->
            lifecycleScope.launch {
                if (!isAdded) return@launch
                val source = withContext(IO) {
                    appDb.bookSourceDao.getBookSource(fav.sourceUrl)
                }
                if (source == null) {
                    toastOnUi(R.string.home_source_no_explore)
                    return@launch
                }
                selectedSource = source
                selectedExploreUrl = fav.categoryUrl
                selectedExploreName = fav.categoryName
                refreshSelectionUI()
            }
        }
    }

    private fun <T> searchPick(
        titleResId: Int,
        items: List<T>,
        label: (T) -> String,
        onPick: (T) -> Unit
    ) {
        val ctx = requireContext()
        ctx.alert(titleResId) {
            customView {
                val query = androidx.compose.runtime.remember { mutableStateOf("") }
                val shown = items.filter { label(it).contains(query.value, ignoreCase = true) }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    AppOutlinedTextField(
                        value = query.value,
                        onValueChange = { query.value = it },
                        label = stringResource(R.string.search),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                    ) {
                        items(shown.size) { index ->
                            val item = shown[index]
                            Text(
                                text = label(item),
                                color = AppTheme.colors.primaryText,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPick(item)
                                        dialog.dismiss()
                                    }
                                    .padding(vertical = 14.dp),
                            )
                        }
                    }
                }
            }
            cancelButton()
        }
    }

    private fun save() {
        val title = title.trim()
        if (title.isBlank()) {
            toastOnUi(R.string.home_title_empty)
            return
        }
        val source = selectedSource ?: run {
            toastOnUi(R.string.home_select_source)
            return
        }
        val exploreUrl = selectedExploreUrl ?: run {
            toastOnUi(R.string.home_select_category)
            return
        }
        val old = editing
        val newSection = HomeSection(
            id = old?.id ?: UUID.randomUUID().toString(),
            title = title,
            sourceUrl = source.bookSourceUrl,
            sourceName = source.bookSourceName,
            exploreUrl = exploreUrl,
            exploreName = selectedExploreName ?: title,
            style = style,
            sortOrder = old?.sortOrder ?: HomeTabHelp.getSections(tabTitle).size,
            coverVideo = coverVideo
        )
        if (newSection.style == HomeSection.STYLE_INFINITE_GRID) {
            val existingInfinite = HomeTabHelp.getSections(tabTitle)
                .firstOrNull { it.style == HomeSection.STYLE_INFINITE_GRID }
            if (existingInfinite != null && existingInfinite.id != newSection.id) {
                toastOnUi(R.string.home_infinite_only_one)
                return
            }
        }
        val finalSection = if (newSection.style == HomeSection.STYLE_INFINITE_GRID) {
            newSection.copy(sortOrder = Int.MAX_VALUE - 1)
        } else newSection
        lifecycleScope.launch {
            withContext(IO) {
                if (old == null) HomeTabHelp.addSection(tabTitle, finalSection)
                else HomeTabHelp.updateSection(tabTitle, finalSection)
            }
            postEvent(
                EventBus.HOME_SECTION,
                HomeSectionEvent(
                    if (old == null) HomeSectionEvent.ADD else HomeSectionEvent.UPDATE,
                    tabTitle,
                    finalSection
                )
            )
            dismiss()
        }
    }

    private fun delete() {
        val section = editing ?: return
        lifecycleScope.launch {
            withContext(IO) {
                HomeTabHelp.removeSection(tabTitle, section.id)
            }
            postEvent(
                EventBus.HOME_SECTION,
                HomeSectionEvent(HomeSectionEvent.REMOVE, tabTitle, section)
            )
            dismiss()
        }
    }

    companion object {
        private const val ARG_TAB_TITLE = "tabTitle"
        private const val ARG_SECTION_ID = "sectionId"

        fun newInstance(tabTitle: String, section: HomeSection? = null) =
            HomeSectionEditDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAB_TITLE, tabTitle)
                    // 传主键到达端经 HomeTabHelp 重查
                    section?.let { putString(ARG_SECTION_ID, it.id) }
                }
            }
    }
}
