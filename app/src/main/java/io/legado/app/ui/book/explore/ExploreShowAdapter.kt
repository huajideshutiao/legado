package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.applyCoverWidth
import io.legado.app.ui.main.bookshelf.upIntro
import io.legado.app.ui.main.bookshelf.upKind
import io.legado.app.ui.main.bookshelf.upLast
import io.legado.app.utils.gone


class ExploreShowAdapter(context: Context, callBack: CallBack) :
    BaseExploreShowAdapter<ItemBookshelfListBinding>(context, callBack) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfListBinding {
        return ItemBookshelfListBinding.inflate(inflater, parent, false)
    }

    override fun bind(binding: ItemBookshelfListBinding, item: SearchBook) {
        binding.run {
            applyCoverWidth()
            flHasNew.gone()
            tvName.text = item.name
            tvAuthor.text = item.getRealAuthor()
            ivInBookshelf.isVisible = callBack.isInBookshelf(item)
            upLast(item.latestChapterTitle)
            upIntro(item.trimIntro(context))
            upKind(item.getKindList())
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