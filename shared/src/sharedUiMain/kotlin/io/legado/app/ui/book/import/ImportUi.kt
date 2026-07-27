package io.legado.app.ui.book.import

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppConst
import io.legado.app.constant.dateFormat
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.ConvertUtils

/*
 * 下沉所需资源 key 清单 (供 ResourceProvider 各平台 actual 补全)
 *
 * Painter key (drawable):
 *   - ic_folder_open     (上级目录/选择目录图标)
 *   - ic_folder          (子目录图标)
 *   - ic_book_has        (已上架图标)
 *
 * String key (string):
 *   - (ImportUi.kt 内部不直接取 string, 文案由调用方 ImportBookScreen / RemoteBookScreen 传入)
 *
 * L3 不可下沉项: 无 (本文件全为纯 Compose + 共享组件)
 */

// ImportFileItem 接口已下沉 commonMain (ui/book/import/ImportFileItem.kt), 供 RemoteBook (commonMain)
// 实现该接口。sharedUiMain 经 dependsOn(commonMain) 可见本接口, 此处仅保留 Composable 实现。

/**
 * 导入家族(本地/远程)共享骨架：标题栏 + 面包屑 + 2dp 进度条 + 列表 + 底部批量栏。
 * 对照 activity_recycler_with_action_bar 布局结构。
 */
@Composable
fun ImportScaffold(
    path: String?,
    loading: Boolean,
    emptyVisible: Boolean,
    emptyText: String,
    titleBar: @Composable () -> Unit,
    actionBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    listContent: LazyListScope.() -> Unit,
) {
    val colors = AppTheme.colors
    Column(modifier.fillMaxSize()) {
        titleBar()
        if (path != null) {
            Text(
                text = path,
                color = colors.secondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        ImportRefreshBar(loading)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(Modifier.fillMaxSize(), content = listContent)
            if (emptyVisible) {
                Text(
                    text = emptyText,
                    color = colors.secondaryText,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
            }
        }
        Box(Modifier.navigationBarsPadding()) { actionBar() }
    }
}

/** 对照 RefreshProgressBar：常驻 2dp 占位，加载中走动画(E-Ink 静态色条)。 */
@Composable
fun ImportRefreshBar(loading: Boolean, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    when {
        !loading -> Spacer(modifier.height(2.dp))
        LocalEInk.current -> Box(
            modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.accent),
        )

        else -> LinearProgressIndicator(
            color = colors.accent,
            backgroundColor = Color.Transparent,
            modifier = modifier
                .fillMaxWidth()
                .height(2.dp),
        )
    }
}

/**
 * 文件/目录行，对照 item_import_book：60dp 高，左侧 52dp 槽(目录图标/已上架图标/复选框)，
 * 右侧书名 + 文件简介(类型标签/大小/日期)。复选框不可点，整行点击切换。
 */
@Composable
fun ImportFileRow(
    name: String,
    isDir: Boolean,
    isUpDir: Boolean,
    checkable: Boolean,
    checked: Boolean,
    onBookShelf: Boolean,
    tag: String,
    size: Long,
    lastModified: Long,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            when {
                isUpDir -> RowIcon(rememberPainter("ic_folder_open"), Modifier.align(Alignment.Center))
                isDir -> RowIcon(rememberPainter("ic_folder"), Modifier.align(Alignment.Center))
                checkable -> AppCheckbox(
                    checked = checked,
                    onCheckedChange = null,
                    modifier = Modifier.align(Alignment.CenterStart),
                )

                onBookShelf -> RowIcon(rememberPainter("ic_book_has"), Modifier.align(Alignment.Center))
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = name,
                color = colors.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!isDir && !isUpDir) {
                Row(
                    Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 类型标签复刻 AccentBgTextView：accent 底 4dp 圆角，字色随 accent 亮度
                    Text(
                        text = tag,
                        color = if (ColorUtils.isColorLight(colors.accent.toArgb())) Color.Black else Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .widthIn(max = 50.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.accent)
                            .padding(horizontal = 4.dp),
                    )
                    Text(
                        text = ConvertUtils.formatFileSize(size),
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    Text(
                        text = AppConst.dateFormat.format(lastModified),
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowIcon(painter: Painter, modifier: Modifier) {
    Icon(
        painter = painter,
        contentDescription = null,
        tint = AppTheme.colors.primaryText,
        modifier = modifier.size(24.dp),
    )
}

/** 排序单选菜单项：勾标记 accent 显隐。 */
@Composable
fun ImportSortItem(text: String, checked: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(text, color = colors.primaryText)
        Spacer(Modifier.weight(1f))
        AppMenuCheckbox(checked = checked)
    }
}

@Composable
fun ImportMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(text, color = AppTheme.colors.primaryText)
    }
}
