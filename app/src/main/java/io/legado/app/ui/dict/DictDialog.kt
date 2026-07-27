package io.legado.app.ui.dict

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.ui.compose.component.AppScrollTabRow
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.toastOnUi

/**
 * 词典
 */
class DictDialog() : BaseComposeDialogFragment() {

    constructor(word: String) : this() {
        arguments = Bundle().apply {
            putString("word", word)
        }
    }

    private val viewModel by viewModels<DictViewModel>()

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        val word = arguments?.getString("word")
        if (word.isNullOrEmpty()) {
            toastOnUi(R.string.cannot_empty)
            dismiss()
            return
        }
        var dictRules by remember { mutableStateOf<List<DictRule>>(emptyList()) }
        var selected by remember { mutableStateOf(0) }
        var loading by remember { mutableStateOf(false) }
        var html by remember { mutableStateOf("") }

        fun query(index: Int) {
            val rule = dictRules.getOrNull(index) ?: return
            loading = true
            viewModel.dict(rule, word) {
                loading = false
                html = it
            }
        }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            viewModel.initData { rules ->
                dictRules = rules
                if (rules.isNotEmpty()) query(0)
            }
        }

        Column(Modifier.fillMaxWidth()) {
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
                        containerColor = colors.bottomBackground,
                        indicator = { positions ->
                            TabRowDefaults.SecondaryIndicator(
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
                        text = AnnotatedString.fromHtml(html),
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
}
