package io.legado.app.ui.compose.component

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.material.TextFieldColors
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TextFieldDefaults.indicatorLine
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * Material Design 2 下划线输入框 (形态依据: 原版 Widget.Design.TextInputLayout boxBackgroundMode=none)。
 *
 * - 透明容器无边框, 仅底部下划线: 未聚焦 1dp controlNormal / 聚焦 2dp accent (indicatorLine 默认粗细)
 * - label 浮动到输入区上方; 配色状态化: 未聚焦/聚焦均为 accent
 * - 文字水平起始 4dp (DecorationBox contentPadding 收窄; M2 TextField 默认 16dp 是 filled 容器所需,
 *   下划线形态下会与周边 4dp 对齐的布局明显错位, 如 BookInfoEditScreen 封面按钮行)
 * - 高度内容推导: 最小高度 = 顶留白 + 固定行高 (fontSize*1.5) + 底部 4dp, 单行字段贴合内容
 *   (对齐 CodeTextField, 消除 56dp minHeight 死区, 文本不再悬浮于线上方)
 * - 行高固定 fontSize*1.5 (16sp → 24sp), 文本底恒距指示线 4dp —— 与 CodeTextField 几何统一
 *
 * 配色适配 Arco Design 主题: 聚焦色 = AppTheme.colors.accent (arcoblue-6 #165DFF),
 * 错误色 = Arco danger (#F53F3F), 直接用 AppTheme.colors 注入 TextFieldDefaults.textFieldColors,
 * 不走 MaterialTheme.colors.primary (取色更明确)。
 *
 * 注意: compose.material (MD2) 在 CMP 1.7+ 标记 deprecated 但保留可用 (见 shared/build.gradle)。
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
    focusRequester: FocusRequester? = null,
) {
    AppTextFieldImpl(
        modifier = modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        isError = isError,
        errorMessage = errorMessage,
    ) {
        val colors = AppFieldColors
        val interactionSource = remember { MutableInteractionSource() }
        val textColor = textStyle.color.takeOrElse { colors.textColor(enabled).value }
        // 输入字号统一 16sp: 调用点显式传 textStyle 但未指定 fontSize 时归一化 (否则渲染回落 14sp)
        val effectiveFontSize = textStyle.fontSize.takeOrElse { 16.sp }
        // 固定行高 fontSize*1.5 (对齐 CodeTextField): 单行/多行垂直几何统一, 行高不随字体默认漂移
        val effectiveTextStyle = textStyle.copy(
            fontSize = effectiveFontSize,
            lineHeight = effectiveFontSize * 1.5f,
        )
        // 新版 TextFieldState API (受控双同步): 用户编辑 → onValueChange;
        // 外部 value → state (程序化修改)。旧版 value/onValueChange 契约保持不变。
        // state 用初始值初始化: snapshotFlow 收集即发首帧, 若 state 初始为空会把空串
        // 推给 onValueChange 清空外部值 (书源编辑界面初始赋值后字段被清空的问题)。
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
        // 呈现变换实例稳定 (remember): BasicTextField 内部按 (state, codepointTransformation,
        // outputTransformation) remember TransformedTextFieldState, 实例一变就重建 TextLayoutState
        // 缓存 → 全文重变换+重布局。内联新建实例会让每次重组都全量重算 (无变换字段也中招);
        // VisualTransformation.None (单例) 时传 null, 字段走无变换路径。
        val outputTransformation = remember(visualTransformation) {
            if (visualTransformation === VisualTransformation.None) {
                null
            } else {
                visualTransformation.asOutputTransformation()
            }
        }
        BasicTextField(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .indicatorLine(enabled, isError, interactionSource, colors)
                // 最小高度内容推导 (对齐 CodeTextField): 单行字段贴合内容, 消除 56dp 死区
                .defaultMinSize(
                    minWidth = TextFieldDefaults.MinWidth,
                    minHeight = appFieldDefaultMinHeight(
                        label != null,
                        effectiveFontSize,
                    ),
                ),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = effectiveTextStyle.copy(color = textColor),
            keyboardOptions = keyboardOptions,
            onKeyboardAction = keyboardActions.toKeyboardActionHandler(keyboardOptions.imeAction),
            lineLimits = if (singleLine) {
                TextFieldLineLimits.SingleLine
            } else {
                TextFieldLineLimits.MultiLine(minLines, maxLines)
            },
            outputTransformation = outputTransformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.cursorColor(isError).value),
            decorator = TextFieldDecorator { innerTextField ->
                AppDecorationBox(
                    text = value,
                    innerTextField = innerTextField,
                    enabled = enabled,
                    singleLine = singleLine,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    isError = isError,
                    label = label,
                    placeholder = placeholder,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    colors = colors,
                )
            },
        )
    }
}

