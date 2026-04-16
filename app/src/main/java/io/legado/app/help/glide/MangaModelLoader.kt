package io.legado.app.help.glide

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class MangaModelLoader : ModelLoader<MangaModel, InputStream> {
    override fun buildLoadData(
        model: MangaModel,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream> {
        return ModelLoader.LoadData(ObjectKey(model.url), MangaDataFetcher(model))
    }

    override fun handles(model: MangaModel): Boolean {
        return true
    }

    class Factory : ModelLoaderFactory<MangaModel, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<MangaModel, InputStream> {
            return MangaModelLoader()
        }

        override fun teardown() {}
    }
}

class MangaDataFetcher(private val model: MangaModel) : DataFetcher<InputStream> {
    private val coroutineContext = SupervisorJob()
    private val coroutineScope = CoroutineScope(coroutineContext)
    private var stream: InputStream? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        Coroutine.async(coroutineScope, Dispatchers.IO) {
            try {
                when (val result = ImageLoader.loadManga(model.url, coroutineContext)) {
                    is ByteArray -> {
                        stream = ByteArrayInputStream(result)
                        callback.onDataReady(stream)
                    }

                    is File -> {
                        stream = FileInputStream(result)
                        callback.onDataReady(stream)
                    }

                    is InputStream -> {
                        stream = result
                        callback.onDataReady(stream)
                    }

                    null -> {
                        callback.onLoadFailed(Exception("Load manga returned null"))
                    }

                    else -> {
                        callback.onLoadFailed(Exception("Unknown result type: ${result::class.java}"))
                    }
                }
            } catch (e: Exception) {
                callback.onLoadFailed(e)
            }
        }
    }

    override fun cleanup() {
        runCatching {
            stream?.close()
        }
    }

    override fun cancel() {
        coroutineContext.cancel()
    }

    override fun getDataClass(): Class<InputStream> {
        return InputStream::class.java
    }

    override fun getDataSource(): DataSource {
        return DataSource.REMOTE
    }
}

data class MangaModel(val url: String)