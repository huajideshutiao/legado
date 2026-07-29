package io.legado.app.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 顶部卡片: app 名 + 简介 (迁 activity_about ll_about, filletBackground)。
 *
 * 从 app 端 AboutActivity.AboutHeaderCard 原样移植:
 * - stringResource(R.string.xxx) → rememberString("xxx") (跨平台 key-based)
 * - 布局/间距/圆角/颜色/字号全部保持原值
 */
@Composable
fun AboutHeaderCard() {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(DesignTokens.shapeDefault)
            .background(colors.bottomBackground)
            .padding(16.dp),
    ) {
        Text(
            text = rememberString("app_name"),
            color = colors.primaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            text = rememberString("about_description"),
            color = colors.primaryText,
            fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