/**
 * [AppTextField] 的 TextFieldValue 重载: 用于需要保留 IME composition / 选区 (undo/redo) 的场景
 * (如 ReplaceEditScreen FormField)。视觉与 String 重载一致。
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun AppTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
    focusRequester: FocusRequester? = null,
) {
    AppTextFieldImpl(
        modifier = modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        isError = isError,
        errorMessage = errorMessage,
    ) {
        val colors = AppFieldColors
        val interactionSource = remember { MutableInteractionSource() }
        val textColor = textStyle.color.takeOrElse { colors.textColor(enabled).value }
        // 输入字号统一 16sp: 调用点显式传 textStyle 但未指定 fontSize 时归一化 (否则渲染回落 14sp)
        val effectiveFontSize = textStyle.fontSize.takeOrElse { 16.sp }
        // 固定行高 fontSize*1.5 (对齐 CodeTextField): 单行/多行垂直几何统一, 行高不随字体默认漂移
        val effectiveTextStyle = textStyle.copy(
            fontSize = effectiveFontSize,
            lineHeight = effectiveFontSize * 1.5f,
        )
        // 新版 TextFieldState API: TextFieldValue 契约 (文本+选区) 经双同步保留。
        // state 用初始文本+选区初始化 (见 String 重载注释: 防首帧快照把空串推给外部)。
        val state = remember { TextFieldState(value.text, value.selection) }
        val currentValue by rememberUpdatedState(value)
        val currentOnValueChange by rememberUpdatedState(onValueChange)
        LaunchedEffect(state) {
            snapshotFlow { state.text.toString() to state.selection }
                .collect { (text, selection) ->
                    val newValue = TextFieldValue(text, selection)
                    if (newValue != currentValue) currentOnValueChange(newValue)
                }
        }
        LaunchedEffect(state, value) {
            if (state.text.toString() != value.text || state.selection != value.selection) {
                state.edit {
                    replace(0, length, value.text)
                    selection = value.selection
                }
            }
        }
        // 呈现变换实例稳定 (remember): 见 String 重载注释 (None → null 走无变换路径)
        val outputTransformation = remember(visualTransformation) {
            if (visualTransformation === VisualTransformation.None) {
                null
            } else {
                visualTransformation.asOutputTransformation()
            }
        }
        BasicTextField(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .indicatorLine(enabled, isError, interactionSource, colors)
                // 最小高度内容推导 (对齐 CodeTextField): 单行字段贴合内容, 消除 56dp 死区
                .defaultMinSize(
                    minWidth = TextFieldDefaults.MinWidth,
                    minHeight = appFieldDefaultMinHeight(
                        label != null,
                        effectiveFontSize,
                    ),
                ),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = effectiveTextStyle.copy(color = textColor),
            keyboardOptions = keyboardOptions,
            onKeyboardAction = keyboardActions.toKeyboardActionHandler(keyboardOptions.imeAction),
            lineLimits = if (singleLine) {
                TextFieldLineLimits.SingleLine
            } else {
                TextFieldLineLimits.MultiLine(minLines, maxLines)
            },
            outputTransformation = outputTransformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.cursorColor(isError).value),
            decorator = TextFieldDecorator { innerTextField ->
                AppDecorationBox(
                    text = value.text,
                    innerTextField = innerTextField,
                    enabled = enabled,
                    singleLine = singleLine,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    isError = isError,
                    label = label,
                    placeholder = placeholder,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    colors = colors,
                )
            },
        )
    }
}

// ===== 下划线输入框统一几何 (与 component/code/CodeTextField 共用同一套常量) =====

/** 水平内容留白: 下划线形态收窄至 4dp (M2 filled 默认 16dp 是容器形态所需) */
internal val TextFieldHorizontalPadding = 4.dp

/** 无 label 时文本顶部留白 (M2 textFieldWithoutLabelPadding 默认 top = TextFieldPadding = 16dp) */
internal val TextFieldTopPadding = 16.dp

