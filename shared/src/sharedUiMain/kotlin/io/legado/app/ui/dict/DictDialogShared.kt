package io.legado.app.ui.dict

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.TabRowDefaults
import androidx.compose.material.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.toHtmlAnnotatedString
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.compose.component.AppScrollTabRow
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 词典查询对话框内容 (KMP 共享, app/iOS 复用)。
 *
 * # 背景
 *
 * 对照 app 端原 [io.legado.app.ui.dict.DictDialog] (继承 BaseComposeDialogFragment),
 * 把 UI Composable 下沉到 shared 供多端复用。业务核心已下沉 [DictViewModelShared] (commonMain),
 * 本 Composable 仅负责渲染: 规则 Tab + 查询结果 HTML。
 *
 * # 平台差异 (由调用方处理)
 *
 * - **Dialog 外壳**: app 端用 BaseComposeDialogFragment (Fragment 提供 Dialog 窗口);
 *   iOS 端用 androidx.compose.ui.window.Dialog + Surface (调用方包装);
 *   本 Composable 只渲染 Column 内容, 由调用方在外层包外壳。
 * - **ViewModel**: 调用方构造 [DictViewModelShared] (Android=viewModelScope /
 *   iOS=rememberCoroutineScope) 后传入。
 * - **word 校验**: app 端原版在 Content() 入口校验空 word (toastOnUi + dismiss),
 *   下沉后由调用方在调本 Composable 前完成校验 (本 Composable 假定 word 非空)。
 *
 * # 功能 (对照 app 端 DictDialog.Content)
 *
 * - initData 加载启用的字典规则列表 → rules 非空时自动 query(0)
 * - 规则 ≤4 用 [TabRow], >4 用 [AppScrollTabRow] (复刻 app 端 setupTabLayoutMode)
 * - [SelectionContainer] + [verticalScroll] + [Text]([AnnotatedString.fromHtml]) 渲染查询结果
 *   (HTML 渲染, 链接可点击; SelectionContainer 支持复制结果文字)
 * - loading 时 [CircularProgressIndicator] 顶部居中
 *
 * @param word 待查单词 (调用方保证非空)
 * @param viewModel 字典查询 VM (调用方构造并注入)
 * @param modifier 由调用方传入的 Modifier (默认 [Modifier])
 */
@Composable
fun DictDialogContent(
    word: String,
    viewModel: DictViewModelShared,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    var dictRules by remember { mutableStateOf<List<DictRule>>(emptyList()) }
    var selected by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var html by remember { mutableStateOf("") }

    // 查词: 取规则 → loading → vm.dict(rule, word) → 回调赋 html (与 app 端 query 完全一致)
    fun query(index: Int) {
        val rule = dictRules.getOrNull(index) ?: return
        loading = true
        viewModel.dict(rule, word) {
            loading = false
            html = it
        }
    }

    // 初始化: 加载启用的字典规则, 非空则自动查第一条 (与 app 端 LaunchedEffect 一致)
    LaunchedEffect(Unit) {
        viewModel.initData { rules ->
            dictRules = rules
            if (rules.isNotEmpty()) query(0)
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (dictRules.isNotEmpty()) {
            // 已启用词典 ≤4 用固定填充，>4 用可滚动（复刻 setupTabLayoutMode）
            val tabs: @Composable () -> Unit = {
                dictRules.forEachIndexed { index, rule ->
                    Tab(
                        selected = index == selected,
                        onClick = {
                            selected = index
                            query(index)
                        },
                        selectedContentColor = colors.accent,
                        unselectedContentColor = colors.primaryText,
                        text = { Text(rule.name) }
                    )
                }
            }
            if (dictRules.size <= 4) {
                TabRow(
                    selectedTabIndex = selected,
                    backgroundColor = colors.bottomBackground,
                    indicator = { positions ->
                        TabRowDefaults.Indicator(
                            Modifier.tabIndicatorOffset(positions[selected]),
                            color = colors.accent
                        )
                    },
                    tabs = tabs
                )
            } else {
                // M3 ScrollableTabRow 硬编码 90dp 最小 tab 宽，改自绘（同书架分组 tab）
                AppScrollTabRow(
                    tabCount = dictRules.size,
                    selectedIndex = selected,
                    indicatorColor = colors.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.bottomBackground),
                ) { index ->
                    val rule = dictRules[index]
                    Tab(
                        selected = index == selected,
                        onClick = {
                            selected = index
                            query(index)
                        },
                        selectedContentColor = colors.accent,
                        unselectedContentColor = colors.primaryText,
                        text = { Text(rule.name) }
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth()) {
            SelectionContainer(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 词典结果按 HTML 渲染，链接可点击(fromHtml 内建 LinkAnnotation)
                Text(
                    text = html.toHtmlAnnotatedString(),
                    color = colors.secondaryText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
            if (loading) {
                CircularProgressIndicator(
                    color = colors.accent,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .size(60.dp)
                )
            }
        }
    }
}
