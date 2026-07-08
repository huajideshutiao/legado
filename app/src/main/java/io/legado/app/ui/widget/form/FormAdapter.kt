package io.legado.app.ui.widget.form

import android.annotation.SuppressLint
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatSpinner
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.lib.theme.applyThemeTree
import io.legado.app.lib.theme.space
import io.legado.app.lib.theme.viewHeight
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.ui.widget.text.EditEntity

/**
 * 通用表单 Adapter，参考 [io.legado.app.ui.book.source.edit.BookSourceEditAdapter] 的 FieldTypes 机制，
 * 复用 [EditEntity] 数据模型，支持 text / checkBox / spinner / code 四种字段类型。
 *
 * 与 BookSourceEditAdapter 的区别：
 * - text 类型用 [AppCompatEditText]（非 CodeView），适合短文本表单
 * - code 类型用 [CodeView]，按 [EditEntity.codePatterns] 位掩码配置语法高亮
 * - 立即回写（非 500ms 延迟），适配 Dialog 即时校验场景
 */
class FormAdapter : RecyclerView.Adapter<FormAdapter.FormViewHolder>() {

    var editEntities: ArrayList<EditEntity> = ArrayList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int {
        val entity = editEntities[position]
        // code 类型无 pattern 时降级为 text，避免创建重量级 CodeView（含 AutoCompleteAdapter 等）
        if (entity.viewType == EditEntity.ViewType.code && entity.codePatterns == 0) {
            return EditEntity.ViewType.text
        }
        return entity.viewType
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FormViewHolder {
        val ctx = parent.context
        return when (viewType) {
            EditEntity.ViewType.spinner -> {
                val root = LinearLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ctx.viewHeight.xl
                    )
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(ctx.space.md, ctx.space.default, ctx.space.md, ctx.space.default)
                }
                val textView = TextView(ctx).apply {
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply { marginEnd = ctx.space.default }
                    gravity = Gravity.CENTER_VERTICAL
                }
                val spinner = AppCompatSpinner(ctx).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    dropDownWidth = ViewGroup.LayoutParams.WRAP_CONTENT
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }
                root.addView(textView)
                root.addView(spinner)
                // 动态构造的 View 不走 Factory2, 在 return 前整树着色 (TextView/Spinner 等会被 ThemeInterceptor 处理)
                root.applyThemeTree()
                SpinnerViewHolder(FormSpinnerItemBinding(root, textView, spinner))
            }

            EditEntity.ViewType.checkBox -> {
                val checkBox = CheckBox(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    minHeight = ctx.viewHeight.xl
                }
                checkBox.applyThemeTree()
                CheckBoxViewHolder(FormCheckBoxItemBinding(checkBox))
            }

