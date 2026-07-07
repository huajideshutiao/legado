package io.legado.app.ui.book.source.debug

import android.content.Context
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter

class BookSourceDebugAdapter(context: Context) :
    RecyclerAdapter<String, BookSourceDebugAdapter.LogBinding>(context) {

    class LogBinding(val root: TextView) : ViewBinding {
        override fun getRoot(): View = root
    }

    override fun getViewBinding(parent: ViewGroup): LogBinding {
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setTextIsSelectable(true)
            autoLinkMask = Linkify.WEB_URLS
        }
        return LogBinding(tv)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: LogBinding,
        item: String,
        payloads: MutableList<Any>
    ) {
        val tv = binding.root
        if (tv.getTag(R.id.tag1) == null) {
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    tv.isCursorVisible = false
                    tv.isCursorVisible = true
                }

                override fun onViewDetachedFromWindow(v: View) {}
            }
            tv.addOnAttachStateChangeListener(listener)
            tv.setTag(R.id.tag1, listener)
        }
        tv.text = item
    }

    override fun registerListener(holder: ItemViewHolder, binding: LogBinding) {
        //nothing
    }
}
