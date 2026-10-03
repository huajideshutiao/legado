package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.KeywordHighlight
import io.legado.app.ui.book.keyword.KeywordHighlightEditDialog
import io.legado.app.ui.book.keyword.KeywordHighlightScreen
import io.legado.app.ui.book.keyword.KeywordHighlightScreenModel
import io.legado.app.ui.book.keyword.KeywordHighlightUiActions
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * AppRoute.KeywordHighlight 路由下沉入口: 桥接 [KeywordHighlightScreenModel] 与
 * [KeywordHighlightScreen]。编辑走本路由内联对话框 (BookmarkDialog 模式), 规则量级小,
 * 不经 AppOverlay 注册表; 落库后列表与阅读层回显同经 DAO flow 自动回推。
 */
@Composable
fun KeywordHighlightRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        KeywordHighlightScreenModel()
    }
    val state by screenModel.state.collectAsState()
    // null = 关闭; id=0 表示新增 (对照 BookmarkRoute.editingBookmark)
    var editingRule by remember { mutableStateOf<KeywordHighlight?>(null) }

    val actions = remember(screenModel, navigator) {
        object : KeywordHighlightUiActions {
            override fun onBack() {
                navigator.pop()
            }

            override fun onAddRule() {
                editingRule = KeywordHighlight()
            }

            override fun onEditRule(rule: KeywordHighlight) {
                editingRule = rule
            }

            override fun onToggleEnabled(rule: KeywordHighlight, enabled: Boolean) {
                screenModel.toggleEnabled(rule, enabled)
            }

            override fun onDeleteRule(rule: KeywordHighlight) {
                screenModel.delete(rule)
            }
        }
    }

    KeywordHighlightScreen(state = state, actions = actions)

    editingRule?.let { rule ->
        KeywordHighlightEditDialog(
            rule = rule,
            showDelete = rule.id != 0L,
            onConfirm = {
                screenModel.save(it)
                editingRule = null
            },
            onDismiss = { editingRule = null },
            onDelete = {
                screenModel.delete(rule)
                editingRule = null
            },
        )
    }

    // 系统返回/ESC 走与标题栏返回同一条路径 (对照 SourceFilterRuleRoute)
    val backStack by navigator.backStack.collectAsState()
    val isTopEntry = backStack.lastOrNull()?.id == entry.id
    AppBackHandler(enabled = isTopEntry, onBack = { actions.onBack() })
}
