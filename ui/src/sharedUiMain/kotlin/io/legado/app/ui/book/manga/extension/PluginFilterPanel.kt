package io.legado.app.ui.book.manga.extension

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.legado.app.model.webBook.AnimeFilterSession
import io.legado.app.model.webBook.MangaFilterSession
import io.legado.app.model.webBook.PluginFilterSession
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 插件源筛选面板 (搜索页选项行之下 / 发现筛选分页), 直接内联, 不经对话框。
 *
 * 会话实例由宿主 VM 持有并经取数委派共用 (同一份 Filter 对象承载筛选状态), 任一项改动即回调
 * [onChanged] 触发宿主重取数。漫画 ([MangaFilterSession]) 与视频 ([AnimeFilterSession]) 是上游
 * 平行的两份 Filter 契约, 两份面板结构同构、仅条目类型不同, 故这里只做分派。
 */
@Composable
fun PluginFilterPanel(
    session: PluginFilterSession,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (session) {
        is MangaFilterSession -> MangaFilterPanel(
            filterList = session.filters,
            onChanged = onChanged,
            modifier = modifier,
        )

        is AnimeFilterSession -> AnimeFilterPanel(
            filterList = session.filters,
            onChanged = onChanged,
            modifier = modifier,
        )
    }
}

/** 条目缩进: 每级 [DesignTokens.spacingLg], 两侧对齐正文行 (漫画/视频筛选面板共用)。 */
internal fun filterIndent(depth: Int): Modifier = Modifier.padding(
    start = DesignTokens.spacingLg + DesignTokens.spacingLg * depth,
    end = DesignTokens.spacingLg,
)

/** 通用取值行: 名称 + 当前值 (点按整行触发), 尾部可选附加控件 (漫画/视频筛选面板共用)。 */
@Composable
internal fun FilterRow(
    name: String,
    valueText: String,
    indent: Modifier,
    valueBold: Boolean = false,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    Row(
        modifier = indent
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = DesignTokens.spacingDefault),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            fontSize = 15.sp,
            color = colors.primaryText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valueText,
            fontSize = 14.sp,
            fontWeight = if (valueBold) FontWeight.Bold else null,
            color = if (valueBold) colors.accent else colors.secondaryText,
        )
        trailing?.invoke()
    }
}
