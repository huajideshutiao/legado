package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.applyCoverWidth
import io.legado.app.ui.main.bookshelf.upIntro
import io.legado.app.ui.main.bookshelf.upKind
import io.legado.app.ui.main.bookshelf.upLastUpdateTime
import io.legado.app.ui.main.bookshelf.upRefresh
import io.legado.app.utils.visible
import splitties.views.onLongClick

class BooksAdapterList(
    context: Context,
    private val fragment: Fragment,
    private val callBack: CallBack,
    private val lifecycle: Lifecycle
) : BaseBooksAdapter<ItemBookshelfListBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfListBinding {
        return ItemBookshelfListBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfListBinding,
        item: Book,
        payloads: MutableList<Any>
    ) = binding.run {
        applyCoverWidth()
        if (payloads.isEmpty()) {
            tvName.text = item.name
            tvAuthor.text = item.author
            tvRead.text = item.durChapterTitle
            tvLast.text = item.latestChapterTitle
            ivRead.visible()
            tvRead.visible()
            ivLast.visible()
            tvLast.visible()
            ivCover.load(item.getDisplayCover(), item.name, item.author, false, item.origin, inBookshelf = true)
            upRefresh(item, callBack.isUpdate(item.bookUrl))
            upLastUpdateTime(item)
            upKindAndIntro(item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bundle.keySet().forEach {
                    when (it) {
                        "name" -> tvName.text = item.name
                        "author" -> tvAuthor.text = item.author
                        "dur" -> tvRead.text = item.durChapterTitle
                        "last" -> tvLast.text = item.latestChapterTitle
                        "cover" -> ivCover.load(
                            item.getDisplayCover(),
                            item.name,
                            item.author,
                            false,
                            item.origin,
                            fragment,
                            lifecycle,
                            true
                        )

                        "refresh" -> upRefresh(item, callBack.isUpdate(item.bookUrl))
                        "lastUpdateTime" -> upLastUpdateTime(item)
                    }
                }
            }
        }
    }

    private fun ItemBookshelfListBinding.upKindAndIntro(item: Book) {
        upKind(item.getKindList(), AppConfig.bookshelfListShowKind)
        upIntro(
            item.getDisplayIntro(),
            maxLines = AppConfig.bookshelfListIntroLines,
            show = AppConfig.bookshelfListShowIntro,
        )
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookshelfListBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.open(it)
                }
            }

            onLongClick {
                getItem(holder.layoutPosition)?.let {
                    callBack.openBookInfo(it)
                }
            }
        }
    }
}
