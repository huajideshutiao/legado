package io.legado.desktop.ui.dict

import androidx.compose.foundation.layout.widthIn
import io.legado.desktop.ui.component.DialogSizes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.compose.component.AppScrollTabRow
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.dict.DictViewModelShared

/**
 * 桌面端"查词"对话框。
 *
 * 对照 app 端 [io.legado.app.ui.dict.DictDialog] (继承 BaseComposeDialogFragment),
 * 桌面端改为 Compose Dialog (androidx.compose.ui.window.Dialog + Surface), 复用
 * commonMain 下沉的 [DictViewModelShared] 业务核心 (initData / dict)。
 *
 * # 与 app 端差异
 * - app 端继承 BaseComposeDialogFragment, Dialog 窗口由 Fragment 提供;
 *   桌面端用 [Dialog] + [Surface] 自绘 (与 [io.legado.desktop.ui.reader.TextSelectionDialog] 一致)
 * - app 端用 viewModelScope (Android ViewModel); 桌面端用 [rememberCoroutineScope]
 *   注入 [DictViewModelShared] (对照 desktop DictRuleScreen.kt 的 Shared VM 注入模式)
 * - app 端 word 通过 Fragment arguments 传入; 桌面端通过函数参数直接传入
 * - app 端空 word 调 toastOnUi + dismiss; 桌面端由调用方 (ReaderScreen) 保证 word 非空, 不重复校验
 *
 * # 功能 (对照 app 端 DictDialog.Content)
 * - initData 加载启用的字典规则列表 → rules 非空时自动 query(0)
 * - 规则 ≤4 用 [TabRow], >4 用 [AppScrollTabRow] (复刻 app 端 setupTabLayoutMode)
 * - [SelectionContainer] + [verticalScroll] + [Text]([AnnotatedString.fromHtml]) 渲染查询结果
 *   (HTML 渲染, 链接可点击; SelectionContainer 支持复制结果文字)
 * - loading 时 [CircularProgressIndicator] 顶部居中
 *
 * # UI 样式
 * - 圆角 16dp (Arco Design arco_radius_lg, 与 TextSelectionDialog 对齐)
 * - 无 elevation (与 TextSelectionDialog 一致)
 * - 内容区 heightIn(max=480dp) 保证 verticalScroll 生效 (与 TextSelectionDialog 一致)
 *
 * @param word 待查单词
 * @param onDismiss 关闭回调
 */
@Composable
fun DictDialog(word: String, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    // 复用 commonMain 下沉的查词 VM, 注入 Compose 协程作用域
    // (对照 desktop DictRuleScreen.kt: rememberCoroutineScope() + Shared VM 注入)
    val viewModel = remember { DictViewModelShared(scope) }

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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp), // Arco Design arco_radius_lg = 16dp
            color = colors.background,
            modifier = Modifier.widthIn(max = DialogSizes.dialogMaxWidth()),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 标题栏: 标题为待查单词 (app 端无标题栏, 桌面端按 TextSelectionDialog 模式补齐)
                DialogTitleBar(
                    title = word,
                    onBack = onDismiss,
                )
                if (dictRules.isNotEmpty()) {
                    // 已启用词典 ≤4 用固定填充, >4 用可滚动 (复刻 app 端 setupTabLayoutMode)
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
                                text = { Text(rule.name) },
                            )
                        }
                    }
                    if (dictRules.size <= 4) {
                        TabRow(
                            selectedTabIndex = selected,
                            containerColor = colors.bottomBackground,
                            indicator = { positions ->
                                TabRowDefaults.SecondaryIndicator(
                                    Modifier.tabIndicatorOffset(positions[selected]),
                                    color = colors.accent,
                                )
                            },
                            tabs = tabs,
                        )
                    } else {
                        // M3 ScrollableTabRow 硬编码 90dp 最小 tab 宽, 改用 shared AppScrollTabRow (同 app 端)
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
                                text = { Text(rule.name) },
                            )
                        }
                    }
                }
                // 内容区: SelectionContainer 包 Text, 用户可复制查询结果; verticalScroll 滚动长结果
                // (与 TextSelectionDialog 一致加 heightIn(max=480dp) 保证 scroll 生效)
                Box(Modifier.fillMaxWidth()) {
                    SelectionContainer(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // 词典结果按 HTML 渲染, 链接可点击 (fromHtml 内建 LinkAnnotation)
                        Text(
                            text = stripHtml(html),
                            color = colors.secondaryText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        )
                    }
                    if (loading) {
                        // loading 指示器顶部居中 (与 app 端 DictDialog 一致: 60dp / accent / TopCenter)
                        CircularProgressIndicator(
                            color = colors.accent,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                                .size(60.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 简易 HTML 转纯文本 (desktop 端无 AnnotatedString.fromHtml, 替代 app 端 HTML 渲染)。
 *
 * 处理: br 标签转换行, 去除其余标签, 解码常见 HTML 实体。
 * 限制: 不渲染链接/样式 (与 app 端 fromHtml 的 LinkAnnotation 行为有差异)。
 */
private fun stripHtml(html: String): String {
    return html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)<p\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()
}
