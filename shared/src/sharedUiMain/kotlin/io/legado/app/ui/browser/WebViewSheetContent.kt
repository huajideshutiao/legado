package io.legado.app.ui.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.PlatformCapabilityProviders
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.copy_url
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.open_in_browser
import legado.shared.generated.resources.refresh
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 加载进度条 (原 activity_web_view.xml RefreshProgressBar: 1dp 加载即常驻):
 * [indeterminate] 时显示不确定进度 (首个进度回调前/预取中), 之后按 [progress]
 * (0..99) 渲染, 100 或完成 (null) 时隐藏。WebViewRoute 与半屏 Sheet 共用。
 */
@Composable
fun WebViewLoadingBar(indeterminate: Boolean, progress: Int?) {
    if (indeterminate) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(1.dp),
            color = AppTheme.colors.accent,
        )
    } else {
        val p = progress
        if (p != null) {
            val animatedProgress by animateFloatAsState(p / 100f)
            LinearProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier.fillMaxWidth().height(1.dp),
                color = AppTheme.colors.accent,
            )
        }
    }
}

/**
 * startBrowser(asBottomSheet=true) 半屏 WebView Sheet 的内容区:
 * 顶栏 (返回/刷新/浏览器打开/拷贝 URL) + 加载进度条 + 平台 WebView slot。
 *
 * 浏览器形态对齐 [io.legado.app.ui.route.WebViewRoute], 但仅携带 url, 无书源验证/
 * 源管理/全屏语义; 弹层外壳 (0.8 锚点高/圆角/拖拽协调) 由 SheetOverlayContent 的
 * AppBottomSheetDialog 承载。
 *
 * 手势协调: WebView 用默认配置, 网页滚到边界后的 overscroll 由 WebView 自己消费
 * (原生光晕), 竖直手势不交还外层面板 → 内容区滚动不触发 sheet 拖拽, 下拉关闭
 * 只在顶栏等无可滚动区生效。
 */
@Composable
internal fun WebViewSheetContent(
    url: String,
    onBack: () -> Unit,
) {
    // 顶栏 + 进度条 + 内容区 (对齐 WebViewRoute 的路由页形态, 修复 Sheet 白屏无顶栏):
    // 原版 activity_web_view.xml TitleBar 静态常显 + RefreshProgressBar 1dp 加载即常驻
    var pageTitle by remember { mutableStateOf("") }
    var loadProgress by remember { mutableStateOf<Int?>(null) }
    // 收到首个进度回调前 indeterminate 常驻 (原版加载即常驻, 100 隐藏)
    var progressStarted by remember { mutableStateOf(false) }
    val webCallbacks = remember { WebViewCallbacks() }
    // 网页 title/进度接线 (对齐 WebViewRoute: title 非空且非 url 才更新; 100 隐藏进度)
    SideEffect {
        webCallbacks.onReceivedTitle = { title ->
            if (!title.isNullOrBlank() && !title.startsWith("http")) {
                pageTitle = title
            }
        }
        webCallbacks.onProgressChanged = { progress ->
            progressStarted = true
            loadProgress = if (progress >= 100) null else progress
        }
    }
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = pageTitle.ifBlank { stringResource(Res.string.loading) },
            onBack = onBack,
            actions = {
                // 刷新 (对照 WebViewRoute 的 menu_refresh: 进度复位 + reload)
                IconButton(onClick = {
                    progressStarted = true
                    loadProgress = 0
                    webCallbacks.host?.reload()
                }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_refresh_black_24dp),
                        contentDescription = stringResource(Res.string.refresh),
                        tint = AppTheme.colors.primaryText,
                    )
                }
                // 溢出菜单: 浏览器打开 / 拷贝 URL (对照 WebViewRoute; Sheet 仅携带 url,
                // 无书源验证/源管理语义, 不提供确定/禁用源/删除源)
                OverflowMenu { dismiss ->
                    DropdownMenuItem(
                        onClick = {
                            dismiss()
                            PlatformCapabilityProviders.getOrNull()
                                ?.openExternalUrl(webCallbacks.host?.getUrl() ?: url)
                        },
                    ) {
                        Text(
                            stringResource(Res.string.open_in_browser),
                            color = AppTheme.colors.primaryText,
                        )
                    }
                    DropdownMenuItem(
                        onClick = {
                            dismiss()
                            PlatformCapabilityProviders.getOrNull()
                                ?.copyToClipboard(webCallbacks.host?.getUrl() ?: url)
                        },
                    ) {
                        Text(
                            stringResource(Res.string.copy_url),
                            color = AppTheme.colors.primaryText,
                        )
                    }
                }
            },
        )
        WebViewLoadingBar(indeterminate = !progressStarted, progress = loadProgress)
        // 内容区: 主题底色占位 (WebView 组合/加载完成前不白屏) + 平台 WebView slot
        //
        // 手势拦截: Android WebView 嵌在 AndroidView 中不参与 Compose 嵌套滚动,
        // 外层 AppBottomSheetDialog 的 pointerInput (awaitVerticalTouchSlopOrCancellation)
        // 无法检测到 WebView 是否消费了位移, 会抢走 WebView 内部的竖直滚动手势。
        // 在此 Box 上挂 pointerInput 消费竖直拖拽位移: 当手指在 WebView 区域竖直滑动时,
        // 本 pointerInput 先赢得 slop 竞争并消费位移, 外层 AppBottomSheetDialog 的
        // pointerInput 因检测到位移已被消费而让位 → 竖直手势归 WebView, 下拉关闭只在
        // 顶栏等无可滚动区生效。水平手势不拦截, WebView 内横向滚动不受影响。
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(AppTheme.colors.background)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 竖直拖拽竞争: 赢得 slop 后消费后续竖直位移, 阻止外层 sheet 跟手
                        awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        } ?: return@awaitEachGesture
                        // 持续消费竖直位移: WebView 自己处理滚动 (AndroidView 内部 View
                        // 收到事件后自行滚动, 这里的 consume 只阻止外层 pointerInput 跟手)
                        drag(down.id) { change ->
                            change.consume()
                        }
                    }
                },
        ) {
            LocalWebViewSlot.current(
                WebViewConfig(url = url),
                Modifier.fillMaxSize(),
                webCallbacks,
            )
        }
    }
}
