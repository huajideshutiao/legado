package io.legado.app.ui.route

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.legado.app.ui.book.audio.AudioPlayPlatformProviders
import io.legado.app.ui.book.audio.AudioPlayScreenModel
import io.legado.app.ui.book.audio.AudioPlayUiEvent
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef

/**
 * 音频播放页 shared 路由入口。
 *
 * 通过 [ScreenModelStore] 复用 [AudioPlayScreenModel]；播放状态和命令由 shared 托管，
 * 封面、模糊背景、歌词、定时/倍速弹层等平台渲染通过 AudioPlayPlatformProvider 注入。
 *
 * 对照 app 端 [io.legado.app.ui.book.audio.AudioPlayActivity]。
 */
@Composable
fun AudioPlayRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.AudioPlay
    val book = route.book.asBook()

    val screenModel = screenModelStore.getOrCreateTyped(entry) { AudioPlayScreenModel() }
    val state by screenModel.state.collectAsState()
    val platform = AudioPlayPlatformProviders.getOrNull()

    // 初始化标题 (对照 viewModel.initData 中 titleData.postValue(book.name) + applyBookmarkPosition)
    LaunchedEffect(book) {
        screenModel.dispatch(
            AudioPlayUiEvent.Init(book, route.chapterIndex, route.chapterPos)
        )
    }

    if (platform == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Audio platform is unavailable", color = Color.White)
        }
        return
    }

    platform.Content(
        state = state,
        onBack = { navigator.pop() },
        onOpenChangeSource = { navigator.push(AppRoute.ChangeSource(book.toRouteRef())) },
        onOpenToc = { navigator.push(AppRoute.Toc(book.toRouteRef())) },
        onOpenBookSourceEdit = { sourceUrl ->
            navigator.push(AppRoute.BookSourceEdit(sourceUrl))
        },
        onOpenReview = { navigator.push(AppRoute.ReviewPost(book.toRouteRef())) },
        onEvent = screenModel::dispatch,
    )
}
