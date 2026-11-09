package io.legado.app.ui.book.manga.extension

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.ReplaceSource
import android.text.SpanStore
import android.text.Spanned
import android.text.TextEditable
import android.text.TextWatcher
import android.text.applyInputFilters
import android.text.checkTextRange
import android.text.method.PasswordTransformationMethod
import android.text.method.SingleLineTransformationMethod
import android.text.method.TransformationMethod
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.legado.app.constant.AppLog
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

/**
 * 插件文本项编辑弹窗 (桌面兼容层活适配): 会话持本次实际配置对象, 插件的
 * `OnBindEditTextListener` 跑在适配输入框上。
 *
 * [TextFieldState] 是文本与选区的唯一活真源; `DesktopEditable` 只把插件对 Editable 的调用
 * 路由到库状态。文本修改统一在一次有效编辑事务内完成 过滤 → before → 修改 → on → after,
 * 事务内继续改 Editable 是平台允许的 after 重入; 事务外程序化修改同样装入一次库 edit。
 *
 * 提交先 `callChangeListener` 通过才落值 (控件与模型操作在主线程; 偏好持久化边界冻结待另批),
 * 取消不保存。资源 id 形式的提示语无插件资源系统可解析 (冻结环节)。
 */
@Composable
fun DesktopMangaPrefTextEditor(request: MangaPrefTextEditorRequest, context: Context) {
    val editorSession = request.session as MangaPrefTextEditorSession
    // 平台编辑器的状态与作用域整体属于本次会话: 换会话即整体弃置, 旧回调不会落到新会话
    key(editorSession) {
        DesktopMangaPrefTextEditorSession(request, editorSession, context)
    }
}

