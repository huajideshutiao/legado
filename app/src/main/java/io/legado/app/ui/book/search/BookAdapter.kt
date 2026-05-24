package io.legado.app.ui.book.search

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemSearchBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.search.SearchAdapter.CallBack
import io.legado.app.utils.gone
import io.legado.app.utils.visible


class BookAdapter(context: Context, val callBack: CallBack) :
    DiffRecyclerAdapter<Book, ItemSearchBinding>(context) {

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

    override fun getViewBinding(parent: ViewGroup): ItemSearchBinding {
        return ItemSearchBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemSearchBinding,
        item: Book,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            bind(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bindChange(binding, item, bundle)
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemSearchBinding) {
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

    private fun bind(binding: ItemSearchBinding, item: Book) {
        binding.run {
            tvName.text = item.name
            tvAuthor.text = context.getString(R.string.author_show, item.author)
            tvIntroduce.text = context.getString(R.string.intro_show, item.intro)
            upKind(binding, item.getKindList())
            upLasted(binding, item.latestChapterTitle)
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

    private fun bindChange(binding: ItemSearchBinding, item: Book, bundle: Bundle) {
        binding.run {
            bundle.keySet().forEach {
                when (it) {
                    "name" -> tvName.text = item.name
                    "author" -> tvAuthor.text =
                        context.getString(R.string.author_show, item.author)

                    "intro" -> tvIntroduce.text =
                        context.getString(R.string.intro_show, item.intro)

                    "kind" -> upKind(binding, item.getKindList())
                    "last" -> upLasted(binding, item.latestChapterTitle)
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

    private fun upLasted(binding: ItemSearchBinding, latestChapterTitle: String?) {
        binding.run {
            if (latestChapterTitle.isNullOrEmpty()) {
                tvLasted.gone()
            } else {
                tvLasted.text =
                    context.getString(R.string.lasted_show, latestChapterTitle)
                tvLasted.visible()
            }
        }
    }

    private fun upKind(binding: ItemSearchBinding, kinds: List<String>) = binding.run {
        if (kinds.isEmpty()) {
            llKind.gone()
        } else {
            llKind.visible()
            llKind.setLabels(kinds)
        }
    }
}
