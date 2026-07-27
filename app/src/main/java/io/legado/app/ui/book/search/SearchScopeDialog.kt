package io.legado.app.ui.book.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.RadioChip
import io.legado.app.ui.compose.theme.AppColors
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class SearchScopeDialog : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    val callback: Callback get() = parentFragment as? Callback ?: activity as Callback

    // scope tab：true=分组 / false=书源（对齐原 rb_group 默认选中）
    private var groupMode by mutableStateOf(true)
    private var screenText by mutableStateOf("")
    private var showScreen by mutableStateOf(false)
    private var groups by mutableStateOf<List<String>>(emptyList())
    private var screenSources by mutableStateOf<List<BookSourcePart>>(emptyList())
    private var selectGroups by mutableStateOf<List<String>>(emptyList())
    private var selectSource by mutableStateOf<BookSourcePart?>(null)

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        LaunchedEffect(Unit) {
            groups = withContext(IO) {
                appDb.bookSourceDao.allEnabledGroups()
            }
        }
        LaunchedEffect(screenText) {
            when {
                screenText.isEmpty() -> appDb.bookSourceDao.flowAll()
                else -> appDb.bookSourceDao.flowSearch(screenText)
            }.flowWithLifecycleAndDatabaseChange(
                lifecycle,
                table = AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("多分组/书源界面更新书源出错", it)
            }.flowOn(IO).conflate().collect { data ->
                screenSources = data
            }
        }
        Column(Modifier.fillMaxWidth()) {
            ScopeTitleBar(colors)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioChip(
                    text = stringResource(R.string.group),
                    checked = groupMode,
                    modifier = Modifier.weight(1f),
                ) { groupMode = true; showScreen = false; screenText = "" }
                RadioChip(
                    text = stringResource(R.string.book_source),
                    checked = !groupMode,
                    modifier = Modifier.weight(1f),
                ) { groupMode = false }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (groupMode) {
                    LazyColumn {
                        items(groups, key = { it }) { group ->
                            GroupItem(group)
                        }
                    }
                } else {
                    LazyColumn {
                        items(screenSources, key = { it.bookSourceUrl }) { source ->
                            SourceItem(source)
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(text = stringResource(R.string.all_source)) {
                    callback.onSearchScopeOk(SearchScope(""))
                    dismiss()
                }
                Spacer(Modifier.weight(1f))
                AppTextButton(
                    text = stringResource(R.string.cancel),
                    color = colors.secondaryText,
                ) { dismiss() }
                AppTextButton(text = stringResource(R.string.ok)) {
                    if (groupMode) {
                        // 对齐原实现：分组按勾选顺序输出
                        callback.onSearchScopeOk(SearchScope(selectGroups))
                    } else {
                        val selected = selectSource
                        if (selected != null) {
                            callback.onSearchScopeOk(SearchScope(selected))
                        } else {
                            callback.onSearchScopeOk(SearchScope(""))
                        }
                    }
                    dismiss()
                }
            }
        }
    }

    /**
     * 复刻原 dialog_title_bar + book_search_scope 菜单：SearchView 展开时占据标题位置。
     * 仅书源模式露出漏斗入口；展开后左侧返回箭头收起并清空筛选。
     */
    @Composable
    private fun ScopeTitleBar(colors: AppColors) {
        val focusRequester = remember { FocusRequester() }
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.bottomBackground)
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!groupMode && showScreen) {
                IconButton(onClick = { showScreen = false; screenText = "" }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = null,
                        tint = colors.primaryText,
                    )
                }
                AppSearchField(
                    value = screenText,
                    onValueChange = { screenText = it },
                    hint = stringResource(R.string.screen),
                    modifier = Modifier.weight(1f),
                    textFieldModifier = Modifier.focusRequester(focusRequester),
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            } else {
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.search_scope),
                    color = colors.primaryText,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 原 menu_screen：仅书源模式可见的筛选入口
                if (!groupMode) {
                    IconButton(onClick = { showScreen = true }) {
                        Icon(
                            painter = painterResource(R.drawable.outline_filter_alt_24),
                            contentDescription = stringResource(R.string.screen),
                            tint = colors.primaryText,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun GroupItem(group: String) {
        val colors = AppTheme.colors
        val checked = group in selectGroups
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox) { isChecked ->
                    selectGroups = if (isChecked) selectGroups + group else selectGroups - group
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppCheckbox(checked = checked, onCheckedChange = null)
            Text(
                text = group,
                color = colors.primaryText,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    @Composable
    private fun SourceItem(source: BookSourcePart) {
        val colors = AppTheme.colors
        val selected = selectSource?.bookSourceUrl == source.bookSourceUrl
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton) {
                    selectSource = source
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppRadioButton(selected = selected, onClick = null)
            Text(
                text = source.bookSourceName,
                color = colors.primaryText,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    interface Callback {

        /**
         * 搜索范围确认
         */
        fun onSearchScopeOk(searchScope: SearchScope)

    }

}
