package io.legado.app.ui.widget

import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.utils.dpToPx

fun LinearLayout.setUpExploreOptions(
    options: List<ExploreOption>,
    onOptionSelected: (option: ExploreOption) -> Unit
) {
    if (options.isEmpty()) {
        isVisible = false
        return
    }
    isVisible = true
    removeAllViews()
    val context = context
    options.forEach { option ->
        val scrollView = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            isFillViewport = true
            scrollBarSize = 0
        }
        val linearLayout = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(8.dpToPx(), 4.dpToPx(), 8.dpToPx(), 4.dpToPx())
        }
        scrollView.addView(linearLayout)
        addView(scrollView)

        ItemFilletTextBinding.inflate(android.view.LayoutInflater.from(context), linearLayout, true)
            .apply {
                textView.text = option.name
                textView.alpha = 0.8f
                textView.paint.isFakeBoldText = true
            }
        option.options.forEach { pair ->
            val itemBinding = ItemFilletTextBinding.inflate(
                android.view.LayoutInflater.from(context), linearLayout, true
            )
            itemBinding.textView.text = pair.first
            itemBinding.root.tag = pair.second
            itemBinding.textView.alpha = if (pair.second == option.selectedValue) 1.0f else 0.5f
            itemBinding.root.setOnClickListener {
                if (option.selectedValue != pair.second) {
                    option.selectedValue = pair.second
                    for (i in 0 until linearLayout.childCount) {
                        val child = linearLayout.getChildAt(i)
                        if (child.tag != null) {
                            child.alpha = if (child.tag == option.selectedValue) 1.0f else 0.5f
                        }
                    }
                    onOptionSelected(option)
                }
            }
        }
    }
}
