package io.legado.app.ui.main.bookshelf.style2

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.ui.main.bookshelf.applyCoverHeight
import io.legado.app.ui.main.bookshelf.bindExploreCard
import io.legado.app.ui.main.bookshelf.upRead
import io.legado.app.ui.main.bookshelf.upRefresh
import io.legado.app.utils.gone
import io.legado.app.utils.visible
import splitties.views.onLongClick

class BooksAdapterList(context: Context, callBack: CallBack, private val isVideoStyle: Boolean) :
    BaseBooksAdapter<RecyclerView.ViewHolder>(context, callBack) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val binding = ItemBookshelfListBinding.inflate(inflater, parent, false)
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

    inner class BookViewHolder(val binding: ItemBookshelfListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: Book) = binding.run {
            bindExploreCard(
                item = item,
                coverUrl = item.getDisplayCover(),
                isVideoStyle = isVideoStyle,
                isInBookshelf = true,
                showBookshelfBadge = false,
                loadCoverOnlyWifi = false,
            )
            flHasNew.visible()
            ivAuthor.visible()
            tvAuthor.visible()
            upRead(item.durChapterTitle)
            upRefresh(item, callBack.isUpdate(item.bookUrl))
        }

        fun onBind(item: Book, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                onBind(item)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "name" -> tvName.text = item.name
                            "author" -> tvAuthor.text = item.getRealAuthor()
                            "dur" -> tvRead.text = item.durChapterTitle
                            "last" -> tvLast.text = item.latestChapterTitle
                            "cover" -> ivCover.load(
                                item.getDisplayCover(),
                                item.name,
                                item.author,
                                false,
                                item.origin,
                                inBookshelf = true
                            )

                            "refresh" -> upRefresh(item, callBack.isUpdate(item.bookUrl))
                        }
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

    }

    inner class GroupViewHolder(val binding: ItemBookshelfListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: BookGroup) = binding.run {
            applyCoverHeight(isVideoStyle)
            tvName.text = item.groupName
            ivCover.load(item.cover, inBookshelf = true)
            flHasNew.gone()
            ivAuthor.gone()
            ivLast.gone()
            tvAuthor.gone()
            tvLast.gone()
        }

        fun onBind(item: BookGroup, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                onBind(item)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "groupName" -> tvName.text = item.groupName
                            "cover" -> ivCover.load(item.cover, inBookshelf = true)
                        }
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

    }

}
