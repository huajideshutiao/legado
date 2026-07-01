package io.legado.app.ui.widget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.annotation.VisibleForTesting
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
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
    // 拷贝一份 working, ok 时才写回, cancel/dismiss 不影响原状态
    val working = option.selectedValues.toMutableSet()
    context.alert(title = option.name) {
        val adapter = TagsAdapter(inflater, option.options, working)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val searchBox = EditText(context).apply {
            hint = context.getString(R.string.search)
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 4.dpToPx()) }
            doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }
        }
        val list = RecyclerView(context).apply {
            layoutManager = FlexboxLayoutManager(context).apply { flexWrap = FlexWrap.WRAP }
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            setPadding(6.dpToPx(), 4.dpToPx(), 6.dpToPx(), 8.dpToPx())
            clipToPadding = false
            // 给一个固定高度; wrap_content 会触发 FlexboxLayoutManager 预 measure 所有 item,
            // tag 上千时就变成一次性 inflate, 优化目的就白费了
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.resources.displayMetrics.heightPixels / 2
            )
            setAdapter(adapter)
        }
        container.addView(searchBox)
        container.addView(list)
        customView { container }
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

/**
 * Flexbox 布局下的 tag 列表 adapter,支持 O(n) 局部过滤但不改动 working 集合。
 * 过滤走全量替换 + notifyDataSetChanged: tag 上千时 DiffUtil 反而更贵,
 * 而且过滤结果几乎总是完全不同,增量刷新收益也小。
 */
private class TagsAdapter(
    private val inflater: LayoutInflater,
    private val allItems: List<Pair<String, String>>,
    private val working: MutableSet<String>,
) : RecyclerView.Adapter<TagsAdapter.VH>() {

    private var visible: List<Pair<String, String>> = allItems

    fun filter(query: String) {
        val trimmed = query.trim()
        val newVisible = if (trimmed.isEmpty()) {
            allItems
        } else {
            allItems.filter {
                it.first.contains(trimmed, ignoreCase = true) ||
                    it.second.contains(trimmed, ignoreCase = true)
            }
        }
        if (newVisible === visible) return
        visible = newVisible
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFilletTextBinding.inflate(inflater, parent, false)
        binding.root.layoutParams = FlexboxLayoutManager.LayoutParams(
            FlexboxLayoutManager.LayoutParams.WRAP_CONTENT,
            FlexboxLayoutManager.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(2.dpToPx(), 2.dpToPx(), 2.dpToPx(), 2.dpToPx()) }
        val vh = VH(binding)
        binding.root.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val value = visible[pos].second
            if (!working.add(value)) working.remove(value)
            binding.root.alpha = if (value in working) ALPHA_SELECTED else ALPHA_UNSELECTED
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (label, value) = visible[position]
        holder.binding.textView.text = label
        holder.binding.root.alpha = if (value in working) ALPHA_SELECTED else ALPHA_UNSELECTED
    }

    override fun getItemCount(): Int = visible.size

    @VisibleForTesting
    class VH(val binding: ItemFilletTextBinding) : RecyclerView.ViewHolder(binding.root)
}
