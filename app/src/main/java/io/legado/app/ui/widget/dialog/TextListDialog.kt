package io.legado.app.ui.widget.dialog

import android.content.Context
import android.os.Bundle
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding

@Suppress("unused")
class TextListDialog() : BaseDialogFragment(R.layout.dialog_recycler_view) {

    override val isFullHeight: Boolean = true

    constructor(title: String, values: ArrayList<String>) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putStringArrayList("values", values)
        }
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { TextAdapter(requireContext()) }
    private var values: ArrayList<String>? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        arguments?.let {
            setupTitleBar(title = it.getString("title"))
            values = it.getStringArrayList("values")
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        adapter.setItems(values)
    }

    class TextAdapter(context: Context) :
        RecyclerAdapter<String, TextAdapter.LogBinding>(context) {

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

}
