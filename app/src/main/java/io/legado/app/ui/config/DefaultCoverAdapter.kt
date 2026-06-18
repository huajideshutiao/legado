package io.legado.app.ui.config

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.model.BookCover
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.dpToPx
import java.io.File

/**
 * 默认封面图集管理 -- 列表末尾的 "+" 占位项用于添加。
 * itemView 直接用代码拼,不为单 item 起一个 xml;CoverImageView 自带 3:4 自适应。
 */
class DefaultCoverAdapter(
    private val context: Context,
    private val callBack: CallBack,
) : RecyclerView.Adapter<DefaultCoverAdapter.VH>() {

    private val entries = mutableListOf<BookCover.DefaultCoverEntry>()
    private val padding = 6.dpToPx()

    fun submit(list: List<BookCover.DefaultCoverEntry>) {
        entries.clear()
        entries.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = entries.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val cover = CoverImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            coverRatio = BookCover.CoverRatio.NOVEL
        }
        val add = AppCompatImageView(context).apply {
            val size = 48.dpToPx()
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            setImageResource(R.drawable.ic_add)
        }
        val frame = FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            )
            setPadding(padding, padding, padding, padding)
            addView(cover)
            addView(add)
        }
        return VH(frame, cover, add)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries.getOrNull(position)
        if (entry == null) {
            holder.add.visibility = View.VISIBLE
            holder.cover.setImageDrawable(null)
            holder.itemView.setOnClickListener { callBack.onAddClick() }
        } else {
            holder.add.visibility = View.GONE
            holder.cover.setImageDrawable(loadThumb(entry))
            holder.itemView.setOnClickListener { callBack.onCoverClick(entry) }
        }
    }

    /**
     * .9.png 走 createFromPath 保留 chunk;普通图 decodeFile,失败回落到默认封面。
     */
    private fun loadThumb(entry: BookCover.DefaultCoverEntry): Drawable? {
        val path = entry.bakedPath(BookCover.CoverRatio.NOVEL)
        if (!File(path).exists()) {
            return BookCover.newDefaultDrawable(BookCover.CoverRatio.NOVEL, entry.id)
        }
        return if (entry.ninePatch) {
            Drawable.createFromPath(path)
        } else {
            BitmapFactory.decodeFile(path)?.toDrawable(context.resources)
        }
    }

    class VH(
        itemView: View,
        val cover: CoverImageView,
        val add: AppCompatImageView,
    ) : RecyclerView.ViewHolder(itemView)

    interface CallBack {
        fun onCoverClick(entry: BookCover.DefaultCoverEntry)
        fun onAddClick()
    }

}