@Composable
private fun DesktopMangaPrefTextEditorSession(
    request: MangaPrefTextEditorRequest,
    editorSession: MangaPrefTextEditorSession,
    context: Context,
) {
    // 控件组与插件绑定在主线程完成; 插件监听/ABI 故障 (含 LinkageError) 如实记录并关闭
    var widgets by remember { mutableStateOf<DesktopPrefEditWidgets?>(null) }
    LaunchedEffect(editorSession, context) {
        widgets = try {
            DesktopPrefEditWidgets.create(context, editorSession)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("插件配置输入绑定失败", e)
            request.onDismiss()
            null
        }
    }
    // 库内撤销/重做等直接文字变更不跑输入变换: 观察库实际文字, 补上未通知过的监听与区间调整
    LaunchedEffect(widgets) {
        val current = widgets ?: return@LaunchedEffect
        val editText = current.editText
        var closed = false
        // 同时观察库真实文本与待清历史标记: 提交后文字与旧值相同也要让标记收口
        snapshotFlow { current.state.text.toString() to editText.pendingUndoHistoryClear }
            .collect { (text, _) ->
                if (closed) return@collect
                try {
                    editText.onLibraryTextChanged(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 新增通知通路的故障边界: 记日志并关闭本次编辑会话, 不新增可见错误
                    closed = true
                    AppLog.put("插件配置文本变更通知失败", e)
                    request.onDismiss()
                }
            }
    }
    widgets?.let { w ->
        DisposableEffect(w) {
            onDispose { w.release() }
        }
        val scope = rememberCoroutineScope()
        val maskedTransformation = remember { PasswordVisualTransformation() }
        val okText = stringResource(Res.string.ok)
        val cancelText = stringResource(Res.string.cancel)
        AppAlertDialog(
            onDismissRequest = request.onDismiss,
            okButton = AlertButton(
                text = okText,
                enabled = w.confirmEnabled.value,
                // 提交实际结束后才关闭 (默认立即关会取消未完成的提交)
                dismissOnClick = false,
            ) {
                val value = w.state.text.toString()
                scope.launch {
                    // 业务拦截与真实故障区分: 故障记录日志后关闭, 不伪装成输入被拒
                    val committed = try {
                        editorSession.commit(value)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        AppLog.put("插件配置保存失败", e)
                        null
                    }
                    if (committed != null) request.onSettled(committed) else request.onDismiss()
                }
            },
            cancelButton = AlertButton(text = cancelText) {},
        ) {
            // 行数/提示/密码变换直读输入框的活声明 (插件监听后续修改即时反映)
            val minLines = w.editText.getMinLines().coerceAtLeast(1)
            AppTextField(
                state = w.state,
                singleLine = w.editText.isSingleLine(),
                minLines = minLines,
                maxLines = maxOf(w.editText.getMaxLines(), minLines),
                placeholder = w.editText.getHint()?.toString(),
                visualTransformation = if (
                    w.editText.getTransformationMethod() is PasswordTransformationMethod
                ) {
                    maskedTransformation
                } else {
                    VisualTransformation.None
                },
                inputTransformation = w.editText.inputTransformation,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 桌面活适配控件组: 假确认键 + 适配输入框放进同一查找树; 组随弹窗弃置。 */
internal class DesktopPrefEditWidgets private constructor(
    val state: TextFieldState,
    val editText: DesktopPrefEditText,
    val confirmEnabled: MutableState<Boolean>,
) {

    private var confirmButton: Button? = null

    /** 解除登记的监听与确认键回调 (控件随组合弃置)。 */
    fun release() {
        editText.release()
        confirmButton?.onEnabledChanged = null
    }

    companion object {
        fun create(context: Context, session: MangaPrefTextEditorSession): DesktopPrefEditWidgets {
            val confirmEnabled = mutableStateOf(true)
            val confirmButton = Button(context).apply {
                // 插件动态校验按根视图查找的确认键 id, 初始可用态为平台默认
                id = android.R.id.button1
                onEnabledChanged = { enabled -> confirmEnabled.value = enabled }
            }
            val root = LinearLayout(context)
            root.addView(confirmButton)
            val editText = DesktopPrefEditText(context, TextFieldState(""))
            root.addView(editText)
            // 完整同树后绑定: 填当前值 → 光标到末尾 → 插件监听器
            editText.setText(session.initialText())
            editText.setSelection(editText.length())
            session.onBindEditText(editText)
            return DesktopPrefEditWidgets(
                state = editText.state,
                editText = editText,
                confirmEnabled = confirmEnabled,
            ).also { it.confirmButton = confirmButton }
        }
    }
}

/**
 * 桌面活适配输入框: 插件对 EditText/Editable 的 SDK 调用适配到 [TextFieldState]。
 * 声明面 (单行/行数/提示/输入类型/变换) 是私有状态, 真实 getter 与 Compose 读同一份值;
 * 文本修改合同见 [applyReplace]/[replaceWhole]。
 */
@OptIn(ExperimentalFoundationApi::class)
internal class DesktopPrefEditText(context: Context, val state: TextFieldState) : EditText(context) {

    private var hintState: CharSequence? by mutableStateOf(null)

    private var singleLineState: Boolean by mutableStateOf(false)

    /** TextView 默认 mMinimum = 0 (无行数下限)。 */
    private var minLinesState: Int by mutableStateOf(0)

    /** TextView 默认 mMaximum = Int.MAX_VALUE (按行计)。 */
    private var maxLinesState: Int by mutableStateOf(Int.MAX_VALUE)

    private var inputTypeState: Int by mutableStateOf(InputType.TYPE_NULL)

    private var transformationState: TransformationMethod? by mutableStateOf(null)

    val inputTransformation: InputTransformation = DesktopFilterTransformation(this)

    private val filterList = ArrayList<InputFilter>()

    private val watcherList = ArrayList<TextWatcher>()

    /** 事务期有效的库缓冲 (transformInput / state.edit 内); 文本修改只落在它上面。 */
    private var activeBuffer: TextFieldBuffer? = null

    /** 已向监听通知过的库文本 (只用于推导未通知变更与旧值参数, 不参与编辑/呈现/提交)。 */
    private var notifiedText: String = ""

    /**
     * 事务内 setText 登记的窄标记: 该次库提交落库后清一次历史 (只服务本次提交, 不作全局开关)。
     * 为快照状态, 供观察器在"提交后文字与旧值相同"时也能收口。
     */
    var pendingUndoHistoryClear: Boolean by mutableStateOf(false)
        private set

    /** 事务内监听显式改过选区: 提交时不再用原提案选区覆盖。 */
    var selectionEditedByListener: Boolean = false
        private set

    /** 活文本读取 (事务内读库缓冲, 事务外读库状态)。 */
    val readText: CharSequence
        get() = activeBuffer?.asCharSequence() ?: state.text

    /** 适配器可变文本面 (插件 getText() 拿到的 Editable, 跨 edit 持续)。 */
    val editableRef: DesktopEditable = DesktopEditable(this)

    // ===== TextView getter/setter 面 (真实描述符, 私有状态承载) =====

    override fun getHint(): CharSequence? = hintState

    override fun isSingleLine(): Boolean = singleLineState

    override fun getInputType(): Int = inputTypeState

    override fun getFilters(): Array<InputFilter> = filterList.toTypedArray()

    override fun getMinLines(): Int = minLinesState

    override fun getMaxLines(): Int = maxLinesState

    override fun getTransformationMethod(): TransformationMethod? = transformationState

    override fun setHint(hint: CharSequence?) {
        hintState = hint
    }

    override fun setHint(resId: Int) {
        // 资源 id 提示语需插件资源系统解析, 兼容层无该机制
    }

    override fun setSingleLine() {
        setSingleLine(true)
    }

    override fun setSingleLine(singleLine: Boolean) {
        setInputTypeSingleLine(singleLine)
        applySingleLine(singleLine, applyTransformation = true)
    }

    override fun setInputType(type: Int) {
        val wasPassword = isPasswordInputType(inputTypeState)
        val wasVisiblePassword = isVisiblePasswordInputType(inputTypeState)
        inputTypeState = type
        val isPassword = isPasswordInputType(type)
        val isVisiblePassword = isVisiblePasswordInputType(type)
        val passwordTransformation = PasswordTransformationMethod.getInstance()
        var forceUpdate = false
        when {
            isPassword -> setTransformationMethod(passwordTransformation)
            isVisiblePassword -> if (transformationState === passwordTransformation) forceUpdate = true
            wasPassword || wasVisiblePassword ->
                if (transformationState === passwordTransformation) forceUpdate = true
        }
        val singleLine = !isMultilineInputType(type)
        // 单行模式变化或退出/进入可见密码时都要重设行数与变换; 密码态由密码变换承担
        if (singleLineState != singleLine || forceUpdate) {
            applySingleLine(singleLine, applyTransformation = !isPassword)
        }
    }

    override fun setFilters(filters: Array<out InputFilter>?) {
        val newFilters = filters ?: throw IllegalArgumentException()
        filterList.clear()
        filterList.addAll(newFilters)
    }

    override fun setTransformationMethod(method: TransformationMethod?) {
        transformationState = method
    }

    override fun setMinLines(minLines: Int) {
        minLinesState = minLines
    }

    override fun setMaxLines(maxLines: Int) {
        maxLinesState = maxLines
    }

    override fun setLines(lines: Int) {
        minLinesState = lines
        maxLinesState = lines
    }

    override fun addTextChangedListener(watcher: TextWatcher?) {
        watcher?.let(watcherList::add)
    }

    override fun removeTextChangedListener(watcher: TextWatcher?) {
        watcherList.remove(watcher)
    }

    // ===== 文本面 (库状态真源; 修改经主线程编辑事务) =====

    override fun getText(): Editable = editableRef

    override fun length(): Int = readText.length

    override fun getSelectionStart(): Int = currentSelection().start

    override fun getSelectionEnd(): Int = currentSelection().end

    override fun setSelection(index: Int) {
        applySelection(TextRange(index))
    }

    override fun setSelection(start: Int, stop: Int) {
        applySelection(TextRange(start, stop))
    }

    private fun currentSelection(): TextRange = activeBuffer?.selection ?: state.selection

    private fun applySelection(range: TextRange) {
        checkTextRange("setSelection", range.min, range.max, length())
        val buffer = activeBuffer
        if (buffer != null) {
            buffer.selection = range
            selectionEditedByListener = true
        } else {
            state.edit { selection = range }
        }
    }

    override fun setText(value: CharSequence?) {
        // TextView.setText 合同: 以空 dest 送过滤链, 然后整段替换 (旧 span 不随新文本保留)
        val text = value ?: ""
        val filtered = applyInputFilters(
            filterList.toTypedArray(),
            text,
            0,
            text.length,
            EMPTY_SPANNED,
            0,
            0,
        )
        // 来源可能就是这个输入框自身: 在清旧 span/改文字前取下文字与 span
        val source = ReplaceSource.capture(filtered.text, 0, filtered.text.length)
        val insideTransaction = activeBuffer != null
        withEditTransaction { replaceWhole(source) }
        if (insideTransaction) {
            // 对齐 TextView.setText: 创建新 Editable 前 mEditor.forgetUndoRedo; 由该次提交的
            // 收口点 (state.edit 返回或提交后的观察) 执行, 不在编辑中途提前清
            pendingUndoHistoryClear = true
        } else {
            // setText 自己开启的编辑已提交返回, 立即按 SDK 口径清库历史
            clearUndoHistory()
        }
    }

    /**
     * 在事务内执行一次平台 `Editable.replace` 合同: 先校验目标与来源区间, 过滤链以变更前文本
     * 为 dest; 改动前取下来源文字与 span, before 时文字仍旧; 按旧范围调整已有标记后真正修改
     * 缓冲, 再按新文本复制来源 span, 最后 on/after。返回时文本/选区已落到库缓冲。
     */
    fun applyReplace(
        start: Int,
        end: Int,
        source: CharSequence,
        startOfSource: Int,
        endOfSource: Int,
    ) {
        val buffer = checkNotNull(activeBuffer) { "文本修改必须运行在编辑事务内" }
        checkTextRange("replace", start, end, buffer.length)
        checkTextRange("replace source", startOfSource, endOfSource, source.length)
        val filtered = applyInputFilters(
            filterList.toTypedArray(),
            source,
            startOfSource,
            endOfSource,
            editableRef,
            start,
            end,
        )
        val replaceSource = ReplaceSource.capture(filtered.text, filtered.start, filtered.end)
        val oldLength = end - start
        val newLength = replaceSource.text.length
        // 零插入且来源无可复制 span 时平台合同不做任何通知
        if (oldLength == 0 && newLength == 0 && replaceSource.hasNoExpandableSpan) return
        // 每个通知阶段各取一次当前监听快照: 阶段内注销/重挂不触发并发修改, 也不影响后续阶段
        for (watcher in ArrayList(watcherList)) {
            watcher.beforeTextChanged(editableRef, start, oldLength, newLength)
        }
        editableRef.spanStore.adjustForReplace(start, end, newLength, buffer.length)
        buffer.replace(start, end, replaceSource.text)
        editableRef.spanStore.copySourceSpans(replaceSource, start)
        for (watcher in ArrayList(watcherList)) {
            watcher.onTextChanged(editableRef, start, oldLength, newLength)
        }
        for (watcher in ArrayList(watcherList)) {
            watcher.afterTextChanged(editableRef)
        }
        recordNotifiedText()
    }

    /** 整段替换 (事务内): 内部事务标记按同一范围调整, 用户 span 弃置, 新文本自带 span 复制。 */
    private fun replaceWhole(source: ReplaceSource) {
        val buffer = checkNotNull(activeBuffer) { "文本修改必须运行在编辑事务内" }
        val oldLength = buffer.length
        val newLength = source.text.length
        for (watcher in ArrayList(watcherList)) {
            watcher.beforeTextChanged(editableRef, 0, oldLength, newLength)
        }
        editableRef.spanStore.adjustForReplace(0, oldLength, newLength, oldLength)
        editableRef.spanStore.clearSpans()
        buffer.replace(0, oldLength, source.text)
        editableRef.spanStore.copySourceSpans(source, 0)
        for (watcher in ArrayList(watcherList)) {
            watcher.onTextChanged(editableRef, 0, oldLength, newLength)
        }
        for (watcher in ArrayList(watcherList)) {
            watcher.afterTextChanged(editableRef)
        }
        recordNotifiedText()
    }

    /** 记录实际已通知的库文本 (只用于通知去重与旧值参数, 不作编辑/呈现/提交来源)。 */
    fun recordNotifiedText() {
        notifiedText = readText.toString()
    }

    /** 库直接变更 (撤销/重做等不经输入变换): 补一次监听通知与 span 区间调整。 */
    fun onLibraryTextChanged(currentText: String) {
        if (activeBuffer != null) return
        // 先收口事务内 setText 的清理标记: 其历史属于已落库的提交, 不吞掉通知内新记录
        consumePendingUndoHistoryClear()
        if (currentText != notifiedText) notifyLibraryChange(notifiedText, currentText)
    }

    /** 事务起点对齐尚未被观察消费的库直接变更 (用本次缓冲原文, 不遗留旧 span/旧校验)。 */
    fun reconcileLibraryChange() {
        val buffer = checkNotNull(activeBuffer)
        val currentText = buffer.asCharSequence().toString()
        if (currentText != notifiedText) notifyLibraryChange(notifiedText, currentText)
    }

    /** 按 SDK 合同通知一次净变更 (before 旧值 / on 新值 / after 当前 Editable) 并同步标记区间。 */
    private fun notifyLibraryChange(oldText: String, newText: String) {
        val oldLength = oldText.length
        val newLength = newText.length
        val minLength = minOf(oldLength, newLength)
        var prefix = 0
        while (prefix < minLength && oldText[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (suffix < minLength - prefix &&
            oldText[oldLength - 1 - suffix] == newText[newLength - 1 - suffix]
        ) {
            suffix++
        }
        val removed = oldLength - prefix - suffix
        val inserted = newLength - prefix - suffix
        for (watcher in ArrayList(watcherList)) {
            watcher.beforeTextChanged(oldText, prefix, removed, inserted)
        }
        // 库外部变更只对齐旧值坐标的用户 span; 事务标记已属当前原文基准, 不再重复平移
        editableRef.spanStore.adjustUserSpansForReplace(prefix, oldLength - suffix, inserted, oldLength)
        // 游标在允许监听改字的 on/after 之前登记为此次实际文本, 同一变更不会重入再通知
        recordNotifiedText()
        for (watcher in ArrayList(watcherList)) {
            watcher.onTextChanged(editableRef, prefix, removed, inserted)
        }
        for (watcher in ArrayList(watcherList)) {
            watcher.afterTextChanged(editableRef)
        }
    }

    /** 收口已落库的 setText 清理标记: 解除标记并清一次库历史 (只服务事务生命周期)。 */
    fun consumePendingUndoHistoryClear() {
        if (!pendingUndoHistoryClear) return
        pendingUndoHistoryClear = false
        clearUndoHistory()
    }

    /** 本次提交未落库: 只撤销本事务内 setText 登记的清理标记, 已落库历史保持原样。 */
    fun cancelUncommittedUndoHistoryClear() {
        pendingUndoHistoryClear = false
    }

    private fun clearUndoHistory() {
        state.undoState.clearHistory()
    }

    /**
     * 事务入口: 已有活动事务时直接在它里面执行 (after 重入合法); 否则装入一次库 edit 并
     * 建立事务。事务外与事务内的修改都只落库缓冲, 不再嵌套开第二次 edit。
     */
    fun withEditTransaction(block: () -> Unit) {
        if (activeBuffer != null) {
            block()
            return
        }
        // 新事务开始前先收口上一次已落库、观察器尚未消费的 setText 清理标记
        consumePendingUndoHistoryClear()
        try {
            state.edit {
                beginTransaction(this)
                try {
                    // 库变更对齐与全部插件回调同在本 try/finally 内, 异常不遗留可继续写的缓冲
                    reconcileLibraryChange()
                    block()
                } finally {
                    endTransaction()
                }
            }
        } catch (e: Throwable) {
            // 本次提交未落库: 只撤销本事务内 setText 登记的标记
            cancelUncommittedUndoHistoryClear()
            throw e
        }
        // 库 edit 已提交返回: 事务内 setText 的历史清理在此收口
        consumePendingUndoHistoryClear()
    }

    /** 事务入口只安装缓冲与阶段状态; 对齐变更由调用点在同一个 try/finally 内执行。 */
    fun beginTransaction(buffer: TextFieldBuffer) {
        activeBuffer = buffer
        selectionEditedByListener = false
    }

    fun endTransaction() {
        activeBuffer = null
    }

    fun release() {
        watcherList.clear()
        filterList.clear()
    }

    private fun applySingleLine(singleLine: Boolean, applyTransformation: Boolean) {
        singleLineState = singleLine
        if (singleLine) {
            setLines(1)
            if (applyTransformation) {
                setTransformationMethod(SingleLineTransformationMethod.getInstance())
            }
        } else {
            setMaxLines(Int.MAX_VALUE)
            if (applyTransformation) {
                setTransformationMethod(null)
            }
        }
    }

    private fun setInputTypeSingleLine(singleLine: Boolean) {
        if ((inputTypeState and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            inputTypeState = if (singleLine) {
                inputTypeState and InputType.TYPE_TEXT_FLAG_MULTI_LINE.inv()
            } else {
                inputTypeState or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
        }
    }

    private fun isMultilineInputType(type: Int): Boolean =
        (type and (InputType.TYPE_MASK_CLASS or InputType.TYPE_TEXT_FLAG_MULTI_LINE)) ==
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)

    private fun isPasswordInputType(inputType: Int): Boolean {
        val variation = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return variation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) ||
            variation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            variation == (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun isVisiblePasswordInputType(inputType: Int): Boolean {
        val variation = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return variation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
    }

    private companion object {
        /** 程序化 setText 的过滤 dest (对齐 TextView.EMPTY_SPANNED 的空文本面)。 */
        val EMPTY_SPANNED: Spanned = TextEditable("")
    }
}

/**
 * 插件对 Editable 的 SDK 调用适配: 读取路由到库当前状态, 修改路由到同一个编辑事务,
 * span 面由 [android.text.SpanStore] 承载。
 */
internal class DesktopEditable(private val host: DesktopPrefEditText) : Editable {

    val spanStore = SpanStore { host.readText }

    private val readText: CharSequence get() = host.readText

    // ===== CharSequence / GetChars =====

    override val length: Int get() = readText.length

    override fun get(index: Int): Char = readText[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        spanStore.slice(startIndex, endIndex)

    override fun getChars(start: Int, end: Int, dest: CharArray, destOff: Int) {
        spanStore.getChars(start, end, dest, destOff)
    }

    override fun toString(): String = readText.toString()

    // ===== Spanned / Spannable =====

    override fun setSpan(what: Any?, start: Int, end: Int, flags: Int) {
        spanStore.setSpan(what, start, end, flags)
    }

    override fun removeSpan(what: Any?) {
        spanStore.removeSpan(what)
    }

    override fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<T> =
        spanStore.getSpans(start, end, type)

    override fun getSpanStart(tag: Any?): Int = spanStore.getSpanStart(tag)

    override fun getSpanEnd(tag: Any?): Int = spanStore.getSpanEnd(tag)

    override fun getSpanFlags(tag: Any?): Int = spanStore.getSpanFlags(tag)

    override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int =
        spanStore.nextSpanTransition(start, limit, type)

    override fun clearSpans() {
        spanStore.clearSpans()
    }

    override fun setFilters(filters: Array<InputFilter>?) {
        host.setFilters(filters)
    }

    override fun getFilters(): Array<InputFilter> = host.getFilters()

    // ===== Appendable / Editable (修改统一经 host 编辑事务) =====

    override fun append(csq: CharSequence?): Editable {
        val text = csq ?: ""
        return replace(length, length, text, 0, text.length)
    }

    override fun append(c: Char): Editable = replace(length, length, c.toString())

    override fun append(csq: CharSequence?, start: Int, end: Int): Editable =
        replace(length, length, csq ?: "", start, end)

    override fun replace(start: Int, end: Int, source: CharSequence?): Editable {
        val text = source ?: ""
        return replace(start, end, text, 0, text.length)
    }

    override fun replace(
        start: Int,
        end: Int,
        source: CharSequence?,
        startOfSource: Int,
        endOfSource: Int,
    ): Editable {
        host.withEditTransaction {
            host.applyReplace(start, end, source ?: "", startOfSource, endOfSource)
        }
        return this
    }

    override fun insert(where: Int, text: CharSequence?): Editable {
        val value = text ?: ""
        return replace(where, where, value, 0, value.length)
    }

    override fun insert(where: Int, text: CharSequence?, startOfText: Int, endOfText: Int): Editable =
        replace(where, where, text ?: "", startOfText, endOfText)

    override fun delete(start: Int, end: Int): Editable = replace(start, end, "")

    override fun clear() {
        replace(0, length, "")
    }
}

/**
 * 用户输入的过滤桥: 官方编辑 API 给出的净变更片段先倒序撤回成编辑前文本, 再在同一缓冲里
 * 按文本次序逐段重放平台 replace 合同 (过滤 → before → 修改 → on → after)。后续片段位置由
 * 事务私有的内部标记随同一次区间调整更新 (不进入插件的 span 读写面), 监听对其它位置的改动
 * 不会让重放坐标失真; 片段重放完成后标记移除。无过滤/监听改动时恢复原提案选区,
 * 监听显式改过选区时不覆盖。
 */
private class DesktopFilterTransformation(
    private val host: DesktopPrefEditText,
) : InputTransformation {

    private class Segment(
        val originalStart: Int,
        val originalEnd: Int,
        val bufferStart: Int,
        val bufferEnd: Int,
        val source: String,
    )

    private class AnchorPair(val start: Any, val end: Any)

    @OptIn(ExperimentalFoundationApi::class)
    override fun TextFieldBuffer.transformInput() {
        val changeCount = changes.changeCount
        if (changeCount == 0) return
        // 开始本次受控重放前先收口上一次已落库、观察器尚未消费的 setText 清理标记
        host.consumePendingUndoHistoryClear()
        val proposedText = asCharSequence().toString()
        val proposedSelection = selection
        val segments = ArrayList<Segment>(changeCount)
        for (index in 0 until changeCount) {
            val bufferRange = changes.getRange(index)
            val originalRange = changes.getOriginalRange(index)
            segments.add(
                Segment(
                    originalStart = originalRange.min,
                    originalEnd = originalRange.max,
                    bufferStart = bufferRange.min,
                    bufferEnd = bufferRange.max,
                    source = asCharSequence().subSequence(bufferRange.min, bufferRange.max).toString(),
                )
            )
        }
        // 倒序撤回: 文本回到本次用户编辑之前, 后段撤回不影响前段坐标
        for (index in segments.indices.reversed()) {
            val segment = segments[index]
            replace(
                segment.bufferStart,
                segment.bufferEnd,
                originalText.subSequence(segment.originalStart, segment.originalEnd).toString(),
            )
        }
        val anchors = ArrayList<AnchorPair>(segments.size)
        var listenerChangedSelection = false
        // 每段尚未重放的起止都用内部事务标记 (不暴露给插件, setText 清用户 span 时也保留)
        for (segment in segments) {
            val pair = AnchorPair(object : Any() {}, object : Any() {})
            host.editableRef.spanStore.setInternalMarker(pair.start, segment.originalStart)
            host.editableRef.spanStore.setInternalMarker(pair.end, segment.originalEnd)
            anchors.add(pair)
        }
        host.beginTransaction(this)
        try {
            // 未消费的库变更在此对齐用户 span; 事务标记已按 buffer.originalText 基准登记
            host.reconcileLibraryChange()
            for (index in segments.indices) {
                val segment = segments[index]
                val start = host.editableRef.spanStore.internalMarkerPosition(anchors[index].start)
                val end = host.editableRef.spanStore.internalMarkerPosition(anchors[index].end)
                host.applyReplace(
                    start,
                    end,
                    segment.source,
                    0,
                    segment.source.length,
                )
            }
            listenerChangedSelection = host.selectionEditedByListener
            host.recordNotifiedText()
        } catch (e: Throwable) {
            // 本次库提交未成功: 只撤销本事务内 setText 登记的标记, 异常按原链继续抛
            host.cancelUncommittedUndoHistoryClear()
            throw e
        } finally {
            host.endTransaction()
            anchors.forEach {
                host.editableRef.spanStore.removeInternalMarker(it.start)
                host.editableRef.spanStore.removeInternalMarker(it.end)
            }
        }
        if (!listenerChangedSelection && asCharSequence().contentEquals(proposedText)) {
            selection = proposedSelection
        }
    }
}
