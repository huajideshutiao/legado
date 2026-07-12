package io.legado.app.ui.book.search

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.ui.book.explore.BaseExploreShowAdapter
import io.legado.app.ui.main.bookshelf.bindExploreCard
import io.legado.app.ui.main.bookshelf.upIntro
import io.legado.app.ui.main.bookshelf.upKind
import io.legado.app.ui.main.bookshelf.upLast
import io.legado.app.ui.main.bookshelf.upRead
import io.legado.app.utils.gone
import io.legado.app.utils.toTimeAgo
import io.legado.app.utils.visible


/**
 * 搜索界面顶部的书架命中区 List tier. 数据是本地 [Book], 显示当前阅读进度 (tvRead) 与
 * 上次阅读时间 (tvLastUpdateTime); 徽标恒隐 (item 都在书架里).
 */
class BookAdapter(
    context: Context,
    callBack: BaseExploreShowAdapter.CallBack,
    private val isVideoStyle: Boolean = false,
) : BaseExploreShowAdapter<ItemBookshelfListBinding>(
    context,
    callBack,
    showBookshelfBadge = false
) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfListBinding {
        return ItemBookshelfListBinding.inflate(inflater, parent, false)
    }

    override fun bind(binding: ItemBookshelfListBinding, item: BaseBook) {
        val book = item as? Book ?: return
        binding.run {
            flHasNew.gone()
            bindExploreCard(
                item = book,
                coverUrl = book.getDisplayCover(),
                isVideoStyle = isVideoStyle,
                isInBookshelf = true,
                showBookshelfBadge = false,
                loadCoverOnlyWifi = false,
            )
            upRead(book.durChapterTitle)
            tvLastUpdateTime.visible()
            tvLastUpdateTime.text = book.durChapterTime.toTimeAgo()
            upKind(book.getKindList())
            upIntro(book.intro)
        }
    }

    override fun bindChange(
        binding: ItemBookshelfListBinding,
        item: BaseBook,
        bundle: Bundle
    ) {
        val book = item as? Book ?: return
        binding.run {
            bundle.keySet().forEach {
                when (it) {
                    "name" -> tvName.text = book.name
                    "author" -> tvAuthor.text = book.getRealAuthor()
                    "intro" -> upIntro(book.intro)
                    "kind" -> upKind(book.getKindList())
                    "last" -> upLast(book.latestChapterTitle)
                    "cover" -> ivCover.load(
                        book.getDisplayCover(),
                        book.name,
                        book.author,
                        false,
                        book.origin,
                        inBookshelf = true
                    )
                }
            }
        }
    }

    companion object {
        val itemCallback = object : DiffUtil.ItemCallback<BaseBook>() {

            override fun areItemsTheSame(oldItem: BaseBook, newItem: BaseBook): Boolean {
                return oldItem.bookUrl == newItem.bookUrl
            }

            override fun areContentsTheSame(oldItem: BaseBook, newItem: BaseBook): Boolean {
                return oldItem.name == newItem.name
                    && oldItem.author == newItem.author
                    && oldItem.kind == newItem.kind
                    && oldItem.intro == newItem.intro
                    && oldItem.coverUrl == newItem.coverUrl
                    && oldItem.latestChapterTitle == newItem.latestChapterTitle
            }

            override fun getChangePayload(oldItem: BaseBook, newItem: BaseBook): Any {
                val payload = Bundle()
                if (oldItem.name != newItem.name) payload.putString("name", newItem.name)
                if (oldItem.author != newItem.author) payload.putString("author", newItem.author)
                if (oldItem.kind != newItem.kind) payload.putString("kind", newItem.kind)
                if (oldItem.intro != newItem.intro) payload.putString("intro", newItem.intro)
                if (oldItem.coverUrl != newItem.coverUrl) payload.putString(
                    "cover", newItem.coverUrl
                )
                if (oldItem.latestChapterTitle != newItem.latestChapterTitle)
                    payload.putString("last", newItem.latestChapterTitle)
                return payload
            }
        }
    }
}
