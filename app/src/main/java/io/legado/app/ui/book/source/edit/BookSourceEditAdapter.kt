package io.legado.app.ui.book.source.edit

import android.annotation.SuppressLint
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSpinner
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.applyThemeTree
import io.legado.app.lib.theme.space
import io.legado.app.lib.theme.viewHeight
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.ui.widget.text.EditEntity

class BookSourceEditAdapter : RecyclerView.Adapter<BookSourceEditAdapter.MyViewHolder>() {

    val editEntityMaxLine = AppConfig.sourceEditMaxLine

    var onSearchReplaceAction: ((String) -> Unit)? = null
    var onCodeViewFocus: ((CodeView) -> Unit)? = null

    var editEntities: ArrayList<EditEntity> = ArrayList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int {
        return editEntities[position].viewType
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            EditEntity.ViewType.spinner -> {
                val ctx = parent.context
                val root = LinearLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ctx.viewHeight.xl
                    )
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(
                        ctx.space.md,
                        ctx.space.default,
                        ctx.space.md,
                        ctx.space.default
                    )
                }
                val textView = TextView(ctx).apply {
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply {
                        marginEnd =
                            ctx.space.default
                    }
                    gravity = android.view.Gravity.CENTER_VERTICAL
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
                SpinnerViewHolder(SourceEditSpinnerItemBinding(root, textView, spinner))
            }
            else -> {
                val ctx = parent.context
                val root = TextInputLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(
                        0,
                        ctx.space.xs,
                        0,
                        0
                    )
                }
                val editText = CodeView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    inputType =
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                root.addView(editText)
                editText.isLineNumberEnabled = true
                editText.addLegadoPattern()
                editText.addJsPattern()
                editText.addJsonPattern()
                editText.onSearchReplaceAction = onSearchReplaceAction
                editText.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        onCodeViewFocus?.invoke(v as CodeView)
                    }
                }
                // 动态构造的 TextInputLayout + CodeView 不走 Factory2, 在 return 前整树着色
                // (TextInputLayout 的 boxStrokeColor/hintTextColor 和 CodeView 的底线/光标会被处理)
                root.applyThemeTree()
                TextViewHolder(SourceEditItemBinding(root, editText))
            }
        }
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        holder.bind(editEntities[position])
    }

    override fun getItemCount(): Int {
        return editEntities.size
    }

    abstract class MyViewHolder(binding: ViewBinding) :
        RecyclerView.ViewHolder(binding.root) {
        abstract fun bind(editEntity: EditEntity)
    }

    inner class TextViewHolder(private val binding: SourceEditItemBinding) :
        MyViewHolder(binding) {

        override fun bind(editEntity: EditEntity) = binding.run {
            (editText.getTag(R.id.tag3) as? Runnable)?.let {
                it.run()
                editText.removeCallbacks(it)
                editText.setTag(R.id.tag3, null)
            }
            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine
            if (editText.getTag(R.id.tag1) == null) {
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        editText.isCursorVisible = false
                        editText.isCursorVisible = true
                        editText.isFocusable = true
                        editText.isFocusableInTouchMode = true
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        (editText.getTag(R.id.tag3) as? Runnable)?.let {
                            it.run()
                            editText.removeCallbacks(it)
                            editText.setTag(R.id.tag3, null)
                        }
                    }
                }
                editText.addOnAttachStateChangeListener(listener)
                editText.setTag(R.id.tag1, listener)
            }
            editText.getTag(R.id.tag2)?.let {
                if (it is TextWatcher) {
                    editText.removeTextChangedListener(it)
                }
            }
            if (!editText.isTextEqual(editText.text, editEntity.value)) {
                editText.setText(editEntity.value)
            }
            textInputLayout.hint = editEntity.hint
            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int
                ) {

                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {

                }

                override fun afterTextChanged(s: Editable?) {
                    (editText.getTag(R.id.tag3) as? Runnable)?.let {
                        editText.removeCallbacks(it)
                    }
                    val runnable = Runnable {
                        editEntity.value = (s?.toString())
                        editText.setTag(R.id.tag3, null)
                    }
                    editText.setTag(R.id.tag3, runnable)
                    editText.postDelayed(runnable, 500)
                }
            }
            editText.addTextChangedListener(textWatcher)
            editText.setTag(R.id.tag2, textWatcher)
        }
    }

    class SourceEditItemBinding(
        val root: TextInputLayout,
        val editText: CodeView
    ) : ViewBinding {
        override fun getRoot(): View = root
        val textInputLayout: TextInputLayout get() = root
    }

    class SpinnerViewHolder(private val binding: SourceEditSpinnerItemBinding) :
        MyViewHolder(binding) {

        override fun bind(editEntity: EditEntity) = binding.run {
            val context = root.context
            textView.text = editEntity.hint
            val selections = editEntity.selections.orEmpty()
            val labels = selections.map { it.first }
            val arrayAdapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                labels
            )
            arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.onItemSelectedListener = null
            spinner.adapter = arrayAdapter
            val currentIndex = selections.indexOfFirst { it.second == editEntity.value }
                .let { if (it < 0) 0 else it }
            spinner.setSelection(currentIndex)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    editEntity.value = selections.getOrNull(position)?.second
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    class SourceEditSpinnerItemBinding(
        val root: LinearLayout,
        val textView: TextView,
        val spinner: AppCompatSpinner
    ) : ViewBinding {
        override fun getRoot(): View = root
    }


}
