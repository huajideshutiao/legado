package io.legado.app.ui.about

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.lib.theme.ThemeUtils
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick
import java.util.Date

class AppLogDialog : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy {
        LogAdapter()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setupTitleBar(
            title = getString(R.string.log),
            menuRes = R.menu.app_log,
            onMenuClick = ::onMenuItemClick
        )
        binding.run {
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = adapter
        }
        adapter.submitList(AppLog.logs)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_clear -> {
                AppLog.clear()
                adapter.submitList(emptyList())
            }
        }
        return true
    }

    private inner class LogAdapter :
        RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

        private var items: List<Triple<Long, String, Throwable?>> = emptyList()

        fun submitList(newItems: List<Triple<Long, String, Throwable?>>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val ctx = parent.context
            val padding = ctx.resources.getDimensionPixelSize(R.dimen.arco_spacing_default)
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                // 用主题属性 selectableItemBackground(ripple 水波纹),
                // 不用 list_selector_background(旧式黄色 state list, 视觉突兀)
                background =
                    ThemeUtils.resolveDrawable(ctx, android.R.attr.selectableItemBackground)
            }
            val textTime = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val textMessage = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setTextIsSelectable(true)
                autoLinkMask = android.text.util.Linkify.WEB_URLS
            }
            root.addView(textTime)
            root.addView(textMessage)
            return LogViewHolder(root, textTime, textMessage)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            val (time, message, throwable) = items[position]
            holder.textTime.text = LogUtils.logTimeFormat.format(Date(time))
            holder.textMessage.text = message
            holder.itemView.onClick {
                throwable?.let {
                    showDialogFragment(TextDialog("Log", it.stackTraceToString()))
                }
            }
        }

        override fun getItemCount() = items.size

        private inner class LogViewHolder(
            itemView: View,
            val textTime: TextView,
            val textMessage: TextView
        ) : RecyclerView.ViewHolder(itemView)
    }

}