package io.legado.app.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * 外部关联 (DeepLink / 文件关联 / PROCESS_TEXT) shared 路由入口。
 *
 * 关联路由是过渡页: 不渲染业务 UI, 仅以透明 Box 占位维持路由栈。
 * 实际关联处理 (Intent 解析 / 文件导入 / 文本搜索 / 朗读) 由各平台入口的
 * LaunchRequest 在路由命中前后消费 Intent 并分发, 对应 app 端 AssociationActivity。
 *
 * 与 shared/sharedUiMain/ui/association/DeepLinkImportHost.kt 区别:
 * DeepLinkImportHost 是 Overlay (在 LegadoApp 顶层挂载, 处理 legado:// 导入),
 * 本路由仅作为外部 Intent 命中 Association action 时的过渡落地。
 */
@Composable
fun AssociationRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    // AppRoute.Association 为 data object, 无参数; 透明占位, 关联逻辑由平台入口处理
    Box {}
}
