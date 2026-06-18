package io.legado.app.ui.book.search

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.applyCoverWidth
import io.legado.app.ui.main.bookshelf.upIntro
import io.legado.app.ui.main.bookshelf.upKind
import io.legado.app.ui.main.bookshelf.upLast
import io.legado.app.utils.gone


class SearchAdapter(context: Context, val callBack: CallBack) :
    DiffRecyclerAdapter<SearchBook, ItemBookshelfListBinding>(context) {

    override val keepScrollPosition = true

    override val diffItemCallback: DiffUtil.ItemCallback<SearchBook>
        get() = object : DiffUtil.ItemCallback<SearchBook>() {

            override fun areItemsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
                return when {
                    oldItem.name != newItem.name -> false
                    oldItem.author != newItem.author -> false
                    else -> true
                }
            }

            override fun areContentsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
                return false
            }

            override fun getChangePayload(oldItem: SearchBook, newItem: SearchBook): Any {
                val payload = Bundle()
                payload.putInt("origins", newItem.origins.size)
                if (oldItem.coverUrl != newItem.coverUrl)
                    payload.putString("cover", newItem.coverUrl)
                if (oldItem.kind != newItem.kind)
                    payload.putString("kind", newItem.kind)
                if (oldItem.latestChapterTitle != newItem.latestChapterTitle)
                    payload.putString("last", newItem.latestChapterTitle)
                if (oldItem.intro != newItem.intro)
                    payload.putString("intro", newItem.intro)
                return payload
            }

        }

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfListBinding {
        return ItemBookshelfListBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfListBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        binding.applyCoverWidth()
        if (payloads.isEmpty()) {
            bind(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bindChange(binding, item, bundle)
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookshelfListBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.showBookInfo(it.apply {
                    if (!binding.ivInBookshelf.isVisible)type = type or BookType.notShelf
                })
            }
        }
        binding.root.setOnLongClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.showBookInfo(it.apply {
                    if (!binding.ivInBookshelf.isVisible)type = type or BookType.notShelf
                }, false)
            }
            true
        }
    }

    private fun bind(binding: ItemBookshelfListBinding, searchBook: SearchBook) {
        binding.run {
            flHasNew.gone()
            tvName.text = searchBook.name
            tvAuthor.text = searchBook.author
            ivInBookshelf.isVisible = callBack.isInBookshelf(searchBook)
            bvOriginCount.setBadgeCount(searchBook.origins.size)
            upLast(searchBook.latestChapterTitle)
            upIntro(searchBook.trimIntro(context))
            upKind(searchBook.getKindList())
            ivCover.load(
                searchBook.coverUrl,
                searchBook.name,
                searchBook.author,
                AppConfig.loadCoverOnlyWifi,
                searchBook.origin,
                inBookshelf = ivInBookshelf.isVisible
            )
        }
    }

    private fun bindChange(
        binding: ItemBookshelfListBinding,
        searchBook: SearchBook,
        bundle: Bundle
    ) {
        binding.run {
            bundle.keySet().forEach {
                when (it) {
                    "origins" -> bvOriginCount.setBadgeCount(searchBook.origins.size)
                    "last" -> upLast(searchBook.latestChapterTitle)
                    "intro" -> upIntro(searchBook.trimIntro(context))
                    "kind" -> upKind(searchBook.getKindList())
                    "isInBookshelf" -> ivInBookshelf.isVisible = callBack.isInBookshelf(searchBook)
                    "cover" -> ivCover.load(
                        searchBook.coverUrl,
                        searchBook.name,
                        searchBook.author,
                        false,
                        searchBook.origin,
                        inBookshelf = ivInBookshelf.isVisible
                    )
                }
            }
        }
    }

    interface CallBack {

        /**
         * 是否已经加入书架
         */
        fun isInBookshelf(book: SearchBook): Boolean

        /**
         * 显示书籍详情
         */
        fun showBookInfo(book: BaseBook, isClick: Boolean = true)
    }
}