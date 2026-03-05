package io.legado.app.ui.book.explore

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemRssArticle1Binding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.utils.gone
import io.legado.app.utils.visible


class BigExploreShowAdapter(context: Context, callBack: CallBack) :
    BaseExploreShowAdapter<ItemRssArticle1Binding>(context, callBack) {

    override fun getViewBinding(parent: ViewGroup): ItemRssArticle1Binding {
        return ItemRssArticle1Binding.inflate(inflater, parent, false)
    }

    @SuppressLint("CheckResult")
    override fun bind(
        binding: ItemRssArticle1Binding,
        item: SearchBook
    ) {
        binding.run {
            tvTitle.text = item.name
            tvPubDate.text = item.kind
            ivInBookshelf.isVisible = callBack.isInBookshelf(item)
            if (item.coverUrl.isNullOrBlank()) {
                imageView.gone()
            } else {
                val options =
                    RequestOptions().set(OkHttpModelLoader.sourceOriginOption, item.origin)
                ImageLoader.load(context, item.coverUrl,ivInBookshelf.isVisible)
                    .apply(options).apply {
                        addListener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(
                                e: GlideException?,
                                model: Any?,
                                target: Target<Drawable>,
                                isFirstResource: Boolean
                            ): Boolean {
                                imageView.gone()
                                return false
                            }

                            override fun onResourceReady(
                                resource: Drawable,
                                model: Any,
                                target: Target<Drawable>?,
                                dataSource: DataSource,
                                isFirstResource: Boolean
                            ): Boolean {
                                imageView.visible()
                                return false
                            }

                        })

                }.into(imageView)
            }
        }
    }

        override fun bindChange(binding: ItemRssArticle1Binding, item: SearchBook, bundle: Bundle) {
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