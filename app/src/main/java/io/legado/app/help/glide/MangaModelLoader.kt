package io.legado.app.help.glide

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.ByteBuffer

class MangaModelLoader : ModelLoader<MangaModel, ByteBuffer> {
    override fun buildLoadData(
        model: MangaModel,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<ByteBuffer> {
        return ModelLoader.LoadData(ObjectKey(model.url), MangaDataFetcher(model))
    }

    override fun handles(model: MangaModel): Boolean {
        return true
    }

    class Factory : ModelLoaderFactory<MangaModel, ByteBuffer> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<MangaModel, ByteBuffer> {
            return MangaModelLoader()
        }

        override fun teardown() {}
    }
}

class MangaDataFetcher(private val model: MangaModel) : DataFetcher<ByteBuffer> {
    private val coroutineContext = SupervisorJob()
    private val coroutineScope = CoroutineScope(coroutineContext)

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in ByteBuffer>) {
        Coroutine.async(coroutineScope, Dispatchers.IO) {
            try {
                val result = ImageLoader.loadManga(
                    model.url,
                    model.book,
                    model.bookSource,
                    coroutineContext
                )
                if (result != null) {
                    callback.onDataReady(ByteBuffer.wrap(result))
                } else {
                    callback.onLoadFailed(Exception("Load manga returned null"))
                }
            } catch (e: Exception) {
                callback.onLoadFailed(e)
            }
        }
    }

    override fun cleanup() {
    }

    override fun cancel() {
        coroutineContext.cancel()
    }

    override fun getDataClass(): Class<ByteBuffer> {
        return ByteBuffer::class.java
    }

    override fun getDataSource(): DataSource {
        return DataSource.LOCAL
    }
}

data class MangaModel(
    val url: String,
    val book: Book,
    val bookSource: BookSource?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MangaModel) return false
        return url == other.url
    }

    override fun hashCode(): Int {
        return url.hashCode()
    }
}
