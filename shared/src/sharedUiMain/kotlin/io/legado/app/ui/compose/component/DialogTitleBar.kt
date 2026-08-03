package io.legado.app.ui.compose.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_arrow_back
import org.jetbrains.compose.resources.painterResource

/**
 * Dialog 通用标题栏，复刻 dialog_title_bar：底栏色背景 + 返回箭头(dismiss) + 标题 + 右侧菜单槽。
 */
@Composable
fun DialogTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    titleClickable: Boolean = false,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = AppTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.bottomBackground)
            // 56dp 对照原 TitleBar/Toolbar minHeight=actionBarSize
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = colors.primaryText,
                )
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .let { mod ->
                    if (titleClickable && onTitleClick != null) {
                        mod.clickable(onClick = onTitleClick)
                    } else {
                        mod
                    }
                }
        ) {
            Text(
                text = title,
                color = colors.primaryText,
                fontSize = 20.sp, // ToolbarTitle
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    color = colors.secondaryText,
                    fontSize = 16.sp, // Toolbar.Subtitle(Subhead)
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}
