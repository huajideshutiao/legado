package io.legado.app.ui.compose.preference

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.Icon
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.PreferenceProvider
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.platform.rememberNavigationBarPaddingValues
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.ColorUtils
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

/**
 * Compose 设置 DSL（对齐 androidx.preference + lib/prefs 视觉/行为）。
 * 视觉对齐 view_preference：行透明、按压 btn_bg、图标 accent、标题 16sp、摘要 14sp。
 * 读写走 PreferenceProviders 单例 (PreferenceProvider) 的 get/put Boolean/Int/String，key 不变；
 * isBottomBackground 复刻 parseIsBottomBackground。
 *
 * 下沉 commonMain 后, 原 LocalContext.current + Context.getPrefX/putPrefX 由
 * [PreferenceProviders] 注入, 替代 SharedPreferences 直接访问。
 */

/** LazyColumn 容器，透明背景（露出 Activity 主题背景/壁纸），底部避让导航栏 */
@Composable
fun PreferenceScreen(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    // 替代 WindowInsets.navigationBars.asPaddingValues(): commonMain 走 expect/actual
    // Android 端等价返回 WindowInsets.navigationBars.asPaddingValues(), 其他平台返回 PaddingValues(0)
    contentPadding: PaddingValues = rememberNavigationBarPaddingValues(),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
        content = content,
    )
}

