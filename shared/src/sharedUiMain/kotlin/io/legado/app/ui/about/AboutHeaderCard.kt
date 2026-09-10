package io.legado.app.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.about_description
import legado.shared.generated.resources.app_name
import org.jetbrains.compose.resources.stringResource

/**
 * 顶部卡片: app 名 + 简介。对照原版 archive 分支 activity_about.xml 的 ll_about:
 * margin/padding = @dimen/arco_spacing_lg (16dp)、app_name 20sp 居中加粗、简介为正文默认字号。
 * 卡片背景对应 AboutActivity 运行时覆盖上去的 Context.filletBackground (圆角 + bottomBackground),
 * 不是 XML 自带的 shape_card_view —— 那个会被 Activity 替掉。
 *
 * 字符串从 R.string 改为 key-based Res.string 以便跨平台。
 *
 * [onHeaderClick]: 整卡可点, 供关于页彩蛋连点计数使用 (计数在 [AboutScreenModel]),
 * 原版 ll_about 不可点, 此处为新增交互。
 *
 * 简介字符串在 composeResources 里必须写成单行: CMP 资源编译器不像 Android aapt
 * 那样折叠 XML 缩进空白, 换行与前导空格会原样进字符串并由 Compose Text 渲染成
 * 空行 + 额外缩进。
 *
 * 两处有意偏离原版 (便于视觉平衡):
 * - app 名与简介之间加 16dp 间距 (原版两个 TextView 无 margin/padding, 两行贴在一起);
 * - 简介逐行居中, 与上方 app 名对齐 (原版左对齐, 首行靠字符串自带的两个
 *   全角空格 U+3000 缩进; 改居中后那两个全角空格会把行顶歪, 已从各 locale 词条删除)。
 */
@Composable
fun AboutHeaderCard(onHeaderClick: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(DesignTokens.shapeDefault)
            .background(colors.bottomBackground)
            .clickable(onClick = onHeaderClick)
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.app_name),
            color = colors.primaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(DesignTokens.spacingLg))
        Text(
            text = stringResource(Res.string.about_description),
            color = colors.primaryText,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
