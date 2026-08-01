package io.legado.app.ui.book.rss

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.browser.WebViewConfig
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.login
import legado.shared.generated.resources.open_in_browser
import legado.shared.generated.resources.refresh
import legado.shared.generated.resources.share
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/*
 * 下沉所需资源 key 清单 (供 ResourceProvider 各平台 actual 补全)
 *
 * Painter key (drawable):
 *   - ic_refresh_black_24dp  (刷新)
 *   - ic_star                (已收藏)
 *   - ic_star_border         (未收藏)
 *
 * String key (string):
 *   - refresh / share / read_aloud / aloud_stop / login / open_in_browser
 *   - in_favorites / out_favorites
 *
 * L3 不可下沉项 (由 platformWebViewSlot 注入):
 *   - WebView 容器。正文与无规则地址一律走 WebView (对照原 ReadRssActivity: 有正文规则时
 *     loadDataWithBaseURL 加载 clHtml 包装后的 HTML, 无正文规则时 loadUrl(url, headerMap)),
 *     图片/视频/网页脚本/Cookie/UA 才与原版一致。
 */

/**
 * RSS 阅读页 shared Screen, 对照 app 端原 [io.legado.app.ui.book.rss.ReadRssActivity]。
 *
 * 布局: 标题栏(刷新/收藏/溢出菜单) + WebView 内容区。
 * 视频全屏时隐藏标题栏 (对照原 CommonWebChromeClient.onShowCustomView 的 llView.invisible)。
 *
 * @param state UI 展示状态
 * @param actions 平台回调 (返回/刷新/收藏/分享/朗读/浏览器打开/登录)
 * @param platformWebViewSlot 平台 WebView 容器, 参数为待加载配置 (HTML 或 URL+headers)
 */
@Composable
fun ReadRssScreen(
    state: ReadRssUiState,
    actions: ReadRssUiActions,
    platformWebViewSlot: @Composable (WebViewConfig) -> Unit,
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
            ContentArea(state, platformWebViewSlot)
        }
    }
}

/** 内容区: 拿到 webConfig 就交 WebView, 否则转圈/显示错误 */
@Composable
private fun ContentArea(
    state: ReadRssUiState,
    platformWebViewSlot: @Composable (WebViewConfig) -> Unit,
) {
    val colors = AppTheme.colors
    val config = state.webConfig
    when {
        config != null -> Box(Modifier.fillMaxSize()) { platformWebViewSlot(config) }

        state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = state.error,
                color = colors.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(16.dp),
            )
        }

        // 首次加载 / 刷新中
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
    }
}

/**
 * 标题栏动作区, 对照原版 menu/rss_read.xml。
 * 刷新(always) + 收藏(仅有书时) + 溢出菜单(分享/朗读/登录/浏览器打开, 登录项仅书源配了登录时)。
 */
@Composable
private fun RssActions(state: ReadRssUiState, actions: ReadRssUiActions) {
    val colors = AppTheme.colors
    IconButton(onClick = actions::onRefresh) {
        Icon(
            painter = painterResource(Res.drawable.ic_refresh_black_24dp),
            contentDescription = stringResource(Res.string.refresh),
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
        ) { Text(stringResource(Res.string.share), color = colors.primaryText) }
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
            ) { Text(stringResource(Res.string.login), color = colors.primaryText) }
        }
        DropdownMenuItem(
            onClick = {
                dismiss()
                actions.onOpenInBrowser()
            },
        ) { Text(stringResource(Res.string.open_in_browser), color = colors.primaryText) }
    }
}
