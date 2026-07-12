package io.legado.app.ui.main.bookshelf.style2

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ItemExploreVideoBinding
import io.legado.app.model.BookCover
import io.legado.app.ui.book.explore.bindVideoCard
import io.legado.app.utils.gone
import io.legado.app.utils.visible
import splitties.views.onLongClick

/**
 * 书架 style2 的 isVideo tier: 复用 item_explore_video 卡片, Book 走 [bindVideoCard],
 * BookGroup 走本地的组名 + 封面绑定 (与 [BooksAdapterList] 的 GroupViewHolder 对齐).
 * 书架内所有 book 都已入库, ivInBookshelf 徽标恒隐避免噪音.
 */
class BooksAdapterVideo(context: Context, callBack: CallBack) :
    BaseBooksAdapter<RecyclerView.ViewHolder>(context, callBack) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val binding = ItemExploreVideoBinding.inflate(inflater, parent, false)
        return when (viewType) {
            1 -> GroupViewHolder(binding)
            else -> BookViewHolder(binding)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        when (holder) {
            is BookViewHolder -> (getItem(position) as? Book)?.let {
                holder.registerListener(it)
                holder.onBind(it, payloads)
            }

            is GroupViewHolder -> (getItem(position) as? BookGroup)?.let {
                holder.registerListener(it)
                holder.onBind(it, payloads)
            }
        }
    }

    inner class BookViewHolder(val binding: ItemExploreVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: Book) {
            binding.bindVideoCard(
                item,
                item.getDisplayCover(),
                isInBookshelf = true,
                showBookshelfBadge = false,
            )
        }

        fun onBind(item: Book, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                onBind(item)
                return
            }
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bundle.keySet().forEach {
                    when (it) {
                        "name", "author", "cover" -> onBind(item)
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener { callBack.onItemClick(item) }
            binding.root.onLongClick { callBack.onItemLongClick(item) }
        }
    }

    inner class GroupViewHolder(val binding: ItemExploreVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: BookGroup) = binding.run {
            tvTitle.text = item.groupName
            tvAuthor.isVisible = false
            llKind.isVisible = false
            ivInBookshelf.isVisible = false
            ivCover.coverRatio = BookCover.CoverRatio.VIDEO
            if (item.cover.isNullOrBlank()) {
                ivCover.gone()
            } else {
                ivCover.visible()
                ivCover.load(item.cover, inBookshelf = true)
            }
        }

        fun onBind(item: BookGroup, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                onBind(item)
                return
            }
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bundle.keySet().forEach {
                    when (it) {
                        "groupName", "cover" -> onBind(item)
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener { callBack.onItemClick(item) }
            binding.root.onLongClick { callBack.onItemLongClick(item) }
        }
    }
}
