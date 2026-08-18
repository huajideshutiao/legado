package io.legado.app.ui.browser

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.component.LocalSheetDismissRequest
import io.legado.app.ui.compose.component.sheetDragExclusion
import io.legado.app.ui.compose.platform.BackLayerHandler
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppRoute

/**
 * startBrowser(asBottomSheet=true) 半屏浏览器的形态壳。
 *
 * 浏览器本体一律走 [WebViewScreen] (与全屏路由同一段实现、同一个 [AppRoute.WebView] 参数包),
 * 本函数只补三件半屏特有的事:
 *
 * 1. **手势**: 内容区整块登记为 [sheetDragExclusion] 拖拽禁区。平台 WebView 是 interop 视图,
 *    不参与 Compose 手势竞争 —— Compose 侧一旦消费位移, 视图就会收到 ACTION_CANCEL 而彻底
 *    滚不动。登记后弹层在该区域完全不参与竞争, 竖直手势原样归 WebView, 下拉关闭只在顶栏生效。
 *    同时补主题底色, 弹层圆角内不露白 (WebView 组合/加载完成前)。
 * 2. **返回键**: 走覆盖物栈 [BackLayerHandler] (注册在 AppBottomSheetDialog 自身的拦截之后,
 *    栈顶优先); 兜底关闭走 [LocalSheetDismissRequest] 才有退出动画, 直调 [onBack] 会砍掉动画。
 * 3. **全屏**: [fullScreen] 只切弹层外壳的高度/圆角 (见 SheetOverlayContent), 组合位置不变 ——
 *    WebView 实例与页面状态 (滚动位置/表单/登录态) 全部保留, 不像推新路由那样重建重载。
 *
 * 与全屏路由仅剩的形态差异: 路由全屏还会调平台 `window.setFullscreen` 隐藏系统栏, 弹层是
 * 独立对话框窗口、不掌管系统栏, 故只撑满窗口 (顶部由 platformStatusBarPadding 让位状态栏)。
 */
@Composable
internal fun WebViewSheetContent(
    spec: AppRoute.WebView,
    onBack: () -> Unit,
    fullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {},
) {
    val dismissSheet = LocalSheetDismissRequest.current ?: onBack
    WebViewScreen(
        spec = spec,
        onClose = dismissSheet,
        fullScreen = fullScreen,
        onToggleFullScreen = onToggleFullScreen,
        backHandler = { onScreenBack -> BackLayerHandler(enabled = true) { onScreenBack() } },
        contentModifier = Modifier
            .background(AppTheme.colors.background)
            .sheetDragExclusion(),
    )
}
