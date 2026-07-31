package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.data.entities.Book
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.rss.RssHelp
import io.legado.app.ui.book.rss.RssSourcesScreen
import io.legado.app.ui.book.rss.RssSourcesScreenModel
import io.legado.app.ui.book.rss.RssSourcesUiActions
import io.legado.app.ui.book.rss.RssSourcesUiEvent
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.sure_del_any
import org.jetbrains.compose.resources.stringResource

/**
 * RSS 源管理 shared 路由入口。
 *
 * 通过 [ScreenModelStore] 复用 [RssSourcesScreenModel], 订阅 [RssHelp.flowRssSources]
 * 推入状态, 渲染 [RssSourcesScreen]。
 *
 * 源管理:
 * - 点击源 → 跳 RssArticles
 * - 长按源 → 编辑 (跳 BookSourceEdit) / 删除 (RssHelp.removeFromBookshelf + 确认弹窗)
 * - 添加源 → 跳搜索 (RSS 源 = Book(type|=rss), 通过搜索发现并上架)
 */
@Composable
fun RssSourcesRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) { RssSourcesScreenModel() }
    val state by screenModel.state.collectAsState()

    // 订阅 RSS 源数据流, 变更时推入状态
    LaunchedEffect(Unit) {
        RssHelp.flowRssSources().collect { sources ->
            screenModel.dispatch(RssSourcesUiEvent.SourcesLoaded(sources))
        }
    }

    val scope = rememberCoroutineScope()
    // 待删除源确认弹窗状态
    val pendingDelete = remember { mutableStateOf<Book?>(null) }

    val actions = remember(navigator) {
        object : RssSourcesUiActions {
            override fun onBack() {
                navigator.pop()
            }

            // 添加 RSS 源走书架 Book 搜索流程
            override fun onAddSource() {
                navigator.push(AppRoute.Search())
            }

            // 点击源跳转文章列表 (sourceUrl = book.bookUrl, 即 feed URL)
            override fun onOpenSource(book: Book) {
                navigator.push(AppRoute.RssArticles(book.bookUrl))
            }

            // 编辑源: 跳 BookSourceEdit (book.origin = 书源 URL)
            override fun onEditSource(book: Book) {
                navigator.push(AppRoute.BookSourceEdit(book.origin))
            }

            // 删除源: 弹确认弹窗, 确认后走 RssHelp.removeFromBookshelf
            override fun onDeleteSource(book: Book) {
                pendingDelete.value = book
            }
        }
    }

    RssSourcesScreen(state = state, actions = actions)

    // 删除源确认弹窗 (对照 app 端 sureDelAlert)
    pendingDelete.value?.let { book ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete.value = null },
            title = stringResource(Res.string.delete),
            message = stringResource(Res.string.sure_del_any, book.name),
            okButton = AlertButton(stringResource(Res.string.ok)) {
                scope.launch {
                    withContext(IoDispatcher) {
                        RssHelp.removeFromBookshelf(book)
                    }
                }
                pendingDelete.value = null
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
        )
    }
}
