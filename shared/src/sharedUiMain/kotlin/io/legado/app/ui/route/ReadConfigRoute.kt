package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import io.legado.app.ui.book.read.config.ReadConfigScreen
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * 阅读配置总入口 shared 路由。
 *
 * 与 [MoreConfigRoute] / [PaddingConfigRoute] / [TipConfigRoute] / [BgTextConfigRoute] / [ReadStyleRoute] /
 * [ReadAloudConfigRoute] 区别: 本路由是阅读配置的汇总页 (列表 + 跳转子项), 子项已分别下沉。
 * 不需要 ScreenModel, 直接渲染 [ReadConfigScreen], 点击子项 push 对应 AppRoute。
 */
@Composable
fun ReadConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    ReadConfigScreen(
        onBack = { navigator.pop() },
        onReadStyle = { navigator.push(AppRoute.ReadStyle) },
        onBgTextConfig = { navigator.push(AppRoute.BgTextConfig) },
        onPaddingConfig = { navigator.push(AppRoute.PaddingConfig) },
        onTipConfig = { navigator.push(AppRoute.TipConfig) },
        onMoreConfig = { navigator.push(AppRoute.MoreConfig) },
        onReadAloudConfig = { navigator.push(AppRoute.ReadAloudConfig) },
    )
}
