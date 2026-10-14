package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.legado.app.ui.compose.theme.AppTheme
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.close
import legado.ui.generated.resources.ic_baseline_close
import legado.ui.generated.resources.ic_search
import legado.ui.generated.resources.search
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 对话框顶栏内的搜索态：平时顶栏右侧是放大镜, 点开后输入框顶掉标题文本、与返回箭头同行,
 * 右侧大叉退出并清空关键词。
 *
 * 换源选择、搜索范围、组件编辑的选择弹窗的搜索入口都是这一套行为, 状态机收在本类里,
 * 由 [AppPickDialog] 统筹: 关键词留在调用方 ([query] 双向) 用于过滤或读库查询,
 * 搜索态与焦点是纯顶栏表现, 调用方无需处理。
 */
class AppDialogSearchState internal constructor() {

    /** 是否处于搜索态 */
    var active by mutableStateOf(false)
        private set

    /** 关键词; 退出搜索态时清空 (否则退出后列表仍被关键词过滤) */
    var query by mutableStateOf("")
        private set

    /** 进入搜索态 (顶栏放大镜) */
    fun enter() {
        active = true
    }

    /** 退出搜索态并清空关键词 */
    fun exit() {
        active = false
        query = ""
    }

    /** 搜索框输入 (与属性 setter 区分, 避开 JVM 签名冲突) */
    fun onQueryChange(value: String) {
        query = value
    }
}

/** 记住一份搜索态并暴露给 [AppPickDialog] (状态随对话框存亡) */
@Composable
fun rememberAppDialogSearchState(): AppDialogSearchState = remember { AppDialogSearchState() }

/**
 * 顶栏带搜索入口的列表选择对话框 (壳与尺寸同 [AppDialogScaffold] 全高型)。
 *
 * 顶栏的搜索态、放大镜入口、退出大叉与聚焦由本组件统一, 调用方只需给列表内容:
 * 未搜索时列表用全量数据, 进入搜索态后用 [searchState] 的关键词自行过滤或查询。
 * 标题文本只在非搜索态显示, 搜索态由输入框顶掉 (与原版标题栏内 SearchView 展开一致)。
 *
 * @param searchHint    搜索框占位文案
 * @param searchEnabled  false = 顶栏不排搜索入口 (如只在子模式下才可搜索的弹窗)
 * @param bottomBar     底栏按钮槽; null = 不排底栏
 * @param titleActions  顶栏右侧额外动作 (溢出菜单等); 搜索态下连同放大镜一并隐藏
 *                      (搜索态只留退出大叉, 由本组件给出)
 */
@Composable
fun AppPickDialog(
    onDismissRequest: () -> Unit,
    title: String,
    searchState: AppDialogSearchState,
    searchHint: String,
    modifier: Modifier = Modifier,
    searchEnabled: Boolean = true,
    bottomBar: (@Composable RowScope.() -> Unit)? = null,
    titleActions: @Composable RowScope.() -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    properties: DialogProperties = AppDialogSizes.properties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    val focusRequester = remember { FocusRequester() }
    AppDialogScaffold(
        onDismissRequest = onDismissRequest,
        title = title,
        modifier = modifier,
        // 搜索态: 输入框顶掉标题文本, 与返回箭头同行
        titleContent = if (searchState.active) {
            {
                AppSearchField(
                    value = searchState.query,
                    onValueChange = searchState::onQueryChange,
                    hint = searchHint,
                    modifier = Modifier.weight(1f),
                    textFieldModifier = Modifier.focusRequester(focusRequester),
                )
                LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
            }
        } else null,
        // 返回箭头: 关闭对话框 (退出搜索态由右侧大叉承担)
        onBack = onDismissRequest,
        titleActions = {
            when {
                searchState.active -> {
                    IconButton(onClick = searchState::exit) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_baseline_close),
                            contentDescription = stringResource(Res.string.close),
                            tint = colors.primaryText,
                        )
                    }
                }

                searchEnabled -> {
                    IconButton(onClick = searchState::enter) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_search),
                            contentDescription = stringResource(Res.string.search),
                            tint = colors.primaryText,
                        )
                    }
                    titleActions()
                }

                else -> titleActions()
            }
        },
        bottomBar = bottomBar,
        contentPadding = contentPadding,
        fullHeight = true,
        properties = properties,
        content = content,
    )
}
