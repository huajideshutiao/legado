package io.legado.app.ui.book.read.config

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.RemoteAssetsUtils
import io.legado.app.utils.postEvent

class BgAdapter(context: Context, val textColor: Int) :
    RecyclerAdapter<String, BgAdapter.BgBinding>(context) {

    class BgBinding(
        val root: LinearLayout,
        val ivBg: ImageView,
        val tvName: TextView
    ) : ViewBinding {
        override fun getRoot(): View = root
    }

    override fun getViewBinding(parent: ViewGroup): BgBinding {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(2, 2, 2, 2)
            layoutParams = ViewGroup.LayoutParams(66, 88)
        }
        val ivBg = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val tvName = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isSingleLine = true
            gravity = Gravity.CENTER
        }
        root.addView(ivBg)
        root.addView(tvName)
        return BgBinding(root, ivBg, tvName)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: BgBinding,
        item: String,
        payloads: MutableList<Any>
    ) {
        val previewBytes = RemoteAssetsUtils.getBgPreviewBytes(item)
        if (previewBytes != null) {
            Glide.with(context).load(previewBytes)
                .centerCrop()
                .into(binding.ivBg)
        }
        binding.tvName.setTextColor(textColor)
        binding.tvName.text = item.substringBeforeLast(".")
    }

    override fun registerListener(holder: ItemViewHolder, binding: BgBinding) {
        holder.itemView.setOnClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.let {
                ReadBookConfig.durConfig.setCurBg(1, it)
                postEvent(EventBus.UP_CONFIG, arrayListOf(1))
            }
        }
    }
}
