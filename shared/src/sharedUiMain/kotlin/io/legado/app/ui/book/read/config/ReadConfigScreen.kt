package io.legado.app.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 阅读配置聚合页 (shared 独有入口)。
 *
 * app 端阅读菜单把样式/背景/边距/信息/更多/朗读分散为独立 Dialog, shared 端将每个子配置
 * 下沉为独立 Screen 后, 本页作为它们的容器/入口页, 用 LazyColumn + Card 列出各子配置,
 * 点击跳转对应 AppRoute (ReadStyle / BgTextConfig / PaddingConfig / TipConfig /
 * MoreConfig / ReadAloudConfig)。
 *
 * 字符串资源 `stringResource(R.string.xxx)` → `rememberString("xxx")` (key-based, 跨平台)。
 */
@Composable
fun ReadConfigScreen(
    onBack: () -> Unit,
    onReadStyle: () -> Unit,
    onBgTextConfig: () -> Unit,
    onPaddingConfig: () -> Unit,
    onTipConfig: () -> Unit,
    onMoreConfig: () -> Unit,
    onReadAloudConfig: () -> Unit,
) {
    val titleReadConfig = rememberString("read_config")
    val titleReadStyle = rememberString("text_font")
    val titleBgText = rememberString("text_bg_style")
    val titlePadding = rememberString("padding")
    val titleTip = rememberString("information")
    val titleMore = rememberString("other_setting")
    val titleAloud = rememberString("aloud_config")

    // 入口项顺序对齐 app 端 ReadStyleDialog 顶部入口行 + 更多/朗读独立入口
    val items = listOf(
        ReadConfigEntry(titleReadStyle, onReadStyle),
        ReadConfigEntry(titleBgText, onBgTextConfig),
        ReadConfigEntry(titlePadding, onPaddingConfig),
        ReadConfigEntry(titleTip, onTipConfig),
        ReadConfigEntry(titleMore, onMoreConfig),
        ReadConfigEntry(titleAloud, onReadAloudConfig),
    )

    AppTheme {
        Column(Modifier.fillMaxSize()) {
            AppTitleBar(
                title = titleReadConfig,
                onBack = onBack,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(items, key = { it.title }) { entry ->
                    ConfigCard(entry)
                }
            }
        }
    }
}

/** 单个配置入口项: 标题 + 点击回调。 */
private data class ReadConfigEntry(
    val title: String,
    val onClick: () -> Unit,
)

/** 配置入口卡片: 标题 + 右箭头, 复用 AppTheme 颜色与 DesignTokens 形状。 */
@Composable
private fun ConfigCard(entry: ReadConfigEntry) {
    val colors = AppTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { entry.onClick() },
        shape = DesignTokens.cardShape,
        backgroundColor = colors.bottomBackground,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.title,
                color = colors.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = rememberPainter("ic_arrow_right"),
                contentDescription = null,
                tint = colors.secondaryText,
            )
        }
    }
}
