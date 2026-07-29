package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.model.LocalReadBookProvider
import io.legado.app.ui.book.read.EffectiveReplacesScreen
import io.legado.app.ui.book.read.EffectiveReplacesScreenModel
import io.legado.app.ui.book.read.EffectiveReplacesUiEvent
import io.legado.app.ui.book.read.config.ChineseConverterSelectorDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * AppRoute.EffectiveReplaces 路由下沉入口。
 *
 * effectiveReplaceRules 来源于 ReadBook 共享状态（[io.legado.app.model.ReadBookShared.curTextChapter]
 * 的 TextChapterShared.effectiveReplaceRules），通过构造函数注入 provider 由 ScreenModel 内部读取。
 * 新增/编辑规则跳 [AppRoute.ReplaceEdit]，管理全部跳 [AppRoute.ReplaceRule]，
 * 繁简转换占位项（item === screenModel.chineseConvert）弹出 [ChineseConverterSelectorDialog]。
 */
@Composable
fun EffectiveReplacesRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val readBook = LocalReadBookProvider.current.readBook
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        EffectiveReplacesScreenModel(
            getEffectiveReplaceRules = {
                readBook.curTextChapter.value?.effectiveReplaceRules ?: emptyList()
            },
        )
    }
    LaunchedEffect(Unit) {
        screenModel.dispatch(EffectiveReplacesUiEvent.Init)
    }
    val state by screenModel.state.collectAsState()

    var showChineseConverter by remember { mutableStateOf(false) }

    EffectiveReplacesScreen(
        items = state.items,
        onAddRule = {
            navigator.push(AppRoute.ReplaceEdit())
        },
        onItemClick = { rule ->
            if (rule === screenModel.chineseConvert) {
                showChineseConverter = true
            } else {
                navigator.push(AppRoute.ReplaceEdit(rule.id))
            }
        },
        onManageAll = {
            navigator.push(AppRoute.ReplaceRule)
        },
        onDismiss = { navigator.pop() },
    )

    // 繁简转换选择器（自包含：内部写 AppConfigProviders）
    if (showChineseConverter) {
        ChineseConverterSelectorDialog(
            currentType = AppConfigProviders.get().chineseConverterType,
            onChanged = {
                // chineseConverterType 变化后刷新 items（chineseConvert 占位项增减）
                screenModel.dispatch(EffectiveReplacesUiEvent.Init)
            },
            onDismiss = { showChineseConverter = false },
        )
    }
}