/** 分组标题：accent 色，复刻 PreferenceCategory */
fun LazyListScope.preferenceCategory(title: String) = item {
    Text(
        text = title,
        color = AppTheme.colors.accent,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** 普通项：title/summary/icon/click，复刻 Preference */
fun LazyListScope.preference(
    title: String,
    summary: String? = null,
    icon: Painter? = null,
    enabled: Boolean = true,
    isBottomBackground: Boolean = false,
    widget: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) = item {
    PreferenceRow(
        title = title,
        summary = summary,
        icon = icon,
        enabled = enabled,
        isBottomBackground = isBottomBackground,
        onClick = onClick,
        onLongClick = onLongClick,
        widget = widget,
    )
}

/**
 * 跟随 pref 外部变更的本地状态。
 *
 * 组件自身写入即时生效；同一个 key 被别的入口改掉时（如桌面标题栏日夜按钮直接写
 * themeMode）也同步刷新显示 —— 无 key 的 `remember` 是一次性快照, 会让设置项停在旧值
 * (用户实测: 标题栏切完主题, 「我的」里「主题模式」仍显示切换前的档位)。
 */
@Composable
private fun <T> rememberPrefState(prefKey: String, read: (PreferenceProvider) -> T): MutableState<T> {
    val pref = PreferenceProviders.get()
    val state = remember(prefKey) { mutableStateOf(read(pref)) }
    DisposableEffect(pref, prefKey) {
        // iOS 的 NSUserDefaults 通知不含变更 key, 回调空串按"任意 key 变更"处理
        val dispose = pref.addPreferenceChangeListener { key ->
            if (key.isEmpty() || key == prefKey) state.value = read(pref)
        }
        onDispose { dispose() }
    }
    return state
}

/**
 * 开关项：key 支撑读写（默认值 defaultValue），复刻 SwitchPreference。
 * onCheckedChange 在持久化后回调（承接原 OnSharedPreferenceChangeListener 副作用）。
 * checked 非空时开关态由外部驱动（如 webService 由 WEB_SERVICE 事件回填），否则内部随 prefs。
 */
fun LazyListScope.switchPreference(
    prefKey: String,
    title: String,
    summary: String? = null,
    defaultValue: Boolean = false,
    icon: Painter? = null,
    enabled: Boolean = true,
    isBottomBackground: Boolean = false,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) = item {
    // 替代 LocalContext.current + context.getPrefBoolean: commonMain 走 PreferenceProviders 单例
    val pref = PreferenceProviders.get()
    var internalChecked by rememberPrefState(prefKey) { it.getBoolean(prefKey, defaultValue) }
    val isChecked = checked ?: internalChecked
    val toggle = {
        val v = !isChecked
        internalChecked = v
        // 替代 context.putPrefBoolean: 走 PreferenceProviders 单例
        pref.putBoolean(prefKey, v)
        onCheckedChange?.invoke(v)
    }
    PreferenceRow(
        title = title,
        summary = summary,
        icon = icon,
        enabled = enabled,
        isBottomBackground = isBottomBackground,
        onClick = { toggle() },
        onLongClick = onLongClick,
        widget = {
            AppSwitch(checked = isChecked, onCheckedChange = { toggle() }, enabled = enabled)
        },
    )
}

/** 输入项：点击弹 AppAlertDialog + 输入框，复刻 EditTextPreference */
fun LazyListScope.editTextPreference(
    prefKey: String,
    title: String,
    summary: String? = null,
    defaultValue: String = "",
    icon: Painter? = null,
    enabled: Boolean = true,
    isBottomBackground: Boolean = false,
    isPassword: Boolean = false,
    // 对话框宽度占窗口宽度的比例 (默认 1f 占满, 备份相关对话框传 0.8f 避免桌面端占满难看)
    widthFraction: Float = 1f,
    onValueChange: ((String) -> Unit)? = null,
) = item {
    // 替代 LocalContext.current: commonMain 走 PreferenceProviders 单例
    val pref = PreferenceProviders.get()
    var showDialog by remember { mutableStateOf(false) }
    PreferenceRow(
        title = title,
        summary = summary,
        icon = icon,
        enabled = enabled,
        isBottomBackground = isBottomBackground,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        var text by remember { mutableStateOf(pref.getStringOrNull(prefKey) ?: defaultValue) }
        AppAlertDialog(
            onDismissRequest = { showDialog = false },
            title = title,
            widthFraction = widthFraction,
            okButton = AlertButton(
                // 替代 stringResource(R.string.ok): commonMain 走 stringResource(Res.string.ok)
                text = stringResource(Res.string.ok),
                onClick = {
                    // 替代 context.putPrefString: 走 PreferenceProviders 单例
                    pref.putString(prefKey, text)
                    onValueChange?.invoke(text)
                },
            ),
            // 替代 stringResource(R.string.cancel): commonMain 走 stringResource(Res.string.cancel)
            cancelButton = AlertButton(text = stringResource(Res.string.cancel)),
        ) {
            AppUnderlineTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                visualTransformation = if (isPassword) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 单选项：entries/values 语义，行尾 fillet 文本显示当前 entry，点击弹单选。复刻 NameListPreference */
fun LazyListScope.listPreference(
    prefKey: String,
    title: String,
    entries: List<String>,
    values: List<String>,
    defaultValue: String = values.firstOrNull() ?: "",
    summary: String? = null,
    icon: Painter? = null,
    enabled: Boolean = true,
    isBottomBackground: Boolean = false,
    onValueChange: ((String) -> Unit)? = null,
) = item {
    // 替代 LocalContext.current: commonMain 走 PreferenceProviders 单例
    val pref = PreferenceProviders.get()
    var value by rememberPrefState(prefKey) { it.getStringOrNull(prefKey) ?: defaultValue }
    var showDialog by remember { mutableStateOf(false) }
    val entry = entries.getOrNull(values.indexOf(value)) ?: ""
    PreferenceRow(
        title = title,
        summary = summary,
        icon = icon,
        enabled = enabled,
        isBottomBackground = isBottomBackground,
        onClick = { showDialog = true },
        widget = { FilletText(entry, isBottomBackground) },
    )
    if (showDialog) {
        SingleChoiceDialog(
            title = title,
            entries = entries,
            selectedIndex = values.indexOf(value),
            onDismissRequest = { showDialog = false },
            onSelected = { i ->
                val v = values[i]
                value = v
                // 替代 context.putPrefString: 走 PreferenceProviders 单例
                pref.putString(prefKey, v)
                onValueChange?.invoke(v)
            },
        )
    }
}

/** 图标单选项：行尾图标预览，点击弹带图标的单选。复刻 IconListPreference */
fun LazyListScope.iconListPreference(
    prefKey: String,
    title: String,
    entries: List<String>,
    values: List<String>,
    icons: List<Painter?>,
    defaultValue: String = values.firstOrNull() ?: "",
    summary: String? = null,
    enabled: Boolean = true,
    isBottomBackground: Boolean = false,
    onValueChange: ((String) -> Unit)? = null,
) = item {
    // 替代 LocalContext.current: commonMain 走 PreferenceProviders 单例
    val pref = PreferenceProviders.get()
    var value by rememberPrefState(prefKey) { it.getStringOrNull(prefKey) ?: defaultValue }
    var showDialog by remember { mutableStateOf(false) }
    val index = values.indexOf(value)
    PreferenceRow(
        title = title,
        summary = summary,
        enabled = enabled,
        isBottomBackground = isBottomBackground,
        onClick = { showDialog = true },
        widget = {
            icons.getOrNull(index)?.let {
                Icon(it, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(48.dp))
            }
        },
    )
    if (showDialog) {
        SingleChoiceDialog(
            title = title,
            entries = entries,
            selectedIndex = index,
            leading = { i ->
                icons.getOrNull(i)?.let {
                    Icon(it, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(32.dp))
                }
            },
            onDismissRequest = { showDialog = false },
            onSelected = { i ->
                val v = values[i]
                value = v
                // 替代 context.putPrefString: 走 PreferenceProviders 单例
                pref.putString(prefKey, v)
                onValueChange?.invoke(v)
            },
        )
    }
}

/** 行骨架：复刻 view_preference（透明底 + 图标 accent + 双行文本 + 行尾 widget；按压用 Compose 默认指示） */
@Composable
internal fun PreferenceRow(
    title: String,
    summary: String? = null,
    icon: Painter? = null,
    enabled: Boolean = true,
    isBottomBackground: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    widget: (@Composable () -> Unit)? = null,
) {
    val (titleColor, summaryColor) = prefTextColors(isBottomBackground)
    val clickModifier = if ((onClick != null || onLongClick != null) && enabled) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    } else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .then(
                if (enabled) Modifier else Modifier
                    .alpha(0.38f)
                    .semantics { disabled() }
            )
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = AppTheme.colors.accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!summary.isNullOrEmpty()) {
                Text(summary, color = summaryColor, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (widget != null) {
            // view_preference: widget 无前置间隙, layout_marginEnd 8dp
            Box(Modifier.padding(end = 8.dp), contentAlignment = Alignment.Center) { widget() }
        }
    }
}

/** 行尾 fillet 文本（复刻 item_fillet_text：secondaryText 字、圆角底） */
@Composable
private fun FilletText(text: String, isBottomBackground: Boolean) {
    if (text.isEmpty()) return
    val colors = AppTheme.colors
    val textColor = if (isBottomBackground) prefTextColors(true).first else colors.secondaryText
    val btnBg = if (colors.isDark) Color(0x14e0e0e0) else Color(0x100e0e0e)
    Box(
        Modifier
            // 复刻 selector_fillet_btn_bg 的 4dp inset：底色内缩，外围留白不变
            .padding(4.dp)
            .clip(DesignTokens.shapeDefault)
            .background(btnBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, color = textColor, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 单选弹窗（radio 列表），供 list/iconList 复用 */
@Composable
private fun SingleChoiceDialog(
    title: String,
    entries: List<String>,
    selectedIndex: Int,
    onDismissRequest: () -> Unit,
    onSelected: (Int) -> Unit,
    leading: (@Composable (Int) -> Unit)? = null,
) {
    val colors = AppTheme.colors
    AppAlertDialog(onDismissRequest = onDismissRequest, title = title) {
        Column(Modifier.selectableGroup()) {
            entries.forEachIndexed { i, entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = i == selectedIndex,
                            onClick = { onSelected(i); onDismissRequest() },
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(
                        selected = i == selectedIndex,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = colors.accent,
                            unselectedColor = colors.secondaryText,
                        ),
                    )
                    leading?.invoke(i)
                    Text(entry, color = colors.primaryText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 文本颜色：普通项使用 AppTheme 的 primaryText/summaryText 语义色，值对齐
 * origin/quickjs 的 primaryText/tv_text_summary；isBottomBackground 复刻
 * getPrimaryTextColor/getSecondaryTextColor（由底栏色亮度反推）。
 */
@Composable
internal fun prefTextColors(isBottomBackground: Boolean): Pair<Color, Color> {
    return if (isBottomBackground) {
        val light = ColorUtils.isColorLight(AppTheme.colors.bottomBackground.toArgb())
        val title = if (light) Color(0xDE000000) else Color.White
        val summary = if (light) Color(0x8A000000) else Color.White
        title to summary
    } else {
        AppTheme.colors.primaryText to AppTheme.colors.summaryText
    }
}
