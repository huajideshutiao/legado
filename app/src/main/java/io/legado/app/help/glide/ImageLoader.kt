package io.legado.app.help.glide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.script.rhino.runScriptWithContext
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadManga
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.isFilePath
import io.legado.app.utils.isUri
import io.legado.app.utils.lifecycle
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.CoroutineContext

//https://bumptech.github.io/glide/doc/generatedapi.html
//Instead of GlideApp, use com.bumptech.Glide
object ImageLoader {

    /**
     * 自动判断path类型
     */
    fun load(
        requestManager: RequestManager,
        path: String?,
        inBookshelf: Boolean = false
    ): RequestBuilder<Drawable> {
        return requestManager.load(if (path.isFilePath()) File(path) else path)
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .let { if (inBookshelf) it.signature(ObjectKey("covers")) else it }
    }

    fun load(
        context: Context,
        path: String?,
        inBookshelf: Boolean = false
    ): RequestBuilder<Drawable> {
        return load(Glide.with(context), path, inBookshelf)
    }

    fun load(
        fragment: Fragment,
        lifecycle: Lifecycle,
        path: String?,
        inBookshelf: Boolean = false
    ): RequestBuilder<Drawable> {
        return load(Glide.with(fragment).lifecycle(lifecycle), path, inBookshelf)
    }

    fun loadBitmap(context: Context, path: String?): RequestBuilder<Bitmap> {
        val requestManager = Glide.with(context).`as`(Bitmap::class.java)
        return when {
            path.isFilePath() -> requestManager.load(File(path))
            else -> requestManager.load(path)
        }.diskCacheStrategy(DiskCacheStrategy.DATA)
    }

    suspend fun loadManga(imageUrl: String, coroutineContext: CoroutineContext): ByteArray? {
        val book = ReadManga.book ?: return null
        if (BookHelp.isImageExist(book, imageUrl)) {
            return BookHelp.getImage(book, imageUrl).readBytes()
        }
        if (book.isLocal) {
            if (book.bookUrl.isUri() || book.bookUrl.isFilePath()) {
                return LocalBook.getImage(book, imageUrl)?.use { it.readBytes() }
            }
            val file = ImageProvider.cacheImage(book, imageUrl, ReadManga.bookSource)
            return if (file.exists()) file.readBytes() else null
        }
        val analyzeUrl = AnalyzeUrl(
            imageUrl, source = ReadManga.bookSource, coroutineContext = coroutineContext
        )
        val bytes = analyzeUrl.getByteArrayAwait()
        val decodedBytes = runScriptWithContext {
            ImageUtils.decode(
                imageUrl, bytes, isCover = false, ReadManga.bookSource, book
            )
        } ?: return null
        return decodedBytes.also {
            kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
                BookHelp.writeImage(book, imageUrl, it)
            }
        }
    }
}