            EditEntity.ViewType.code -> {
                val root = TextInputLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, ctx.space.xs, 0, 0)
                }
                val codeView = CodeView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                root.addView(codeView)
                // 动态构造的 TextInputLayout + CodeView 不走 Factory2, 在 return 前整树着色
                // (TextInputLayout 的 boxStrokeColor/hintTextColor 和 CodeView 的底线/光标会被处理)
                root.applyThemeTree()
                CodeViewHolder(FormCodeItemBinding(root, codeView))
            }

            else -> {
                val root = TextInputLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, ctx.space.xs, 0, 0)
                }
                val editText = AppCompatEditText(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                root.addView(editText)
                // 动态构造的 TextInputLayout + EditText 不走 Factory2, 在 return 前整树着色
                // (TextInputLayout 的 boxStrokeColor/hintTextColor 和 EditText 的底线/光标会被处理)
                root.applyThemeTree()
                TextViewHolder(FormTextItemBinding(root, editText))
            }
        }
    }

    override fun onBindViewHolder(holder: FormViewHolder, position: Int) {
        holder.bind(editEntities[position])
    }

    override fun getItemCount(): Int = editEntities.size

    abstract class FormViewHolder(binding: ViewBinding) :
        RecyclerView.ViewHolder(binding.root) {
        abstract fun bind(entity: EditEntity)
    }

    inner class TextViewHolder(private val binding: FormTextItemBinding) :
        FormViewHolder(binding) {

        override fun bind(entity: EditEntity) = binding.run {
            // 移除旧 TextWatcher 避免复用时错位
            (editText.getTag(R.id.tag2) as? TextWatcher)?.let {
                editText.removeTextChangedListener(it)
            }
            editText.setTag(R.id.tag, entity.key)
            // 仅在值不一致时 setText, 避免光标跳到开头
            val current = editText.text?.toString()
            if (current != entity.value) {
                editText.setText(entity.value)
            }
            textInputLayout.hint = entity.hint
            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                }

                override fun afterTextChanged(s: Editable?) {
                    entity.value = s?.toString()
                }
            }
            editText.addTextChangedListener(textWatcher)
            editText.setTag(R.id.tag2, textWatcher)
        }
    }

    inner class CheckBoxViewHolder(private val binding: FormCheckBoxItemBinding) :
        FormViewHolder(binding) {

        override fun bind(entity: EditEntity) = binding.run {
            // 移除旧监听避免回调错位
            checkBox.setOnCheckedChangeListener(null)
            checkBox.text = entity.hint
            checkBox.isChecked = entity.boolValue
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                entity.value = isChecked.toString()
            }
        }
    }

    inner class SpinnerViewHolder(private val binding: FormSpinnerItemBinding) :
        FormViewHolder(binding) {

        override fun bind(entity: EditEntity) = binding.run {
            val context = root.context
            textView.text = entity.hint
            val selections = entity.selections.orEmpty()
            val labels = selections.map { it.first }
            val arrayAdapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                labels
            )
            arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.onItemSelectedListener = null
            spinner.adapter = arrayAdapter
            val currentIndex = selections.indexOfFirst { it.second == entity.value }
                .let { if (it < 0) 0 else it }
            spinner.setSelection(currentIndex)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    entity.value = selections.getOrNull(position)?.second
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    inner class CodeViewHolder(private val binding: FormCodeItemBinding) :
        FormViewHolder(binding) {

        override fun bind(entity: EditEntity) = binding.run {
            // 配置 pattern (仅当 codePatterns 变化时重置, 避免 RecyclerView 复用导致重复添加)
            val lastPatterns = codeView.getTag(R.id.tag1) as? Int
            if (lastPatterns != entity.codePatterns) {
                codeView.resetSyntaxPatternList()
                entity.codePatterns.let { patterns ->
                    if (patterns and EditEntity.CodePattern.legado != 0) codeView.addLegadoPattern()
                    if (patterns and EditEntity.CodePattern.json != 0) codeView.addJsonPattern()
                    if (patterns and EditEntity.CodePattern.js != 0) codeView.addJsPattern()
                }
                codeView.setTag(R.id.tag1, entity.codePatterns)
            }
            // 移除旧 TextWatcher 避免复用时错位
            (codeView.getTag(R.id.tag2) as? TextWatcher)?.let {
                codeView.removeTextChangedListener(it)
            }
            codeView.setTag(R.id.tag, entity.key)
            // 仅在值不一致时 setText, 避免光标跳到开头 (CodeView 自带 isTextEqual 判断)
            if (!codeView.isTextEqual(codeView.text, entity.value)) {
                codeView.setText(entity.value)
            }
            textInputLayout.hint = entity.hint
            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                }

                override fun afterTextChanged(s: Editable?) {
                    entity.value = s?.toString()
                }
            }
            codeView.addTextChangedListener(textWatcher)
            codeView.setTag(R.id.tag2, textWatcher)
        }
    }

    class FormTextItemBinding(
        val root: TextInputLayout,
        val editText: AppCompatEditText
    ) : ViewBinding {
        override fun getRoot(): View = root
        val textInputLayout: TextInputLayout get() = root
    }

    class FormCheckBoxItemBinding(
        val root: CheckBox
    ) : ViewBinding {
        override fun getRoot(): View = root
        val checkBox: CheckBox get() = root
    }

    class FormSpinnerItemBinding(
        val root: LinearLayout,
        val textView: TextView,
        val spinner: AppCompatSpinner
    ) : ViewBinding {
        override fun getRoot(): View = root
    }

    class FormCodeItemBinding(
        val root: TextInputLayout,
        val codeView: CodeView
    ) : ViewBinding {
        override fun getRoot(): View = root
        val textInputLayout: TextInputLayout get() = root
    }

}