/** 有 label 时 label 基线距组件顶 (M2 FirstBaselineOffset = 20dp) */
internal val TextFieldFirstBaselineOffset = 20.dp

/** label 基线到文本顶的间距 (M2 内部 TextFieldTopPadding = 2dp, label 浮动后文本顶 = 基线 + 2dp) */
internal val TextFieldLabelToText = 2.dp

/**
 * 文本底到指示线的间距 4dp (原版 EditText wrap_content 底部 inset 约 2-4dp)。
 * 顶对齐内容下 bottom 即"文本-下划线"距离。
 */
internal val TextFieldBottomInset = 4.dp

/**
 * 输入框默认最小高度 = 顶留白 + 固定行高 (fontSize*1.5) + 底部 4dp:
 * 单行字段高度正好贴合内容, 消除 minHeight 死区 —— 死区是"单行文本离下划线太远"的根源
 * (56dp 最小高 + 顶对齐下, 文本下方空出 56-(顶留白+行高+4dp) ≈ 19dp)。
 * 多行时内容自然超过该值, 高度随内容增长, 文本底距指示线恒为 [TextFieldBottomInset]。
 * (fontScale=1 时 sp 与 dp 等价; 非 1 时此项仅为下限, 内容更高则自动撑开)
 */
internal fun appFieldDefaultMinHeight(hasLabel: Boolean, fontSize: TextUnit): Dp {
    val topSpace =
        if (hasLabel) TextFieldFirstBaselineOffset + TextFieldLabelToText else TextFieldTopPadding
    val lineHeightDp = (if (fontSize.isSp) fontSize.value else 14f) * 1.5f
    return topSpace + lineHeightDp.dp + TextFieldBottomInset
}

/**
 * MD2 filled 系 TextFieldDefaults.textFieldColors + backgroundColor=Transparent = 下划线形态取色。
 * 下划线 = indicator 系参数 (非 outlined 的 border 系); 单一 cursorColor + errorCursorColor。
 * internal: 供 component/code 的 CodeTextField 复用同一套取色。
 */
internal val AppFieldColors: TextFieldColors
    @Composable get() = AppTheme.colors.let { colors ->
        TextFieldDefaults.textFieldColors(
            textColor = colors.primaryText,
            disabledTextColor = colors.textDisabled,
            backgroundColor = Color.Transparent, // boxBackgroundMode=none: 容器透明无填充
            cursorColor = colors.accent,
            errorCursorColor = DesignTokens.arcoDanger,
            focusedIndicatorColor = colors.accent,
            unfocusedIndicatorColor = colors.controlNormal,
            disabledIndicatorColor = colors.textDisabled,
            errorIndicatorColor = DesignTokens.arcoDanger,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.accent,
            disabledLabelColor = colors.textDisabled,
            errorLabelColor = DesignTokens.arcoDanger,
            placeholderColor = colors.controlNormal,
            disabledPlaceholderColor = colors.textDisabled,
        )
    }

/** 两个重载共享的装饰盒: label 浮动/占位符/图标排版走 M2 默认, 仅水平 contentPadding 收窄至 4dp。
 *  [contentPadding] 可覆写: CodeTextField 盒模式传入含 16dp 盒内边距的 padding */
@OptIn(ExperimentalMaterialApi::class)
@Composable
internal fun AppDecorationBox(
    text: String,
    innerTextField: @Composable () -> Unit,
    enabled: Boolean,
    singleLine: Boolean,
    visualTransformation: VisualTransformation,
    interactionSource: InteractionSource,
    isError: Boolean,
    label: String?,
    placeholder: String?,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    colors: TextFieldColors,
    // 垂直 top 走 M2 默认 (无 label 16dp / 有 label FirstBaselineOffset+2dp), bottom 固定 4dp
    // (TextFieldBottomInset), 水平 4dp (TextFieldHorizontalPadding) —— 对齐 CodeTextField,
    // 文本底恒距指示线 4dp, 与 M2 默认 (bottom 10dp / 水平 16dp) 不同。
    contentPadding: PaddingValues = if (label == null) {
        TextFieldDefaults.textFieldWithoutLabelPadding(
            start = TextFieldHorizontalPadding,
            end = TextFieldHorizontalPadding,
            bottom = TextFieldBottomInset,
        )
    } else {
        TextFieldDefaults.textFieldWithLabelPadding(
            start = TextFieldHorizontalPadding,
            end = TextFieldHorizontalPadding,
            bottom = TextFieldBottomInset,
        )
    },
) {
    TextFieldDefaults.TextFieldDecorationBox(
        value = text,
        innerTextField = innerTextField,
        enabled = enabled,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        isError = isError,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        colors = colors,
        contentPadding = contentPadding,
    )
}

