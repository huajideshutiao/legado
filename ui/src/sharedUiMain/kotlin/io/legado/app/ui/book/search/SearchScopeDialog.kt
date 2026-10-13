package io.legado.app.ui.book.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDialogScaffold
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.RadioChip
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.all_source
import legado.ui.generated.resources.book_source
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.group
import legado.ui.generated.resources.close
import legado.ui.generated.resources.ic_baseline_close
import legado.ui.generated.resources.ic_search
import legado.ui.generated.resources.ok
import legado.ui.generated.resources.screen
import legado.ui.generated.resources.search
import legado.ui.generated.resources.search_scope
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 搜索范围选择对话框。
 *
 * 结构、尺寸和交互对齐 Android 原版：90% 窗口宽度且不超过 800dp、80% 窗口高度，
 * 标题栏下方保留“分组/书源”双模式，书源模式提供标题栏筛选入口。
 *
 * @param groups 分组列表，null=自行读库（对照 app 端 allEnabledGroups）
 * @param sources 书源列表，null=自行读库并按筛选词走 flowSearch（对照 app 端 flowAll/flowSearch）
 */
@Composable
fun SearchScopeDialog(
    groups: List<String>? = null,
    sources: List<BookSourcePart>? = null,
    onConfirm: (SearchScope) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    // 原版每次打开都默认分组模式，选择状态不从已保存范围预填。
    var groupMode by remember { mutableStateOf(true) }
    var screenText by remember { mutableStateOf("") }
    var showScreen by remember { mutableStateOf(false) }
    var selectedGroups by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedSource by remember { mutableStateOf<BookSourcePart?>(null) }
    var dbGroups by remember { mutableStateOf<List<String>>(emptyList()) }
    var dbSources by remember { mutableStateOf<List<BookSourcePart>>(emptyList()) }
    if (groups == null) {
        LaunchedEffect(Unit) {
            dbGroups = withContext(IoDispatcher) {
                AppDbProviders.get().bookSourceDao.allEnabledGroups()
            }
        }
    }
    // 自行读库时筛选走 DAO（覆盖名称/分组/地址/注释），与 app 端 upBookSource 一致
    if (sources == null) {
        LaunchedEffect(screenText) {
            val dao = AppDbProviders.get().bookSourceDao
            when {
                screenText.isEmpty() -> dao.flowAll()
                else -> dao.flowSearch(screenText)
            }.catch {
                AppLog.put("多分组/书源界面更新书源出错", it)
            }.flowOn(IoDispatcher).conflate().collect { data ->
                dbSources = data
            }
        }
    }
    // 调用方注入列表时无法走 DAO 查询，退化为本地筛选
    val injectedSources = remember(sources, screenText) {
        val key = screenText.trim()
        val list = sources.orEmpty()
        if (key.isEmpty()) list else list.filter { source ->
            source.bookSourceName.contains(key, ignoreCase = true) ||
                source.bookSourceUrl.contains(key, ignoreCase = true) ||
                source.bookSourceGroup.orEmpty().contains(key, ignoreCase = true)
        }
    }
    val visibleGroups = groups ?: dbGroups
    val visibleSources = if (sources == null) dbSources else injectedSources
    // 书源模式筛选框聚焦 (进入筛选态即请求焦点)
    val focusRequester = remember { FocusRequester() }
    // 退出筛选 (对照原版 SearchView 收起: 关输入态并清空筛选词)
    val exitScreen: () -> Unit = {
        showScreen = false
        screenText = ""
    }

    AppDialogScaffold(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.search_scope),
        // 筛选态: 输入框顶掉标题文本, 与返回箭头同行 (对照原版标题栏 SearchView 展开)
        titleContent = if (!groupMode && showScreen) {
            {
                AppSearchField(
                    value = screenText,
                    onValueChange = { screenText = it },
                    hint = stringResource(Res.string.screen),
                    modifier = Modifier.weight(1f),
                    textFieldModifier = Modifier.focusRequester(focusRequester),
                )
                // 退出搜索态的大叉常显 (有文本时输入框自带的清除叉同时可见)
                IconButton(onClick = exitScreen) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_baseline_close),
                        contentDescription = stringResource(Res.string.close),
                        tint = colors.primaryText,
                    )
                }
                LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
            }
        } else null,
        // 返回箭头: 始终关闭对话框 (退出搜索态由右侧大叉承担)
        onBack = onDismiss,
        titleActions = {
            // 切换书源模式时的筛选入口 (对照原版标题栏 menu 搜索项)
            if (!groupMode && !showScreen) {
                IconButton(onClick = { showScreen = true }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_search),
                        contentDescription = stringResource(Res.string.search),
                        tint = colors.primaryText,
                    )
                }
            }
        },
        bottomBar = {
            AppTextButton(text = stringResource(Res.string.all_source)) {
                onConfirm(SearchScope(""))
            }
            Spacer(Modifier.weight(1f))
            AppTextButton(
                text = stringResource(Res.string.cancel),
                color = colors.secondaryText,
                onClick = onDismiss,
            )
            AppTextButton(text = stringResource(Res.string.ok)) {
                val scope = if (groupMode) {
                    SearchScope(selectedGroups)
                } else {
                    selectedSource?.let(::SearchScope) ?: SearchScope("")
                }
                onConfirm(scope)
            }
        },
        contentPadding = PaddingValues(0.dp),
        // 全高型: 高度锁定 0.8 屏高
        fullHeight = true,
        properties = AppDialogSizes.properties(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DesignTokens.spacingDefault),
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacingDefault),
        ) {
            RadioChip(
                text = stringResource(Res.string.group),
                checked = groupMode,
                modifier = Modifier.weight(1f),
            ) {
                groupMode = true
                exitScreen()
            }
            RadioChip(
                text = stringResource(Res.string.book_source),
                checked = !groupMode,
                modifier = Modifier.weight(1f),
            ) { groupMode = false }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (groupMode) {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(visibleGroups, key = { it }) { group ->
                        val checked = group in selectedGroups
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                ) { isChecked ->
                                    selectedGroups = if (isChecked) {
                                        selectedGroups + group
                                    } else {
                                        selectedGroups - group
                                    }
                                }
                                .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingDefault),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppCheckbox(checked = checked, onCheckedChange = null)
                            Text(
                                text = group,
                                color = colors.primaryText,
                                modifier = Modifier.padding(start = DesignTokens.spacingDefault),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(visibleSources, key = { it.bookSourceUrl }) { source ->
                        val selected =
                            selectedSource?.bookSourceUrl == source.bookSourceUrl
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                ) { selectedSource = source }
                                .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingDefault),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppRadioButton(selected = selected, onClick = null)
                            Text(
                                text = source.bookSourceName,
                                color = colors.primaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = DesignTokens.spacingDefault),
                            )
                        }
                    }
                }
            }
        }
    }
}
