package io.legado.app.ui.main.home

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemHomeCoverCardBinding
import io.legado.app.databinding.ItemHomeRankBookBinding
import io.legado.app.databinding.ViewHomeSectionTitleBinding
import io.legado.app.model.BookCover
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.utils.dpToPx
import io.legado.app.utils.visible
import kotlin.math.abs

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

    companion object {
        /** 排行榜样式仅展示前 N 名 */
        private const val RANK_LIMIT = 5
    }

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

    fun updateSectionError(sectionId: String) {
        holders[sectionId]?.showError(context.getString(R.string.home_source_invalid))
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
        private var rv: RecyclerView? = null
        private var stateView: LoadMoreView? = null

        init {
            if (section.style != HomeSection.STYLE_INFINITE_GRID) {
                rv = RecyclerView(context).apply {
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

                    if (layoutManager is LinearLayoutManager && (layoutManager as LinearLayoutManager).orientation == RecyclerView.HORIZONTAL) {
                        addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                            private var startX = 0f
                            private var startY = 0f

                            override fun onInterceptTouchEvent(
                                rv: RecyclerView,
                                e: MotionEvent
                            ): Boolean {
                                when (e.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        startX = e.x
                                        startY = e.y
                                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                                    }

                                    MotionEvent.ACTION_MOVE -> {
                                        val dx = abs(e.x - startX)
                                        val dy = abs(e.y - startY)
                                        if (dx > dy) {
                                            rv.parent?.requestDisallowInterceptTouchEvent(true)
                                        } else if (dy > dx) {
                                            rv.parent?.requestDisallowInterceptTouchEvent(false)
                                        }
                                    }

                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        rv.parent?.requestDisallowInterceptTouchEvent(false)
                                    }
                                }
                                return false
                            }

                            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
                            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
                        })
                    }
                }
                root.addView(
                    rv, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                stateView = LoadMoreView(context).apply {
                    visibility = View.GONE
                }
                root.addView(
                    stateView, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        private fun itemCountOf(): Int = when (val a = adapter) {
            is CoverCardAdapter -> a.itemCount
            is RankBookAdapter -> a.itemCount
            is FourColumnAdapter -> a.itemCount
            else -> 0
        }

        fun updateBooks(books: List<SearchBook>) {
            (adapter as? CoverCardAdapter)?.setBooks(books)
            // 排行榜只展示前 5 名（数据缓存仍是完整一页，切样式不丢）
            (adapter as? RankBookAdapter)?.setBooks(books.take(RANK_LIMIT))
            (adapter as? FourColumnAdapter)?.setBooks(books)
            // 数据到位后让 loading/empty 状态根据真实 item 数刷新
            updateLoading(callback.isLoading(section.id))
        }

        fun updateLoading(loading: Boolean) {
            if (section.style == HomeSection.STYLE_INFINITE_GRID) return
            val sv = stateView ?: return
            if (itemCountOf() > 0) {
                rv?.visibility = View.VISIBLE
                sv.visibility = View.GONE
                return
            }
            rv?.visibility = View.GONE
            sv.visibility = View.VISIBLE
            if (loading) sv.startLoad() else sv.noMore(context.getString(R.string.empty))
        }

        fun showError(message: String) {
            if (section.style == HomeSection.STYLE_INFINITE_GRID) return
            val sv = stateView ?: return
            rv?.visibility = View.GONE
            sv.visibility = View.VISIBLE
            sv.error(message, message)
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FourColumnVH {
            val rv = RecyclerView(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    220.dpToPx(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                isNestedScrollingEnabled = false
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            }
            return FourColumnVH(rv, onClick)
        }

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
            b.tvAuthor.text = book.getRealAuthor()
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
            }
            b.tvName.text = book.name
            b.tvAuthor.text = book.getRealAuthor()
            // 排行榜/四行的封面是小缩略图，固定小说比例
            b.ivCover.coverRatio = BookCover.CoverRatio.NOVEL
            b.ivCover.load(book.coverUrl, book.name, book.author, false, book.origin)
            b.root.setOnClickListener { onClick(book, false) }
            b.root.setOnLongClickListener { onClick(book, true); true }
        }
    }

    private inner class FourColumnVH(
        val rv: RecyclerView,
        private val onClick: (SearchBook, Boolean) -> Unit
    ) : RecyclerView.ViewHolder(rv) {

        private val itemAdapter = RankBookAdapter(showRank = false, onClick = onClick)

        init {
            rv.layoutManager = LinearLayoutManager(context)
            rv.adapter = itemAdapter
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
