package io.legado.app.ui.compose.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.transitionStatusBarPadding
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.back
import legado.shared.generated.resources.clear
import legado.shared.generated.resources.ic_arrow_back
import legado.shared.generated.resources.ic_baseline_close
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.ic_search
import legado.shared.generated.resources.more_menu
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Compose 版顶部标题栏，复刻 View TitleBar 视觉：
 * 背景取值(EInk 白底+底部分割线 / 有壁纸透明 / 否则 backgroundColor)、
 * 标题 20sp、返回箭头、右侧菜单区、状态栏沉浸 padding。
 *
 * @param titleContent 覆盖标题区(如搜索框)；为空则显示 [title] 文本。
 * @param subtitle 标题下方小字 (对照原 TitleBar.subtitle, 如 WebView 页的书源名); 空则不显示。
 */
@Composable
fun AppTitleBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    val themeStore = LocalThemeStoreProvider.current
    val hasBgImage = remember(themeStore) {
        !themeStore.bgImagePath.isNullOrBlank()
    }
    val bg = when {
        eInk -> Color.White
        hasBgImage -> Color.Transparent
        else -> colors.background
    }
    // 对话框窗口已自行避让系统栏 (decorFitsSystemWindows=true 时内容区避开状态栏;
    // Android 15+ targetSdk 35+ 强制 edge-to-edge 时窗口虽全屏, 但本应用弹层内容为
    // 0.7~0.92 锚点高、底部贴齐或居中, 顶部均不触达状态栏区域), 顶栏不再叠加状态栏
    // padding, 否则双重避让 → 弹窗顶部多出一层状态栏高的空白带 (目录/TXT目录规则/
    // 浏览器半屏等弹窗)。路由页 (LocalDialogWindow=false) 保持页面语义的状态栏沉浸 padding。
    val insetsModifier = when {
        eInk -> Modifier.windowInsetsPadding(WindowInsets(0))
        LocalDialogWindow.current -> Modifier
        else -> Modifier.transitionStatusBarPadding()
    }
    Box(
        modifier
            .fillMaxWidth()
            .background(bg)
            .then(insetsModifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_back),
                    contentDescription = stringResource(Res.string.back),
                    tint = colors.primaryText,
                )
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (titleContent != null) {
                    titleContent()
                } else if (subtitle.isNullOrBlank()) {
                    Text(
                        text = title,
                        color = colors.primaryText,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Column {
                        Text(
                            text = title,
                            color = colors.primaryText,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = subtitle,
                            color = colors.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            actions()
        }
        if (eInk) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.secondaryText.copy(alpha = 0.4f))
                    .align(Alignment.BottomStart),
            )
        }
    }
}

/**
 * 复刻 view_search：圆角描边(bg_searchview: transparent10 填充/描边 + 8dp 圆角) + 搜索图标 + 输入框，
 * 供 TitleBar 的 titleContent 槽承载搜索语义(替代 SearchView)。
 *
 * @param textFieldModifier 挂在内部输入框上(focusRequester/onFocusChanged)。
 * @param onSearch 非空时启用 IME 搜索键并回调(对齐 onQueryTextSubmit)。
 */
@Composable
fun AppSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    textFieldModifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    // transparent10: light #10000000 / dark #10ffffff
    val fillStroke = if (colors.isDark) Color(0x10ffffff) else Color(0x10000000)
    Row(
        modifier
            .fillMaxWidth()
            .padding(end = 8.dp)
            .height(DesignTokens.viewHeightDefault)
            .clip(DesignTokens.shapeDefault)
            .border(DesignTokens.strokeHairline, fillStroke, DesignTokens.shapeDefault)
            .background(fillStroke)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_search),
            contentDescription = null,
            tint = colors.secondaryText,
            modifier = Modifier.size(18.dp),
        )
        Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(hint, color = colors.secondaryText, fontSize = 14.sp, maxLines = 1)
            }
            // 新版 TextFieldState API: 受控双同步 (用户编辑 → onValueChange; 外部 value →
            // state, 覆盖清除按钮等程序化修改)。保留 BasicTextField: 外层 Row 已自绘
            // 圆角描边/背景/搜索图标/清除按钮, AppTextField 自带下划线/浮动 label 语义不符。
            // state 用初始值初始化 (防 snapshotFlow 首帧把空串推给 onValueChange 清空外部值)
            val state = remember { TextFieldState(value) }
            val currentValue by rememberUpdatedState(value)
            val currentOnValueChange by rememberUpdatedState(onValueChange)
            LaunchedEffect(state) {
                snapshotFlow { state.text.toString() }
                    .collect { if (it != currentValue) currentOnValueChange(it) }
            }
            LaunchedEffect(state, value) {
                if (state.text.toString() != value) {
                    state.edit { replace(0, length, value) }
                }
            }
            BasicTextField(
                state = state,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = TextStyle(color = colors.primaryText, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = if (onSearch != null) {
                    KeyboardOptions(imeAction = ImeAction.Search)
                } else {
                    KeyboardOptions.Default
                },
                // 新版 onKeyboardAction 不区分 ImeAction (仅给 performDefaultAction);
                // 字段已配置 imeAction=Search, 触发即等价旧 onSearch 回调
                onKeyboardAction = if (onSearch != null) {
                    { onSearch() }
                } else {
                    null
                },
                modifier = textFieldModifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(24.dp)) {
                Icon(
                    painter = painterResource(Res.drawable.ic_baseline_close),
                    contentDescription = stringResource(Res.string.clear),
                    tint = colors.secondaryText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** TitleBar 溢出菜单：竖点图标 + 展开的 DropdownMenu，项由调用方填充。
 *
 *  @param onExpandedChange 展开/收起状态回调 (供调用方感知弹层遮挡, 如桌面端 airspace 窗口)
 */
@Composable
fun OverflowMenu(
    modifier: Modifier = Modifier,
    onExpandedChange: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val colors = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    // 只保留手动回调, 不挂 LaunchedEffect(expanded): 否则每次开合回调两次且首帧误报 false
    Box(modifier) {
        IconButton(onClick = {
            expanded = true
            onExpandedChange(true)
        }) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_vert),
                contentDescription = stringResource(Res.string.more_menu),
                tint = colors.primaryText,
            )
        }
        AppDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                onExpandedChange(false)
            },
        ) {
            content {
                expanded = false
                onExpandedChange(false)
            }
        }
    }
}
