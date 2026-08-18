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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.source.SourceHelp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.PlatformCapabilityProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.copy_url
import legado.shared.generated.resources.delete_source
import legado.shared.generated.resources.disable_source
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.full_screen
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.open_in_browser
import legado.shared.generated.resources.refresh
import legado.shared.generated.resources.sure_del
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
 * WebView 溢出菜单共享项 (浏览器打开 / 拷贝 URL / 全屏 / 禁用源 / 删除源)。
 *
 * WebViewRoute (全屏路由) 与 WebViewSheetContent (半屏 Sheet) 共用本函数,
 * 消除两端菜单项重复逻辑; 各端通过回调参数注入平台差异行为。
 *
 * @param currentUrl 当前页 URL (平台实时 URL > 导航完成状态 > 初始 URL, 由调用方决定优先级)
 * @param onDismiss 菜单项 onClick 内调 dismiss() 收起溢出菜单 (OverflowMenu 的 dismiss 回调)
 * @param onFullScreen 全屏动作回调 (已 dismiss 菜单后调用)
 * @param sourceKey 书源 key (空则不显示禁用源/删除源, 对照原版 onPrepareOptionsMenu: sourceOrigin 非空)
 * @param onDisableSource 禁用源动作回调 (已 dismiss 菜单后调用)
 * @param onDeleteSource 删除源动作回调 (已 dismiss 菜单后调用)
 */
@Composable
fun WebViewOverflowMenuItems(
    currentUrl: () -> String,
    onDismiss: () -> Unit,
    onFullScreen: () -> Unit,
    sourceKey: String = "",
    onDisableSource: () -> Unit = {},
    onDeleteSource: () -> Unit = {},
) {
    // 浏览器打开 (原 menu_open_in_browser → openUrl)
    DropdownMenuItem(
        onClick = {
            onDismiss()
            PlatformCapabilityProviders.getOrNull()?.openExternalUrl(currentUrl())
        },
    ) {
        Text(
            stringResource(Res.string.open_in_browser),
            color = AppTheme.colors.primaryText,
        )
    }
    // 拷贝 URL (原 menu_copy_url → sendToClip)
    DropdownMenuItem(
        onClick = {
            onDismiss()
            PlatformCapabilityProviders.getOrNull()?.copyToClipboard(currentUrl())
        },
    ) {
        Text(
            stringResource(Res.string.copy_url),
            color = AppTheme.colors.primaryText,
        )
    }
    // 全屏 (原 menu_full_screen → toggleFullScreen)
    DropdownMenuItem(
        onClick = {
            onDismiss()
            onFullScreen()
        },
    ) {
        Text(
            stringResource(Res.string.full_screen),
            color = AppTheme.colors.primaryText,
        )
    }
    // 原 onPrepareOptionsMenu: sourceKey 非空才显示禁用/删除源
    if (sourceKey.isNotEmpty()) {
        // 禁用源 (原 menu_disable_source → viewModel.disableSource { finish() })
        DropdownMenuItem(
            onClick = {
                onDismiss()
                onDisableSource()
            },
        ) {
            Text(
                stringResource(Res.string.disable_source),
                color = AppTheme.colors.primaryText,
            )
        }
        // 删除源 (原 menu_delete_source → alert 确认后 viewModel.deleteSource { finish() })
        DropdownMenuItem(
            onClick = {
                onDismiss()
                onDeleteSource()
            },
        ) {
            Text(
                stringResource(Res.string.delete_source),
                color = AppTheme.colors.primaryText,
            )
        }
    }
}

/**
 * startBrowser(asBottomSheet=true) 半屏 WebView Sheet 的内容区:
 * 顶栏 (返回/刷新/溢出菜单: 浏览器打开/拷贝 URL/全屏/禁用源/删除源) + 加载进度条 + 平台 WebView slot。
 *
 * 浏览器形态对齐 [io.legado.app.ui.route.WebViewRoute], 菜单项通过
 * [WebViewOverflowMenuItems] 共享; 弹层外壳 (0.8 锚点高/圆角/拖拽协调) 由
 * SheetOverlayContent 的 AppBottomSheetDialog 承载。
 *
 * 手势协调: WebView 用默认配置, 网页滚到边界后的 overscroll 由 WebView 自己消费
 * (原生光晕), 竖直手势不交还外层面板 → 内容区滚动不触发 sheet 拖拽, 下拉关闭
 * 只在顶栏等无可滚动区生效。
 */
@Composable
internal fun WebViewSheetContent(
    url: String,
    sourceKey: String = "",
    sourceName: String = "",
    sourceType: Int = 0,
    onBack: () -> Unit,
    onFullScreen: () -> Unit = onBack,
) {
    val scope = rememberCoroutineScope()
    // 顶栏 + 进度条 + 内容区 (对齐 WebViewRoute 的路由页形态, 修复 Sheet 白屏无顶栏):
    // 原版 activity_web_view.xml TitleBar 静态常显 + RefreshProgressBar 1dp 加载即常驻
    var pageTitle by remember { mutableStateOf("") }
    var loadProgress by remember { mutableStateOf<Int?>(null) }
    // 收到首个进度回调前 indeterminate 常驻 (原版加载即常驻, 100 隐藏)
    var progressStarted by remember { mutableStateOf(false) }
    // 删除源确认弹窗 (对照 WebViewRoute 的 showDeleteConfirm)
    var showDeleteConfirm by remember { mutableStateOf(false) }
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
                // 溢出菜单: 浏览器打开 / 拷贝 URL / 全屏 / 禁用源 / 删除源
                // (对照 WebViewRoute, 通过 WebViewOverflowMenuItems 共享)
                OverflowMenu { dismiss ->
                    WebViewOverflowMenuItems(
                        currentUrl = { webCallbacks.host?.getUrl() ?: url },
                        onDismiss = { dismiss() },
                        onFullScreen = onFullScreen,
                        sourceKey = sourceKey,
                        onDisableSource = {
                            scope.launch(IoDispatcher) {
                                runCatching {
                                    SourceHelp.enableSource(sourceKey, sourceType, false)
                                }.onSuccess {
                                    withContext(Dispatchers.Main) { onBack() }
                                }
                            }
                        },
                        onDeleteSource = { showDeleteConfirm = true },
                    )
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

    // 删除源确认弹窗 (对照 WebViewRoute 的 showDeleteConfirm alert:
    // sure_del + 源名, 确认后 SourceHelp.deleteSource + 关闭)
    if (showDeleteConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del) + "\n" + sourceName,
            okButton = AlertButton(stringResource(Res.string.ok), dismissOnClick = false) {
                showDeleteConfirm = false
                scope.launch(IoDispatcher) {
                    runCatching {
                        SourceHelp.deleteSource(sourceKey, sourceType)
                    }.onSuccess {
                        withContext(Dispatchers.Main) { onBack() }
                    }
                }
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel), dismissOnClick = false) {
                showDeleteConfirm = false
            },
        )
    }
}
