package io.legado.app.ui.book.manga.extension

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.text.TransformationMethod
import android.text.method.PasswordTransformationMethod
import android.text.method.SingleLineTransformationMethod
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
 * 插件文本项编辑弹窗 (桌面兼容层活适配承载, 与 Android 真实控件承载同语义): 会话持本次
 * 实际配置对象, 插件的 `OnBindEditTextListener` 跑在适配输入框上。
 *
 * 活文本与选区的唯一真源是 [TextFieldState] (B3): Compose 只是呈现库状态, 不另存
 * StringBuilder/publishedText 快照、不用 String 回声当提交事件。`DesktopEditable` 只把
 * 插件对 Editable 的 SDK 调用适配到库: 用户编辑在官方 `TextFieldBuffer.originalText`/
 * `changes` 给出的真实变更范围内、以库当前有效事务 buffer 逐段重放合同 (过滤 → before →
 * 修改 → on → after, after 回调内继续改 Editable 重入同一事务是合法平台合同); 程序化
 * setText 走库 edit 事务并以空 dest 过滤。声明面 (单行/多行/hint/行数/密码变换) 是
 * snapshot 状态, 绑定与 TextWatcher 后续修改实时反映; 行数/密码判定按平台真实掩码。
 *
 * 提交先 `callChangeListener` 通过才落值 (View/模型操作在主线程; 桌面兼容层偏好写盘同步,
 * 磁盘持久化边界冻结待另批); 取消不保存; 关闭释放监听与挂链引用。桌面无 IME; hint 以
 * 资源 id 声明不解析 (兼容层无插件资源系统, 冻结环节)。
 */
