package io.legado.app.ui.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.PlatformCapabilityProviders
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.copy_url
import legado.shared.generated.resources.delete_source
import legado.shared.generated.resources.disable_source
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.full_screen
import legado.shared.generated.resources.ic_check
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.open_in_browser
import legado.shared.generated.resources.refresh
import legado.shared.generated.resources.sure_del
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * [WebViewScreen] 的无状态外壳组件: 顶栏 / 加载进度条 / 溢出菜单项 / 删除源确认弹窗,
 * 对照原 app 端 WebViewActivity (activity_web_view.xml 的 TitleBar + RefreshProgressBar,
 * web_view.xml 的菜单)。
 *
 * 全屏路由与半屏 Sheet 跑的是同一个 [WebViewScreen], 所以这里不存在"某一端专用"的分支;
 * 两种形态的差异全部收在各自的壳里 (见 [WebViewSheetContent] /
 * [io.legado.app.ui.route.WebViewRoute])。本文件只放纯展示件 —— 状态与流程 (预取/验证回传/
 * 跳转拦截/返回链) 一律在 [WebViewScreen] 内。
 */

/**
 * 加载进度条 (原 activity_web_view.xml RefreshProgressBar: 1dp 加载即常驻):
 * [indeterminate] 时显示不确定进度 (首个进度回调前/预取中), 之后按 [progress]
 * (0..99) 渲染, 100 或完成 (null) 时隐藏。
 */
@Composable
internal fun WebViewLoadingBar(indeterminate: Boolean, progress: Int?) {
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
 * WebView 顶栏: 标题 (空则"加载中") + 刷新 + [extraActions] + 溢出菜单。
 *
 * 对照原 activity_web_view.xml 的 TitleBar 与 web_view.xml 菜单项。
 *
 * @param onRefresh 原 menu_refresh: 调用方自行复位进度后 reload
 * @param extraActions 插在刷新与溢出菜单之间的额外动作 (全屏路由的 menu_ok"确定")
 */
@Composable
internal fun WebViewTitleBar(
    title: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    currentUrl: () -> String,
    onFullScreen: () -> Unit,
    subtitle: String? = null,
    sourceKey: String = "",
    onDisableSource: () -> Unit = {},
    onDeleteSource: () -> Unit = {},
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    AppTitleBar(
        title = title.ifBlank { stringResource(Res.string.loading) },
        subtitle = subtitle,
        onBack = onBack,
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(
                    painter = painterResource(Res.drawable.ic_refresh_black_24dp),
                    contentDescription = stringResource(Res.string.refresh),
                    tint = AppTheme.colors.primaryText,
                )
            }
            extraActions()
            OverflowMenu { dismiss ->
                WebViewOverflowMenuItems(
                    currentUrl = currentUrl,
                    onDismiss = { dismiss() },
                    onFullScreen = onFullScreen,
                    sourceKey = sourceKey,
                    onDisableSource = onDisableSource,
                    onDeleteSource = onDeleteSource,
                )
            }
        },
    )
}

/**
 * 完成动作 (原 web_view.xml menu_ok: ic_check 图标 + showAsAction="always",
 * 登录模式确认 cookie / 验证完成后回传, 与刷新同为顶栏图标按钮而非文字按钮)。
 */
@Composable
internal fun WebViewOkAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(Res.drawable.ic_check),
            contentDescription = stringResource(Res.string.ok),
            tint = AppTheme.colors.primaryText,
        )
    }
}

/**
 * WebView 溢出菜单项 (浏览器打开 / 拷贝 URL / 全屏 / 禁用源 / 删除源)。
 * 唯一消费者是上面的 [WebViewTitleBar], 单独成函数只为让"菜单项 ↔ 原 web_view.xml"一眼对得上。
 *
 * @param currentUrl 当前页 URL (平台实时 URL > 导航完成状态 > 初始 URL, 由调用方决定优先级)
 * @param onDismiss 菜单项 onClick 内调 dismiss() 收起溢出菜单 (OverflowMenu 的 dismiss 回调)
 * @param onFullScreen 全屏动作回调 (已 dismiss 菜单后调用)
 * @param sourceKey 书源 key (空则不显示禁用源/删除源, 对照原版 onPrepareOptionsMenu: sourceOrigin 非空)
 * @param onDisableSource 禁用源动作回调 (已 dismiss 菜单后调用)
 * @param onDeleteSource 删除源动作回调 (已 dismiss 菜单后调用)
 */
@Composable
private fun WebViewOverflowMenuItems(
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
    // 全屏 (原 menu_full_screen → toggleFullScreen; 与原版一致是同一菜单项来回切换)
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
 * 删除源确认弹窗 (原 menu_delete_source 的 alert: sure_del + 源名)。
 * [onConfirm] 内自行收起本弹窗并执行删除。
 */
@Composable
internal fun WebViewDeleteSourceConfirm(
    sourceName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.draw),
        message = stringResource(Res.string.sure_del) + "\n" + sourceName,
        okButton = AlertButton(stringResource(Res.string.ok), dismissOnClick = false) {
            onConfirm()
        },
        cancelButton = AlertButton(stringResource(Res.string.cancel), dismissOnClick = false) {
            onDismiss()
        },
    )
}
