package io.legado.app.ui.book.toc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ItemChapterListBinding
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.ThemeUtils
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ChapterListAdapter(context: Context, val callback: Callback) :
    DiffRecyclerAdapter<BookChapter, ItemChapterListBinding>(context) {

    val cacheFileNames = hashSetOf<String>()
    private val displayTitleMap = ConcurrentHashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private var allChapters: List<BookChapter> = emptyList()
    private val collapsedVolumeIndices = hashSetOf<Int>()
    private var pendingChapterListReset = false

    override val diffItemCallback: DiffUtil.ItemCallback<BookChapter>
        get() = object : DiffUtil.ItemCallback<BookChapter>() {

            override fun areItemsTheSame(
                oldItem: BookChapter,
                newItem: BookChapter
            ): Boolean {
                return oldItem.index == newItem.index
            }

            override fun areContentsTheSame(
                oldItem: BookChapter,
                newItem: BookChapter
            ): Boolean {
                return oldItem.bookUrl == newItem.bookUrl
                        && oldItem.url == newItem.url
                        && oldItem.isVip == newItem.isVip
                        && oldItem.isPay == newItem.isPay
                        && oldItem.title == newItem.title
                        && oldItem.tag == newItem.tag
                        && oldItem.wordCount == newItem.wordCount
                        && oldItem.isVolume == newItem.isVolume
            }

        }

    private var upDisplayTileJob: Coroutine<*>? = null

    override fun onCurrentListChanged() {
        super.onCurrentListChanged()
        if (pendingChapterListReset) {
            pendingChapterListReset = false
            callback.onListChanged()
        }
    }

    fun setChapterList(items: List<BookChapter>?) {
        allChapters = items.orEmpty()
        collapsedVolumeIndices.clear()
        pendingChapterListReset = true
        setItems(buildDisplayList())
    }

    private fun buildDisplayList(): List<BookChapter> {
        if (collapsedVolumeIndices.isEmpty()) return allChapters
        val out = ArrayList<BookChapter>(allChapters.size)
        var hide = false
        for (item in allChapters) {
            if (item.isVolume) {
                hide = item.index in collapsedVolumeIndices
                out.add(item)
            } else if (!hide) {
                out.add(item)
            }
        }
        return out
    }

    private fun toggleVolume(volume: BookChapter) {
        if (!collapsedVolumeIndices.add(volume.index)) {
            collapsedVolumeIndices.remove(volume.index)
        }
        setItems(buildDisplayList())
    }

    fun clearDisplayTitle() {
        upDisplayTileJob?.cancel()
        displayTitleMap.clear()
    }

    fun upDisplayTitles(startIndex: Int) {
        upDisplayTileJob?.cancel()
        upDisplayTileJob = Coroutine.async(callback.scope) {
            val book = callback.book ?: return@async
            val replaceRules = ContentProcessor.get(book.name, book.origin).getTitleReplaceRules()
            val useReplace = AppConfig.tocUiUseReplace && book.getUseReplaceRule()
            val items = getItems()
            launch {
                for (i in startIndex until items.size) {
                    val item = items[i]
                    if (displayTitleMap[item.title] == null) {
                        ensureActive()
                        val displayTitle = item.getDisplayTitle(replaceRules, useReplace)
                        ensureActive()
                        displayTitleMap[item.title] = displayTitle
                        handler.post {
                            notifyItemChanged(i, true)
                        }
                    }
                }
            }
            launch {
                for (i in startIndex downTo 0) {
                    val item = items[i]
                    if (displayTitleMap[item.title] == null) {
                        ensureActive()
                        val displayTitle = item.getDisplayTitle(replaceRules, useReplace)
                        ensureActive()
                        displayTitleMap[item.title] = displayTitle
                        handler.post {
                            notifyItemChanged(i, true)
                        }
                    }
                }
            }
        }
    }

    private fun getDisplayTitle(chapter: BookChapter): String {
        return displayTitleMap[chapter.title] ?: chapter.title
    }

    override fun getViewBinding(parent: ViewGroup): ItemChapterListBinding {
        return ItemChapterListBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemChapterListBinding,
        item: BookChapter,
        payloads: MutableList<Any>
    ) {
        binding.run {
            val isDur = callback.durChapterIndex() == item.index
            val cached = callback.isLocalBook
                    || item.isVolume
                    || cacheFileNames.contains(item.getFileName())
            if (payloads.isEmpty()) {
                if (isDur) {
                    tvChapterName.setTextColor(context.accentColor)
                } else {
                    tvChapterName.setTextColor(context.getCompatColor(R.color.primaryText))
                }
                tvChapterName.text = getDisplayTitle(item)
                if (item.isVolume) {
                    //卷名，如第一卷 突出显示
                    tvChapterItem.setBackgroundColor(context.getCompatColor(R.color.btn_bg))
                } else {
                    //普通章节 保持不变
                    tvChapterItem.background =
                        ThemeUtils.resolveDrawable(context, android.R.attr.selectableItemBackground)
                }

                //卷名不显示
                if (!item.tag.isNullOrEmpty() && !item.isVolume) {
                    //更新时间规则
                    tvTag.text = item.tag
                    tvTag.visible()
                } else {
                    tvTag.gone()
                }
                if (AppConfig.tocCountWords && !item.wordCount.isNullOrEmpty() && !item.isVolume) {
                    //章节字数
                    tvWordCount.text = item.wordCount
                    tvWordCount.visible()
                } else {
                    tvWordCount.gone()
                }

                if (item.isVip && !item.isPay) {
                    ivLocked.visible()
                } else {
                    ivLocked.gone()
                }

                upRightIcon(binding, item, isDur, cached)
            } else {
                tvChapterName.text = getDisplayTitle(item)
                upRightIcon(binding, item, isDur, cached)
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemChapterListBinding) {
        holder.itemView.setOnClickListener {
            val item = getItem(holder.layoutPosition) ?: return@setOnClickListener
            if (item.isVolume) {
                toggleVolume(item)
            } else {
                callback.openChapter(item)
            }
        }
        holder.itemView.setOnLongClickListener {
            getItem(holder.layoutPosition)?.let { item ->
                context.longToastOnUi(getDisplayTitle(item))
            }
            true
        }
    }

    private fun upRightIcon(
        binding: ItemChapterListBinding,
        item: BookChapter,
        isDur: Boolean,
        cached: Boolean
    ) = binding.apply {
        if (item.isVolume) {
            val collapsed = item.index in collapsedVolumeIndices
            ivChecked.setImageResource(
                if (collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less
            )
            ivChecked.visible()
            return@apply
        }
        ivChecked.setImageResource(R.drawable.ic_outline_cloud_24)
        ivChecked.visible(!cached)
        if (isDur) {
            ivChecked.setImageResource(R.drawable.ic_check)
            ivChecked.visible()
        }
    }

    interface Callback {
        val scope: CoroutineScope
        val book: Book?
        val isLocalBook: Boolean
        fun openChapter(bookChapter: BookChapter)
        fun durChapterIndex(): Int
        fun onListChanged()
    }

}