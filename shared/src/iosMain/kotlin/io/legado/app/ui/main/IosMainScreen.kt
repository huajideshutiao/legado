package io.legado.app.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.legado.app.constant.BottomNavTag
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * iOS 端主界面容器入口 (包装 shared/sharedUiMain 的 [MainScreen])。
 *
 * 阻塞点: MainScreen 是 4 tab 容器 (HOME/BOOKSHELF/DISCOVERY/MY),
 * 各 tab Composable 由调用方注入。iOS 端当前 IosNavHost 用扁平路由替代 tab 容器,
 * 本入口仅做 stub 让编译通过, 后续 KP6+ 若改用 tab 容器再接入实际 tab 内容。
 *
 * @param bookshelfTab 书架 tab composable (默认空, 由调用方注入)
 */
@Composable
fun IosMainScreen(
    bookshelfTab: @Composable () -> Unit = { },
) {
    // pageSelections: 桥接 pager 跳转指令 (iOS stub 用空 SharedFlow)
    val pageSelections = remember { MutableSharedFlow<Pair<Int, Boolean>>(extraBufferCapacity = 8) }
    // KP-iOS: 4 tab visibleTags, 对照 app 端 BottomNavTag 默认顺序
    val visibleTags = listOf(
        BottomNavTag.HOME,
        BottomNavTag.BOOKSHELF,
        BottomNavTag.DISCOVERY,
        BottomNavTag.MY,
    )

    MainScreen(
        visibleTags = visibleTags,
        initialPage = 0,
        pageSelections = pageSelections,
        currentPageSink = { },
        onSelectPage = { _, _ -> },
        onReselect = { },
        homeTab = {
            // TODO: 接入 IosHomeScreen, 当前 stub 占位
            Box(Modifier.fillMaxSize())
        },
        bookshelfTab = bookshelfTab,
        exploreTab = {
            // TODO: 接入 IosExploreScreen, 当前 stub 占位
            Box(Modifier.fillMaxSize())
        },
        myTab = {
            // TODO: 接入 IosMyConfigScreen, 当前 stub 占位
            Box(Modifier.fillMaxSize())
        },
        bottomBarIconSize = 24,
        bottomBarHeight = 56,
        bottomBarLabelMode = 1,
    )
}
