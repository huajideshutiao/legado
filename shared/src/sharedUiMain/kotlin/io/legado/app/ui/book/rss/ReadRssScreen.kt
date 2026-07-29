package io.legado.app.ui.book.rss

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/*
 * 下沉所需资源 key 清单 (供 ResourceProvider 各平台 actual 补全)
 *
 * Painter key (drawable):
 *   - ic_refresh_black_24dp  (刷新)
 *   - ic_star                (已收藏)
 *   - ic_star_border         (未收藏)
 *   - ic_share               (分享)
 *   - ic_volume_up            (朗读)
 *   - ic_stop_black_24dp     (停止朗读)
 *
 * String key (string):
 *   - refresh / share / read_aloud / aloud_stop / login / open_in_browser
 *   - in_favorites / out_favorites
 *
 * L3 不可下沉项 (保留 app 端, 通过 slot 注入):
 *   - WebView 容器 (VisibleWebView + RefreshProgressBar + customViewContainer)
 *     → platformWebViewSlot: @Composable (String) -> Unit
 *     参数为当前文章 URL, 平台侧自行 loadUrl / loadDataWithBaseURL
 */

/**
 * RSS 阅读页 shared Screen, 对照 app 端 [io.legado.app.ui.book.rss.ReadRssActivity.Content]。
 *
 * 布局: 标题栏(刷新/收藏/溢出菜单) + WebView 内容区。
 * 视频全屏时隐藏标题栏, WebView 区填满整屏 (视频覆盖层由平台 slot 内部处理)。
 *
 * @param state UI 展示状态 (标题/收藏/TTS/登录/视频全屏等)
 * @param actions 平台回调 (返回/刷新/收藏/分享/朗读/浏览器打开/登录)
 * @param platformWebViewSlot 平台 WebView 容器, 参数为当前文章 URL
 *   (app 端: VisibleWebView + RefreshProgressBar + customViewContainer;
 *    桌面/iOS: 各平台 WebView 组件)
 */
@Composable
fun ReadRssScreen(
    state: ReadRssUiState,
    actions: ReadRssUiActions,
    platformWebViewSlot: @Composable (String) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.ime)
                        .only(WindowInsetsSides.Bottom)
                )
        ) {
            if (!state.videoFullScreen) {
                AppTitleBar(
                    title = state.pageTitle ?: "",
                    onBack = actions::onBack,
                    actions = { RssActions(state, actions) },
                )
            }
            // WebView 内容区: 占满剩余空间, 平台 slot 填充
            Box(Modifier.fillMaxWidth().weight(1f)) {
                platformWebViewSlot(state.currArticle?.url ?: "")
            }
        }
    }
}

/**
 * 标题栏动作区, 对照 app 端 ReadRssActivity.RssActions。
 * 刷新(always) + 收藏(if starVisible) + 溢出菜单(分享/朗读/登录/浏览器打开)。
 */
@Composable
private fun RssActions(state: ReadRssUiState, actions: ReadRssUiActions) {
    val colors = AppTheme.colors
    IconButton(onClick = actions::onRefresh) {
        Icon(
            painter = rememberPainter("ic_refresh_black_24dp"),
            contentDescription = rememberString("refresh"),
            tint = colors.primaryText,
        )
    }
    if (state.starVisible) {
        IconButton(onClick = actions::onToggleStar) {
            Icon(
                painter = rememberPainter(if (state.inShelf) "ic_star" else "ic_star_border"),
                contentDescription = rememberString(
                    if (state.inShelf) "in_favorites" else "out_favorites"
                ),
                tint = colors.primaryText,
            )
        }
    }
    OverflowMenu { dismiss ->
        DropdownMenuItem(
            onClick = {
                dismiss()
                actions.onShare()
            },
        ) { Text(rememberString("share"), color = colors.primaryText) }
        DropdownMenuItem(
            onClick = {
                dismiss()
                actions.onReadAloud()
            },
        ) {
            Text(
                rememberString(if (state.ttsPlaying) "aloud_stop" else "read_aloud"),
                color = colors.primaryText,
            )
        }
        if (state.hasLogin) {
            DropdownMenuItem(
                onClick = {
                    dismiss()
                    actions.onLogin()
                },
            ) { Text(rememberString("login"), color = colors.primaryText) }
        }
        DropdownMenuItem(
            onClick = {
                dismiss()
                actions.onOpenInBrowser()
            },
        ) { Text(rememberString("open_in_browser"), color = colors.primaryText) }
    }
}
