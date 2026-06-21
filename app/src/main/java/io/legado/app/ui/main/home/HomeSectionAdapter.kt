package io.legado.app.ui.main.home

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemHomeCoverCardBinding
import io.legado.app.databinding.ItemHomeFourPageBinding
import io.legado.app.databinding.ItemHomeRankBookBinding
import io.legado.app.databinding.ViewHomeSectionTitleBinding
import io.legado.app.model.BookCover
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.visible

/**
 * 主页 header 视图构建：把各展示项动态渲染进竖向 LinearLayout（header）。
 * 提取了统一的标题栏布局，并采用代码动态创建 RecyclerView 以简化 XML。
 */
class HomeSectionAdapter(
    private val context: Context,
    private val container: LinearLayout,
    private val callback: Callback
) {

    private val inflater = LayoutInflater.from(context)
    private val holders = LinkedHashMap<String, SectionHolder>()

    fun setSections(sections: List<HomeSection>, booksMap: Map<String, List<SearchBook>>) {
        container.removeAllViews()
        holders.clear()
        sections.forEach { section ->
            val holder = SectionHolder(section)
            holders[section.id] = holder
            container.addView(holder.root)
            holder.updateBooks(booksMap[section.id] ?: emptyList())
            holder.updateLoading(callback.isLoading(section.id))
        }
    }

    fun updateSectionBooks(sectionId: String, books: List<SearchBook>) {
        holders[sectionId]?.updateBooks(books)
    }

    fun updateSectionLoading(sectionId: String) {
        holders[sectionId]?.updateLoading(callback.isLoading(sectionId))
    }

    private fun onBook(section: HomeSection, book: SearchBook, longClick: Boolean) {
        callback.onBookClick(book, section, longClick)
    }

    // ─── Section holder ──────────────────────────────────────────────────

    private inner class SectionHolder(val section: HomeSection) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dpToPx(), 0, 4.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleBinding = ViewHomeSectionTitleBinding.inflate(inflater, root, true).apply {
            tvSectionTitle.text = section.title
            llTitle.setOnClickListener { callback.onMoreClick(section) }
        }

        private var adapter: RecyclerView.Adapter<*>? = null

        init {
            if (section.style != HomeSection.STYLE_INFINITE_GRID) {
                val rv = RecyclerView(context).apply {
                    setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
                    clipToPadding = false
                    isNestedScrollingEnabled = false

                    when (section.style) {
                        HomeSection.STYLE_COVER_ROW -> {
                            layoutManager =
                                LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                            this@SectionHolder.adapter =
                                CoverCardAdapter(section.coverVideo) { book, lc ->
                                    onBook(section, book, lc)
                                }
                            minimumHeight = 160.dpToPx()
                        }

                        HomeSection.STYLE_FOUR_ROW -> {
                            layoutManager =
                                LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                            this@SectionHolder.adapter = FourColumnAdapter { book, lc ->
                                onBook(section, book, lc)
                            }
                            minimumHeight = 160.dpToPx()
                            onFlingListener = null
                            StartSnapHelper().attachToRecyclerView(this)
                        }

                        else -> { // STYLE_RANK
                            layoutManager = LinearLayoutManager(context)
                            this@SectionHolder.adapter =
                                RankBookAdapter(showRank = true) { book, lc ->
                                    onBook(section, book, lc)
                                }
                        }
                    }
                    this.adapter = this@SectionHolder.adapter
                }
                root.addView(
                    rv, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        fun updateBooks(books: List<SearchBook>) {
            (adapter as? CoverCardAdapter)?.setBooks(books)
            (adapter as? RankBookAdapter)?.setBooks(books)
            (adapter as? FourColumnAdapter)?.setBooks(books)
        }

        fun updateLoading(loading: Boolean) {
            if (loading && section.style != HomeSection.STYLE_INFINITE_GRID) {
                titleBinding.rlLoading.visible()
            } else {
                titleBinding.rlLoading.gone()
            }
        }
    }

    // ─── Sub-adapters（内层 RecyclerView）────────────────────────────────

    private inner class CoverCardAdapter(
        private val coverVideo: Boolean,
        private val onClick: (SearchBook, Boolean) -> Unit
    ) : RecyclerView.Adapter<CoverCardVH>() {

        private val books = mutableListOf<SearchBook>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CoverCardVH(ItemHomeCoverCardBinding.inflate(inflater, parent, false))

        override fun getItemCount() = books.size

        override fun onBindViewHolder(holder: CoverCardVH, position: Int) =
            holder.bind(books[position], coverVideo, onClick)

        fun setBooks(newBooks: List<SearchBook>) {
            books.clear()
            books.addAll(newBooks)
            notifyDataSetChanged()
        }
    }

    private inner class RankBookAdapter(
        private val showRank: Boolean,
        private val onClick: (SearchBook, Boolean) -> Unit
    ) : RecyclerView.Adapter<RankBookVH>() {

        private val books = mutableListOf<SearchBook>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RankBookVH(ItemHomeRankBookBinding.inflate(inflater, parent, false))

        override fun getItemCount() = books.size

        override fun onBindViewHolder(holder: RankBookVH, position: Int) =
            holder.bind(position + 1, books[position], showRank, onClick)

        fun setBooks(newBooks: List<SearchBook>) {
            books.clear()
            books.addAll(newBooks)
            notifyDataSetChanged()
        }
    }

    private inner class FourColumnAdapter(
        private val onClick: (SearchBook, Boolean) -> Unit
    ) : RecyclerView.Adapter<FourColumnVH>() {

        private val columns = mutableListOf<List<SearchBook>>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            FourColumnVH(ItemHomeFourPageBinding.inflate(inflater, parent, false), onClick)

        override fun getItemCount() = columns.size

        override fun onBindViewHolder(holder: FourColumnVH, position: Int) =
            holder.bind(columns[position])

        fun setBooks(books: List<SearchBook>) {
            columns.clear()
            columns.addAll(books.chunked(4))
            notifyDataSetChanged()
        }
    }

    // ─── 内层 ViewHolder ─────────────────────────────────────────────────

    private inner class CoverCardVH(val b: ItemHomeCoverCardBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(book: SearchBook, coverVideo: Boolean, onClick: (SearchBook, Boolean) -> Unit) {
            b.tvName.text = book.name
            b.tvAuthor.text = book.author
            b.ivCover.coverRatio =
                if (coverVideo) BookCover.CoverRatio.VIDEO else BookCover.CoverRatio.NOVEL
            // 必须设置固定高度以触发 CoverImageView 根据比例计算宽度，从而限制标题文字换行
            b.ivCover.layoutParams = b.ivCover.layoutParams.apply {
                height = if (coverVideo) 120.dpToPx() else 160.dpToPx()
                width = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            b.ivCover.load(book.coverUrl, book.name, book.author, false, book.origin)
            b.root.setOnClickListener { onClick(book, false) }
            b.root.setOnLongClickListener { onClick(book, true); true }
        }
    }

    private inner class RankBookVH(val b: ItemHomeRankBookBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(
            rank: Int,
            book: SearchBook,
            showRank: Boolean,
            onClick: (SearchBook, Boolean) -> Unit
        ) {
            if (showRank) {
                b.tvRank.visible()
                b.tvRank.text = rank.toString()
                b.tvRank.setTextColor(
                    when (rank) {
                        1 -> context.getColor(R.color.md_red_600)
                        2 -> context.getColor(R.color.md_orange_700)
                        3 -> context.getColor(R.color.md_yellow_700)
                        else -> context.getColor(R.color.tv_text_summary)
                    }
                )
            } else {
                b.tvRank.gone()
            }
            b.tvName.text = book.name
            b.tvAuthor.text = book.author
            // 排行榜/四行的封面是小缩略图，固定小说比例
            b.ivCover.coverRatio = BookCover.CoverRatio.NOVEL
            b.ivCover.load(book.coverUrl, book.name, book.author, false, book.origin)
            b.root.setOnClickListener { onClick(book, false) }
            b.root.setOnLongClickListener { onClick(book, true); true }
        }
    }

    private inner class FourColumnVH(
        val b: ItemHomeFourPageBinding,
        private val onClick: (SearchBook, Boolean) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {

        private val itemAdapter = RankBookAdapter(showRank = false, onClick = onClick)

        init {
            // 列宽固定 228dp（竖屏最小宽 411dp 下约露出 1.8 列），高度随内容自适应
            b.root.layoutParams = RecyclerView.LayoutParams(
                228.dpToPx(),
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            b.rvPage.layoutManager = LinearLayoutManager(context)
            b.rvPage.adapter = itemAdapter
            b.rvPage.isNestedScrollingEnabled = false
        }

        fun bind(books: List<SearchBook>) {
            itemAdapter.setBooks(books)
        }
    }

    interface Callback {
        fun onBookClick(book: SearchBook, section: HomeSection, longClick: Boolean)
        fun onMoreClick(section: HomeSection)
        fun isLoading(sectionId: String): Boolean
    }
}
