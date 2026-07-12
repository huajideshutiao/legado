package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.ui.main.bookshelf.bindExploreCard
import io.legado.app.ui.main.bookshelf.upIntro
import io.legado.app.ui.main.bookshelf.upKind
import io.legado.app.ui.main.bookshelf.upLast
import io.legado.app.utils.gone


/**
 * 发现/搜索/搜索里书架命中区共用的 List tier adapter.
 * - showOrigins: 搜索场景要显示多源徽标 (SearchBook.origins.size), 发现场景 false.
 * - showBookshelfBadge: 书架命中区 false, 徽标恒隐 (item 都在书架里, 徽标是噪音).
 */
class ExploreShowAdapter(
    context: Context,
    callBack: CallBack,
    private val isVideoStyle: Boolean = false,
    showBookshelfBadge: Boolean = true,
    private val showOrigins: Boolean = false,
) : BaseExploreShowAdapter<ItemBookshelfListBinding>(context, callBack, showBookshelfBadge) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfListBinding {
        return ItemBookshelfListBinding.inflate(inflater, parent, false)
    }

    override fun bind(binding: ItemBookshelfListBinding, item: BaseBook) {
        binding.run {
            flHasNew.gone()
            bindExploreCard(
                item = item,
                coverUrl = item.coverUrl,
                isVideoStyle = isVideoStyle,
                isInBookshelf = callBack.isInBookshelf(item),
                showBookshelfBadge = showBookshelfBadge,
                showOrigins = showOrigins,
            )
            upKind(item.getKindList())
            if (item is SearchBook) {
                upIntro(item.trimIntro(context))
            } else {
                upIntro(item.intro)
            }
        }
    }

    override fun bindChange(binding: ItemBookshelfListBinding, item: BaseBook, bundle: Bundle) {
        binding.run {
            bundle.keySet().forEach {
                when (it) {
                    "origins" -> if (showOrigins && item is SearchBook) {
                        bvOriginCount.setBadgeCount(item.origins.size)
                    }

                    "last" -> upLast(item.latestChapterTitle)
                    "intro" -> if (item is SearchBook) {
                        upIntro(item.trimIntro(context))
                    } else {
                        upIntro(item.intro)
                    }

                    "kind" -> upKind(item.getKindList())
                    "isInBookshelf" -> ivInBookshelf.isVisible =
                        showBookshelfBadge && callBack.isInBookshelf(item)

                    "cover" -> ivCover.load(
                        item.coverUrl,
                        item.name,
                        item.author,
                        false,
                        item.origin,
                        inBookshelf = callBack.isInBookshelf(item)
                    )
                }
            }
        }
    }

    companion object {
        val itemCallback = object : DiffUtil.ItemCallback<BaseBook>() {

            override fun areItemsTheSame(oldItem: BaseBook, newItem: BaseBook): Boolean {
                return oldItem.name == newItem.name && oldItem.author == newItem.author
            }

            override fun areContentsTheSame(oldItem: BaseBook, newItem: BaseBook): Boolean {
                return false
            }

            override fun getChangePayload(oldItem: BaseBook, newItem: BaseBook): Any {
                val payload = Bundle()
                if (oldItem.coverUrl != newItem.coverUrl) payload.putString(
                    "cover", newItem.coverUrl
                )
                if (oldItem.kind != newItem.kind) payload.putString("kind", newItem.kind)
                if (oldItem.latestChapterTitle != newItem.latestChapterTitle) payload.putString(
                    "last", newItem.latestChapterTitle
                )
                if (oldItem.intro != newItem.intro) payload.putString("intro", newItem.intro)
                if (newItem is SearchBook) payload.putInt("origins", newItem.origins.size)
                return payload
            }
        }
    }
}