@Composable
fun DesktopMangaPrefTextEditor(request: MangaPrefTextEditorRequest, context: Context) {
    // 会话由同平台配置桥创建, 平台绑定面在此取回 (装配契约)
    val editorSession = request.session as MangaPrefTextEditorSession
    // 控件组与绑定在主线程完成; 插件监听器/ABI 故障 (含 LinkageError) 如实记录并关闭,
    // 不伪装成功也不主线程裸抛
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
    widgets?.let { w ->
        DisposableEffect(w) {
            onDispose { w.release() }
        }
        val scope = rememberCoroutineScope()
        val okText = stringResource(Res.string.ok)
        val cancelText = stringResource(Res.string.cancel)
        AppAlertDialog(
            onDismissRequest = request.onDismiss,
            okButton = AlertButton(
                text = okText,
                enabled = w.confirmEnabled.value,
                // 显式关闭边界: 提交完成 (含业务拒绝/故障) 后按结果关闭, 不用默认立即关
                // (那会取消未完成的提交)
                dismissOnClick = false,
            ) {
                val value = w.state.text.toString()
                scope.launch {
                    // 真实故障与插件拦截区分: 异常记录日志, 关闭边界一致, 不伪装成功/拒绝
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
            AppTextField(
                state = w.state,
                singleLine = w.editText.singleLine,
                // Compose 域最小 1 行; 真实 min 0 行 (无下限) 的呈现等价
                minLines = w.editText.minLines.coerceAtLeast(1),
                maxLines = w.editText.maxLines,
                placeholder = w.editText.hint,
                visualTransformation = if (w.editText.passwordTransformation) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                inputTransformation = w.inputTransformation,
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

    /** 释放: 解除登记的监听与确认键回调 (控件随组合弃置)。 */
    fun release() {
        editText.release()
        confirmButton?.onEnabledChanged = null
    }

    private var confirmButton: Button? = null

    companion object {
        fun create(context: Context, session: MangaPrefTextEditorSession): DesktopPrefEditWidgets {
            val confirmEnabled = mutableStateOf(true)
            val confirmButton = Button(context).apply {
                // 假确认键 (Mihon 同款 id, 初始 enabled=true 平台默认): 插件校验改它的 enabled
                id = android.R.id.button1
                onEnabledChanged = { enabled -> confirmEnabled.value = enabled }
            }
            val root = LinearLayout(context)
            root.addView(confirmButton)
            // 活文本唯一真源 = TextFieldState (库); 初始值走 setText 合同 (过滤+通知)
            val editText = DesktopPrefEditText(context, TextFieldState(""))
            root.addView(editText)
            // 完整同树后按库顺序绑定: 填当前值 → 光标到末尾 → 插件监听器
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
 * 桌面活适配输入框: 插件对 EditText/Editable 的 SDK 调用适配到 [TextFieldState] (唯一活
 * 文本与选区真源)。声明面 (单行/多行/hint/行数/密码变换) 是 snapshot 状态; 文本修改合同
 * (过滤/变更通知) 在 [DesktopPrefEditText.runReplaceContract] 编排。
 */
internal class DesktopPrefEditText(context: Context, val state: TextFieldState) : EditText(context) {

    // 声明面: 呈现直接订阅 (TextWatcher 后改 hint/行数/变换实时重绘)
    var singleLine: Boolean by mutableStateOf(false)
        private set

    var hint: String? by mutableStateOf(null)
        private set

    /** 真实默认 mMinimum = 0 (无下限)。 */
    var minLines: Int by mutableStateOf(0)
        private set

    /** 真实默认 mMaximum = Int.MAX_VALUE (mMaxMode = LINES)。 */
    var maxLines: Int by mutableStateOf(Int.MAX_VALUE)
        private set

    var passwordTransformation: Boolean by mutableStateOf(false)
        private set

    val inputTransformation: InputTransformation = DesktopFilterTransformation(this)

    // 私有后备 (R1: 宿主内部状态)
    private var inputTypeState: Int = InputType.TYPE_NULL
    private var transformationState: TransformationMethod? = null
    private val filterList = ArrayList<InputFilter>()
    private val watcherList = ArrayList<TextWatcher>()
    private var lastReplaceNewLen: Int = 0

    /** 事务通道: 活动事务 buffer (transformInput / state.edit 期间); 修改路由至此, 不重入库 edit。 */
    private var activeBuffer: TextFieldBuffer? = null

    /** 事务首个 before 前为 true: 读取变更前文本 (真实合同 before 时内容仍旧)。 */
    private var readBeforeChange: Boolean = false

    /** 活文本读取 (库当前有效状态; 事务内按合同阶段读变更前/当前)。 */
    val readText: CharSequence
        get() {
            val buffer = activeBuffer
            return when {
                buffer == null -> state.text
                readBeforeChange -> buffer.originalText
                else -> buffer.asCharSequence()
            }
        }

    /** 适配器可变文本面 (插件 getText() 拿到的 Editable, 跨 setText 持续)。 */
    val editableRef: DesktopEditable = DesktopEditable(this)

    // ===== TextView getter/setter 面 (真实描述符, 私有后备承载) =====

    override fun getHint(): CharSequence? = hint

    override fun isSingleLine(): Boolean = singleLine

    override fun getInputType(): Int = inputTypeState

    override fun getFilters(): Array<out InputFilter> = filterList.toTypedArray()

    override fun getMinLines(): Int = minLines

    override fun getMaxLines(): Int = maxLines

    override fun getTransformationMethod(): TransformationMethod? = transformationState

    override fun setHint(hint: CharSequence?) {
        this.hint = hint?.toString()
    }

    override fun setHint(resId: Int) {
        // 平台合同经 Context 资源解析; 桌面兼容层无插件资源系统 (冻结环节), 不解析
    }

    override fun setSingleLine() {
        applySingleLine(true)
    }

    override fun setSingleLine(singleLine: Boolean) {
        applySingleLine(singleLine)
    }

    override fun setInputType(type: Int) {
        val wasPassword = isPasswordInputType(inputTypeState)
        inputTypeState = type
        val isPassword = isPasswordInputType(type)
        if (isPassword) {
            setTransformationMethod(PasswordTransformationMethod.getInstance())
        } else if (wasPassword) {
            // 真实切出密码: 清变换
            setTransformationMethod(null)
        }
        // 真实: singleLine = !isMultilineInputType(type) (TYPE_NULL → 单行, 未设置 → 多行)
        applySingleLine(!isMultilineInputType(type), updateTransformation = !isPassword)
    }

    override fun setFilters(filters: Array<out InputFilter>?) {
        filterList.clear()
        if (filters != null) filterList.addAll(filters)
    }

    override fun setTransformationMethod(method: TransformationMethod?) {
        transformationState = method
        passwordTransformation = method is PasswordTransformationMethod
    }

    override fun setMinLines(minLines: Int) {
        this.minLines = minLines
    }

    override fun setMaxLines(maxLines: Int) {
        this.maxLines = maxLines
    }

    override fun setLines(lines: Int) {
        minLines = lines
        maxLines = lines
    }

    // ===== 文本面 (库状态真源; 合同修改经 runReplaceContract 编排) =====

    override fun getText(): Editable = editableRef

    override fun setText(value: CharSequence?) {
        // TextView.setText 合同 (android-35 一手): 以空 dest 送过滤器, 事务内
        // before(旧文本, 0, 旧长, 新长) → 修改 → on → after (watcher 持续收通知)
        var text: CharSequence = value ?: ""
        for (f in filterList) {
            val out = f.filter(text, 0, text.length, EMPTY_SPANNED, 0, 0)
            if (out != null) text = out
        }
        state.edit {
            beginTransaction(this)
            try {
                runReplaceContract(0, length, text, 0, text.length, applyFilters = false)
            } finally {
                endTransaction()
            }
        }
    }

    override fun setSelection(index: Int) {
        applySelection(TextRange(index, index))
    }

    override fun setSelection(start: Int, stop: Int) {
        applySelection(TextRange(start, stop))
    }

    private fun applySelection(range: TextRange) {
        val buffer = activeBuffer
        if (buffer != null) {
            buffer.selection = range
        } else {
            state.edit { selection = range }
        }
    }

    // ===== 监听 (库状态真源下跨事务持续, 对齐真实 mListeners 通道) =====

    override fun addTextChangedListener(watcher: TextWatcher?) {
        watcher?.let(watcherList::add)
    }

    override fun removeTextChangedListener(watcher: TextWatcher?) {
        watcherList.remove(watcher)
    }

    // ===== 编辑合同 (B3: 一个事务内 过滤→before→修改→on→after 及合法 after 重入) =====

    /**
     * 在当前事务内执行一次真实 `Editable.replace` 合同: [applyFilters] 时先按声明顺序跑
     * 过滤链 (null 接受原替换/空串拒绝/删除保留, dest 为变更前文本), 然后 before (内容仍旧)
     * → 修改 (事务内操作提供的事务 buffer, 事务外走库 edit) → on → after; after 回调内继续
     * 改 Editable 重入同一事务是合法平台合同, 不加禁重入。外部非法范围按库校验如实抛, 不
     * clamp 掩错。返回本次替换的长度增量。
     */
    fun runReplaceContract(
        start: Int,
        end: Int,
        source: CharSequence,
        sstart: Int,
        send: Int,
        applyFilters: Boolean,
    ): Int {
        var src: CharSequence = source
        var a = sstart
        var b = send
        if (applyFilters) {
            for (f in filterList) {
                val repl = f.filter(src, a, b, editableRef, start, end)
                if (repl != null) {
                    src = repl
                    a = 0
                    b = repl.length
                }
            }
        }
        val origLen = end - start
        val newLen = b - a
        if (origLen == 0 && newLen == 0) {
            // 平台 no-op (0 长替换 + 0 长新文), 无通知
            return 0
        }
        // before: 变更前文本 (事务首个 before 读 originalText; 重入读当前)
        watcherList.forEach { it.beforeTextChanged(editableRef, start, origLen, newLen) }
        readBeforeChange = false
        // 修改: 事务内操作提供的事务 buffer, 事务外走库 edit
        val replacement = src.subSequence(a, b)
        val buffer = activeBuffer
        if (buffer != null) {
            buffer.replace(start, end, replacement)
        } else {
            state.edit { replace(start, end, replacement) }
        }
        editableRef.mapSpansOnReplace(start, end, newLen)
        // on/after: 读取当前
        watcherList.forEach { it.onTextChanged(editableRef, start, origLen, newLen) }
        watcherList.forEach { it.afterTextChanged(editableRef) }
        return newLen - origLen
    }

    fun beginTransaction(buffer: TextFieldBuffer) {
        activeBuffer = buffer
        readBeforeChange = true
    }

    fun endTransaction() {
        activeBuffer = null
        readBeforeChange = false
    }

    fun release() {
        watcherList.clear()
        filterList.clear()
    }

    /** 过滤链 (声明顺序), 链全 null = 接受原替换 (返回 null)。 */
    fun runFilters(
        source: CharSequence,
        sstart: Int,
        send: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int,
    ): CharSequence? {
        var src: CharSequence = source
        var a = sstart
        var b = send
        for (f in filterList) {
            val repl = f.filter(src, a, b, dest, dstart, dend)
            if (repl != null) {
                src = repl
                a = 0
                b = repl.length
            }
        }
        return if (src === source && a == sstart && b == send) null else src.subSequence(a, b)
    }

    private fun applySingleLine(singleLine: Boolean, updateTransformation: Boolean = true) {
        this.singleLine = singleLine
        // 真实 setInputTypeSingleLine: 仅 CLASS_TEXT 时同步 MULTI_LINE 标记
        if ((inputTypeState and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            inputTypeState = if (singleLine) {
                inputTypeState and InputType.TYPE_TEXT_FLAG_MULTI_LINE.inv()
            } else {
                inputTypeState or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
        }
        // 真实 applySingleLine: 非密码时切单行变换/清变换 (内容面不变)
        if (updateTransformation && transformationState !is PasswordTransformationMethod) {
            transformationState = if (singleLine) {
                SingleLineTransformationMethod.getInstance()
            } else {
                null
            }
            passwordTransformation = transformationState is PasswordTransformationMethod
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

    private companion object {
        /** 程序化 setText 的过滤 dest (对齐真实 TextView.EMPTY_SPANNED)。 */
        private val EMPTY_SPANNED: Spanned = TextEditable("")
    }
}

/**
 * 插件对 Editable 的 SDK 调用适配器: 读取路由到库当前有效状态 (事务内按合同阶段读变更前/
 * 当前), 修改路由到 [DesktopPrefEditText.runReplaceContract] 合同编排。span 面为真实合同
 * (合法 watcher 用 setSpan 跟踪编辑偏移): 列表存储 + 修改时经 mapSpanRange 映射。
 */
internal class DesktopEditable(private val host: DesktopPrefEditText) : Editable {

    private class SpanRecord(val what: Any, var start: Int, var end: Int, var flags: Int)

    private val spans = ArrayList<SpanRecord>()

    private val readText: CharSequence get() = host.readText

    // ===== CharSequence (路由到库当前有效状态) =====

    override val length: Int get() = readText.length

    override fun get(index: Int): Char = readText[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        readText.subSequence(startIndex, endIndex)

    override fun toString(): String = readText.toString()

    // ===== Spanned/Spannable (真实合同) =====

    override fun setSpan(what: Any?, start: Int, end: Int, flags: Int) {
        if (what == null) return
        spans.removeAll { it.what === what }
        spans.add(SpanRecord(what, start, end, flags))
    }

    override fun removeSpan(what: Any?) {
        if (what == null) return
        spans.removeAll { it.what === what }
    }

    override fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<T> {
        val matched = spans.filter {
            type.isInstance(it.what) && it.end >= start && it.start <= end
        }
        @Suppress("UNCHECKED_CAST")
        val result = java.lang.reflect.Array.newInstance(type, matched.size) as Array<T>
        matched.forEachIndexed { index, record -> result[index] = type.cast(record.what) }
        return result
    }

    override fun getSpanStart(tag: Any?): Int = spans.firstOrNull { it.what === tag }?.start ?: -1

    override fun getSpanEnd(tag: Any?): Int = spans.firstOrNull { it.what === tag }?.end ?: -1

    override fun getSpanFlags(tag: Any?): Int = spans.firstOrNull { it.what === tag }?.flags ?: 0

    override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int {
        var next = limit
        for (record in spans) {
            if (type != null && !type.isInstance(record.what)) continue
            if (record.start > start && record.start < next) next = record.start
            if (record.end > start && record.end < next) next = record.end
        }
        return next
    }

    /** 合同修改后的 span 区间映射。 */
    fun mapSpansOnReplace(start: Int, end: Int, replacementLength: Int) {
        for (record in spans) {
            val mapped = mapSpanRange(start, end, replacementLength, record.start, record.end)
            record.start = mapped.first
            record.end = mapped.second
        }
    }

    // ===== Appendable / Editable (修改路由到 host 合同编排) =====

    override fun append(csq: CharSequence?): Editable =
        host.runReplaceContract(length, length, csq ?: "", 0, (csq ?: "").length, applyFilters = true).let { this }

    override fun append(c: Char): Editable =
        host.runReplaceContract(length, length, c.toString(), 0, 1, applyFilters = true).let { this }

    override fun append(csq: CharSequence?, start: Int, end: Int): Editable {
        host.runReplaceContract(length, length, csq ?: "", start, end, applyFilters = true)
        return this
    }

    override fun replace(start: Int, end: Int, source: CharSequence?): Editable {
        host.runReplaceContract(start, end, source ?: "", 0, (source ?: "").length, applyFilters = true)
        return this
    }

    override fun replace(start: Int, end: Int, source: CharSequence?, startOfSource: Int, endOfSource: Int): Editable {
        host.runReplaceContract(start, end, source ?: "", startOfSource, endOfSource, applyFilters = true)
        return this
    }

    override fun insert(where: Int, text: CharSequence?): Editable {
        host.runReplaceContract(where, where, text ?: "", 0, (text ?: "").length, applyFilters = true)
        return this
    }

    override fun insert(where: Int, text: CharSequence?, startOfText: Int, endOfText: Int): Editable {
        host.runReplaceContract(where, where, text ?: "", startOfText, endOfText, applyFilters = true)
        return this
    }

    override fun delete(start: Int, end: Int): Editable {
        host.runReplaceContract(start, end, "", 0, 0, applyFilters = true)
        return this
    }

    override fun clear() {
        host.runReplaceContract(0, length, "", 0, 0, applyFilters = true)
    }

    override fun clearSpans() {
        spans.clear()
    }
}

/**
 * 用户输入的过滤桥: 官方 state-based 编辑 API (`TextFieldBuffer.originalText`/`changes`)
 * 给出真实变更范围。把用户变更倒序撤回后, 在事务内正序按合同逐段重放 (过滤 → before →
 * 修改 → on → after), 过滤结果写回同一 buffer; 通知不在此处外发到其它状态, 留在合同内。
 */
private class DesktopFilterTransformation(
    private val host: DesktopPrefEditText,
) : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        val changeCount = changes.changeCount
        if (changeCount == 0) return
        val segments = (0 until changeCount).map { i ->
            val range = changes.getRange(i)
            val originalRange = changes.getOriginalRange(i)
            val source = asCharSequence().subSequence(range.min, range.max).toString()
            Segment(range, originalRange, source)
        }
        // 倒序撤回用户变更 → buffer = 变更前文本 (真实"该次 replace 前"状态)
        for (segment in segments.asReversed()) {
            replace(
                segment.range.min,
                segment.range.max,
                originalText.subSequence(segment.originalRange.min, segment.originalRange.max).toString(),
            )
        }
        // 正序合同重放: 坐标 = 原区间 + 前段累计增量 (单段 = 精确合同), 过滤结果写回同一 buffer
        var offset = 0
        for (segment in segments) {
            val segStart = segment.originalRange.min + offset
            val segEnd = segment.originalRange.max + offset
            val delta = host.runReplaceContract(segStart, segEnd, segment.source, 0, segment.source.length, applyFilters = true)
            offset += delta
        }
    }

    private class Segment(
        val range: TextRange,
        val originalRange: TextRange,
        val source: String,
    )
}
