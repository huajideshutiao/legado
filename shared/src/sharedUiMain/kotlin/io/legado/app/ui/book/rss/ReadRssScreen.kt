package io.legado.app.ui.book.rss

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.HtmlFormatter
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.login
import legado.shared.generated.resources.open_in_browser
import legado.shared.generated.resources.refresh
import legado.shared.generated.resources.rss_no_content_rule_hint
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
 *   - ic_share               (分享)
 *   - ic_volume_up            (朗读)
 *   - ic_stop_black_24dp     (停止朗读)
 *
 * String key (string):
 *   - refresh / share / read_aloud / aloud_stop / login / open_in_browser
 *   - in_favorites / out_favorites
 *   - rss_no_content_rule_hint (无正文规则提示)
 *   - empty (空态)
 *
 * L3 不可下沉项 (保留 app 端, 通过 slot 注入):
 *   - WebView 容器 (VisibleWebView + RefreshProgressBar + customViewContainer)
 *     → platformWebViewSlot: @Composable (String) -> Unit
 *     参数为当前文章 URL, 平台侧自行 loadUrl
 *     (仅 URL-only 模式调用; HTML body 走 HtmlFormatter.format 纯文本渲染, 跨平台一致)
 */

/**
 * RSS 阅读页 shared Screen, 对照 app 端 [io.legado.app.ui.book.rss.ReadRssActivity.Content]。
 *
 * 布局: 标题栏(刷新/收藏/溢出菜单) + 内容区。
 * 内容区按状态分流:
 * - Loading: 居中转圈
 * - Error: 居中显示错误信息
 * - 无正文规则 (hasContentRule=false 且 contentUrl 非空): WebView 加载 URL
 * - Content (contentBody 非空): HtmlFormatter 格式化纯文本, 垂直滚动 (跨平台一致)
 * 视频全屏时隐藏标题栏, 内容区填满整屏。
 *
 * @param state UI 展示状态
 * @param actions 平台回调 (返回/刷新/收藏/分享/朗读/浏览器打开/登录)
 * @param platformWebViewSlot 平台 WebView 容器, 参数为待加载 URL (URL-only 模式使用)
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
            // 内容区: 按状态分流
            ContentArea(state, platformWebViewSlot)
        }
    }
}

/** 内容区: 按加载状态分流渲染 */
@Composable
private fun ContentArea(
    state: ReadRssUiState,
    platformWebViewSlot: @Composable (String) -> Unit,
) {
    val colors = AppTheme.colors
    when {
        // 加载中且无内容: 居中转圈
        state.isLoading && state.contentBody == null && state.contentUrl == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
        }

        // 加载失败且无内容: 居中显示错误
        state.error != null && state.contentBody == null && state.contentUrl == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = state.error,
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        // URL-only 模式 (无正文规则): 用平台 WebView 加载 URL
        state.contentUrl != null -> {
            Box(Modifier.fillMaxSize()) {
                platformWebViewSlot(state.contentUrl)
            }
        }

        // HTML body 内容: HtmlFormatter 格式化纯文本, 垂直滚动
        state.contentBody != null -> {
            val scrollState = rememberScrollState()
            Text(
                text = HtmlFormatter.format(state.contentBody),
                color = colors.primaryText,
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }

        // 初始态/空态: 提示无正文规则
        else -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.rss_no_content_rule_hint),
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                )
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
