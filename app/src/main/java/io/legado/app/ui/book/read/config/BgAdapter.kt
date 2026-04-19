package io.legado.app.ui.book.read.config

import android.content.Context
import android.view.ViewGroup
import com.bumptech.glide.Glide
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ItemBgImageBinding
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.RemoteAssetsUtils
import io.legado.app.utils.postEvent

class BgAdapter(context: Context, val textColor: Int) :
    RecyclerAdapter<String, ItemBgImageBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemBgImageBinding {
        return ItemBgImageBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBgImageBinding,
        item: String,
        payloads: MutableList<Any>
    ) {
        binding.run {
            val previewBytes = RemoteAssetsUtils.getBgPreviewBytes(item)
            if (previewBytes != null) {
                Glide.with(context).load(previewBytes)
                    .centerCrop()
                    .into(ivBg)
            }
            tvName.setTextColor(textColor)
            tvName.text = item.substringBeforeLast(".")
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBgImageBinding) {
        holder.itemView.apply {
            this.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    ReadBookConfig.durConfig.setCurBg(1, it)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(1))
                }
            }
        }
    }
}