/** 两个重载共享的容器: 下划线输入框 + 线下错误文案 */
@Composable
internal fun AppTextFieldImpl(
    modifier: Modifier,
    isError: Boolean,
    errorMessage: String?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = AppTheme.colors.accent,
            backgroundColor = AppTheme.colors.accent.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier) {
            content()
            // 错误文案: 下划线下方 12sp danger 色 (MD2 不自带 errorText, 需手动添加); 4dp 对齐文字起始
            if (isError && !errorMessage.isNullOrEmpty()) {
                Text(
                    text = errorMessage,
                    color = DesignTokens.arcoDanger,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

/**
 * 旧 [KeyboardActions] → 新版 [KeyboardActionHandler] 映射。
 *
 * 新版 onKeyboardAction 回调不带 ImeAction (仅给 performDefaultAction), 而旧 KeyboardActions
 * 按 ImeAction 分发 (onDone/onSearch/...)。字段已配置 keyboardOptions.imeAction, 实际触发的
 * 就是该 action, 故按 imeAction 选对应回调 (无对应回调则返回 null 走框架默认行为;
 * KeyboardActions(onAny=...) 工厂会把同一 lambda 填进全部六个回调, 故无需单独回落 onAny)。
 * scope.defaultKeyboardAction() 映射到新回调的 performDefaultAction (即当前 action 的默认行为)。
 */
internal fun KeyboardActions.toKeyboardActionHandler(imeAction: ImeAction): KeyboardActionHandler? {
    val handler =
        when (imeAction) {
            ImeAction.Done -> onDone
            ImeAction.Go -> onGo
            ImeAction.Next -> onNext
            ImeAction.Previous -> onPrevious
            ImeAction.Search -> onSearch
            ImeAction.Send -> onSend
            else -> null
        } ?: return null
    return KeyboardActionHandler { performDefaultAction ->
        val scope = object : KeyboardActionScope {
            override fun defaultKeyboardAction(imeAction: ImeAction) {
                performDefaultAction()
            }
        }
        handler(scope)
    }
}

/**
 * 旧 [VisualTransformation] → 新版 [OutputTransformation] 适配。
 *
 * 项目内使用场景均为等长变换 (PasswordVisualTransformation 掩码 1:1), 直接整体替换缓冲区文本,
 * 选区/光标位置不变, 无需偏移映射。非等长变换 (增删字符) 不适用, 需另行实现 offsetMapping。
 */
internal fun VisualTransformation.asOutputTransformation(): OutputTransformation {
    val visualTransformation = this
    return OutputTransformation {
        val original = asCharSequence().toString()
        val transformedText = visualTransformation.filter(AnnotatedString(original)).text
        if (transformedText.text != original) {
            replace(0, length, transformedText)
        }
    }
}

/**
 * 旧 [VisualTransformation] (纯样式着色, 文本不变) → 新版 [OutputTransformation] 适配。
 *
 * 缓冲区是字符存储, replace 注入 AnnotatedString 会丢弃 span 样式; 正确做法是把
 * 变换产出的 SpanStyle 区间逐段 [TextFieldBuffer.addStyle] 到呈现缓冲区
 * (布局层合并 outputAnnotations 渲染)。语法高亮/查找高亮等"只上色不改字"场景用这个;
 * 文本本身变化的变换 (密码掩码) 用 [asOutputTransformation]。
 */
internal fun VisualTransformation.asHighlightOutputTransformation(): OutputTransformation =
    OutputTransformation {
        val original = asCharSequence().toString()
        val annotated = filter(AnnotatedString(original)).text
        if (annotated.text != original) {
            replace(0, length, annotated.text)
        }
        annotated.spanStyles.forEach { span ->
            addStyle(span.item, span.start.coerceIn(0, length), span.end.coerceIn(0, length))
        }
    }
