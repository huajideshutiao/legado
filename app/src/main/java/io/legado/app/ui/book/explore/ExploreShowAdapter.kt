package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.gone
import io.legado.app.utils.visible


class ExploreShowAdapter(context: Context, callBack: CallBack) :
    BaseExploreShowAdapter<ItemBookshelfListBinding>(context, callBack) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfListBinding {
        return ItemBookshelfListBinding.inflate(inflater, parent, false)
    }

    override fun bind(binding: ItemBookshelfListBinding, item: SearchBook) {
        binding.run {
            flHasNew.gone()
            tvIntro.visible()
            tvName.text = item.name
            tvAuthor.text = item.author
            ivInBookshelf.isVisible = callBack.isInBookshelf(item)
            if (item.latestChapterTitle.isNullOrEmpty()) {
                ivLast.gone()
                tvLast.gone()
            } else {
                tvLast.text = item.latestChapterTitle
                ivLast.visible()
                tvLast.visible()
            }
            tvIntro.text = item.trimIntro(context)
            val kinds = item.getKindList()
            if (kinds.isEmpty()) {
                llKind.gone()
            } else {
                llKind.visible()
                llKind.setLabels(kinds)
            }
            ivCover.load(
                item.coverUrl,
                item.name,
                item.author,
                AppConfig.loadCoverOnlyWifi,
                item.origin,
                inBookshelf = ivInBookshelf.isVisible
            )
        }
    }

    override fun bindChange(binding: ItemBookshelfListBinding, item: SearchBook, bundle: Bundle) {
        binding.run {
            bundle.keySet().forEach {
                when (it) {
                    "isInBookshelf" -> ivInBookshelf.isVisible =
                        callBack.isInBookshelf(item)
                }
            }
        }
    }
}