package io.legado.app.ui.widget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.cancelButton
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.neutralButton
import io.legado.app.lib.dialogs.okButton
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.utils.dpToPx

private const val ALPHA_TITLE = 0.8f
private const val ALPHA_SELECTED = 1.0f
private const val ALPHA_UNSELECTED = 0.5f

fun LinearLayout.setUpExploreOptions(
    options: List<ExploreOption>,
    onOptionSelected: (option: ExploreOption) -> Unit
) {
    removeAllViews()
    isVisible = options.isNotEmpty()
    if (options.isEmpty()) return
    val inflater = LayoutInflater.from(context)
    options.forEach { option ->
        addView(buildOptionRow(inflater, option, onOptionSelected))
    }
}

private fun LinearLayout.buildOptionRow(
    inflater: LayoutInflater,
    option: ExploreOption,
    onOptionSelected: (option: ExploreOption) -> Unit
): HorizontalScrollView {
    val row = LinearLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        orientation = LinearLayout.HORIZONTAL
        setPadding(8.dpToPx(), 4.dpToPx(), 8.dpToPx(), 4.dpToPx())
    }
    if (option.multiSelect) {
        bindMultiSelect(inflater, row, option, onOptionSelected)
    } else {
        bindSingleSelect(inflater, row, option, onOptionSelected)
    }
    return HorizontalScrollView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        isFillViewport = true
        scrollBarSize = 0
        addView(row)
    }
}

private fun addTitleChip(
    inflater: LayoutInflater,
    container: LinearLayout,
    text: String,
    onClick: View.OnClickListener?
) {
    val binding = ItemFilletTextBinding.inflate(inflater, container, true)
    binding.textView.text = text
    binding.textView.alpha = ALPHA_TITLE
    binding.textView.paint.isFakeBoldText = true
    if (onClick != null) binding.root.setOnClickListener(onClick)
}

private fun bindSingleSelect(
    inflater: LayoutInflater,
    row: LinearLayout,
    option: ExploreOption,
    onOptionSelected: (option: ExploreOption) -> Unit
) {
    fun refreshAlpha() {
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            val tag = child.tag as? String ?: continue
            child.alpha = if (option.selectedValue == tag) ALPHA_SELECTED else ALPHA_UNSELECTED
        }
    }
    addTitleChip(inflater, row, option.name) {
        if (option.resetToDefault()) {
            refreshAlpha()
            onOptionSelected(option)
        }
    }
    option.options.forEach { (label, value) ->
        val binding = ItemFilletTextBinding.inflate(inflater, row, true)
        binding.textView.text = label
        binding.root.tag = value
        binding.root.alpha = if (option.selectedValue == value) ALPHA_SELECTED else ALPHA_UNSELECTED
        binding.root.setOnClickListener {
            if (option.selectedValue == value) return@setOnClickListener
            option.selectedValue = value
            refreshAlpha()
            onOptionSelected(option)
        }
    }
}

private fun bindMultiSelect(
    inflater: LayoutInflater,
    row: LinearLayout,
    option: ExploreOption,
    onOptionSelected: (option: ExploreOption) -> Unit
) {
    fun render() {
        row.removeAllViews()
        // 多选模式下 title chip + 已选 chip 的点击行为完全一致 (打开对话框),
        // 不在每个 chip 上重复挂 listener: chip 是 TextView 默认 unclickable,
        // touch 会冒泡到 row, 整行 (含 chip 之间空白) 都能触发, 扩大点击区域。
        addTitleChip(inflater, row, option.name, null)
        option.options.forEach { (label, value) ->
            if (value !in option.selectedValues) return@forEach
            val binding = ItemFilletTextBinding.inflate(inflater, row, true)
            binding.textView.text = label
        }
    }
    row.setOnClickListener {
        showMultiSelectDialog(inflater, option) {
            render()
            onOptionSelected(option)
        }
    }
    render()
}

private fun showMultiSelectDialog(
    inflater: LayoutInflater,
    option: ExploreOption,
    onConfirmed: () -> Unit
) {
    val context = inflater.context
    val working = option.selectedValues.toMutableSet()
    context.alert(title = option.name) {
        val flexbox = FlexboxLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            flexWrap = FlexWrap.WRAP
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }
        option.options.forEach { (label, value) ->
            val binding = ItemFilletTextBinding.inflate(inflater, flexbox, false)
            binding.textView.text = label
            binding.root.alpha = if (value in working) ALPHA_SELECTED else ALPHA_UNSELECTED
            binding.root.setOnClickListener {
                if (!working.add(value)) working.remove(value)
                binding.root.alpha = if (value in working) ALPHA_SELECTED else ALPHA_UNSELECTED
            }
            val lp = FlexboxLayout.LayoutParams(
                FlexboxLayout.LayoutParams.WRAP_CONTENT,
                FlexboxLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(2.dpToPx(), 2.dpToPx(), 2.dpToPx(), 2.dpToPx()) }
            flexbox.addView(binding.root, lp)
        }
        customView {
            NestedScrollView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(flexbox)
            }
        }
        okButton {
            if (working != option.selectedValues) {
                option.selectedValues.clear()
                option.selectedValues.addAll(working)
                onConfirmed()
            }
        }
        neutralButton(R.string.clear) {
            if (option.selectedValues.isNotEmpty()) {
                option.selectedValues.clear()
                onConfirmed()
            }
        }
        cancelButton()
    }
}
