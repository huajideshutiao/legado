package io.legado.app.ui.book.search

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.search.SearchAdapter.CallBack
import io.legado.app.ui.main.bookshelf.applyCoverWidth
import io.legado.app.ui.main.bookshelf.upIntro
import io.legado.app.ui.main.bookshelf.upKind
import io.legado.app.ui.main.bookshelf.upLast
import io.legado.app.utils.gone
import io.legado.app.utils.toTimeAgo
import io.legado.app.utils.visible


class BookAdapter(context: Context, val callBack: CallBack) :
    DiffRecyclerAdapter<Book, ItemBookshelfListBinding>(context) {

    override val diffItemCallback: DiffUtil.ItemCallback<Book>
        get() = object : DiffUtil.ItemCallback<Book>() {

            override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.bookUrl == newItem.bookUrl
            }

            override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.name == newItem.name
                    && oldItem.author == newItem.author
                    && oldItem.kind == newItem.kind
                    && oldItem.intro == newItem.intro
                    && oldItem.coverUrl == newItem.coverUrl
                    && oldItem.latestChapterTitle == newItem.latestChapterTitle
            }

            override fun getChangePayload(oldItem: Book, newItem: Book): Any {
                val payload = Bundle()
                if (oldItem.name != newItem.name) payload.putString("name", newItem.name)
                if (oldItem.author != newItem.author) payload.putString("author", newItem.author)
                if (oldItem.kind != newItem.kind) payload.putString("kind", newItem.kind)
                if (oldItem.intro != newItem.intro) payload.putString("intro", newItem.intro)
                if (oldItem.coverUrl != newItem.coverUrl) payload.putString(
                    "cover",
                    newItem.coverUrl
                )
                if (oldItem.latestChapterTitle != newItem.latestChapterTitle)
                    payload.putString("last", newItem.latestChapterTitle)
                return payload
            }
        }

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfListBinding {
        return ItemBookshelfListBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfListBinding,
        item: Book,
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
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.showBookInfo(it)
                }
            }
            setOnLongClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.showBookInfo(it, false)
                }
                true
            }
        }
    }

    private fun bind(binding: ItemBookshelfListBinding, item: Book) {
        binding.run {
            flHasNew.gone()
            ivRead.visible()
            tvRead.visible()
            tvIntro.visible()
            tvLastUpdateTime.visible()
            tvName.text = item.name
            tvAuthor.text = item.getRealAuthor()
            tvRead.text = item.durChapterTitle
            tvLastUpdateTime.text = item.durChapterTime.toTimeAgo()
            upIntro(item.intro)
            upKind(item.getKindList())
            upLast(item.latestChapterTitle)
            ivCover.load(
                item.coverUrl,
                item.name,
                item.author,
                AppConfig.loadCoverOnlyWifi,
                item.origin,
                inBookshelf = true
            )
        }
    }

    private fun bindChange(binding: ItemBookshelfListBinding, item: Book, bundle: Bundle) {
        binding.run {
            bundle.keySet().forEach {
                when (it) {
                    "name" -> tvName.text = item.name
                    "author" -> tvAuthor.text = item.getRealAuthor()
                    "intro" -> upIntro(item.intro)
                    "kind" -> upKind(item.getKindList())
                    "last" -> upLast(item.latestChapterTitle)
                    "cover" -> ivCover.load(
                        item.coverUrl,
                        item.name,
                        item.author,
                        AppConfig.loadCoverOnlyWifi,
                        item.origin,
                        inBookshelf = true
                    )
                }
            }
        }
    }

}
