package io.legado.app.help.glide

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.utils.isFilePath
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class FilePathLoader : ModelLoader<String, InputStream> {
    override fun buildLoadData(
        model: String,
        width: Int,
        height: Int,
        options: com.bumptech.glide.load.Options
    ): ModelLoader.LoadData<InputStream> {
        return ModelLoader.LoadData(ObjectKey(model), FilePathFetcher(model))
    }

    override fun handles(model: String): Boolean {
        return model.isFilePath()
    }

    class FilePathFetcher(private val filePath: String) : DataFetcher<InputStream> {
        private var stream: InputStream? = null

        override fun loadData(
            priority: Priority,
            callback: DataFetcher.DataCallback<in InputStream>
        ) {
            val file = File(filePath)
            if (file.exists() && file.isFile) {
                val fis = FileInputStream(file)
                stream = fis
                callback.onDataReady(fis)
            } else {
                callback.onLoadFailed(Exception("File not found: $filePath"))
            }
        }

        override fun cleanup() {
            kotlin.runCatching { stream?.close() }
            stream = null
        }

        override fun cancel() {}

        override fun getDataClass(): Class<InputStream> = InputStream::class.java

        override fun getDataSource(): DataSource = DataSource.LOCAL
    }

    class Factory : ModelLoaderFactory<String, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<String, InputStream> {
            return FilePathLoader()
        }

        override fun teardown() {}
    }
}
