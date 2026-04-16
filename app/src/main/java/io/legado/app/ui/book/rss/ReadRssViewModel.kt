package io.legado.app.ui.book.rss

import android.app.Application
import androidx.lifecycle.MutableLiveData
import com.script.rhino.runScriptWithContext
import io.legado.app.base.BaseReadViewModel
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.IntentData
import io.legado.app.help.TTS
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook.getContentAwait
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class ReadRssViewModel(application: Application) : BaseReadViewModel(application) {
    override var curBook: Book? = null
    var tts: TTS? = null
    val contentLiveData = MutableLiveData<Pair<String, String>>()
    val urlLiveData = MutableLiveData<AnalyzeUrl>()
    val upTtsMenuData = MutableLiveData<Boolean>()
    val upStarMenuData = MutableLiveData<Boolean>()
    @Suppress("PropertyName")
    var UA: String? = null

    fun initData() {
        execute {
            upBook(IntentData.book ?: return@execute)
            val book = curBook ?: return@execute
            val source = curBookSource
            val intro = book.intro

            if (book.originName == "RSS" && !intro.isNullOrBlank()) {
                UA = runScriptWithContext { source?.getHeaderMap()?.get(AppConst.UA_NAME) }
                contentLiveData.postValue(Pair(book.tocUrl, clHtml(intro)))
                return@execute
            }

            if (source == null) return@execute

            val chapter = withContext(Dispatchers.Main) {
                chapterListData.value?.get(book.durChapterIndex)
            } ?: return@execute

            if (!source.ruleContent?.content.isNullOrBlank()) {
                loadContent(chapter)
            } else {
                val baseUrl = if (book.originName == "RSS") source.bookSourceUrl else book.tocUrl
                urlLiveData.postValue(
                    AnalyzeUrl(
                        mUrl = chapter.url,
                        baseUrl = baseUrl,
                        source = source,
                        coroutineContext = currentCoroutineContext(),
                        hasLoginHeader = false
                    )
                )
            }
        }.onFinally {
            upStarMenuData.postValue(true)
        }
    }

    private fun loadContent(chapter: BookChapter) {
        val book = curBook ?: return
        val source = curBookSource ?: return
        execute {
            getContentAwait(source, book, chapter)
        }.onSuccess(IO) { body ->
            val baseUrl = if (book.originName == "RSS") source.bookSourceUrl else book.tocUrl
            val url = NetworkUtils.getAbsoluteURL(baseUrl, chapter.url)
            UA = runScriptWithContext { source.getHeaderMap()[AppConst.UA_NAME] }
            contentLiveData.postValue(Pair(url, clHtml(body)))
        }.onError {
            contentLiveData.postValue(Pair("", "加载正文失败\n${it.stackTraceToString()}"))
        }
    }

    fun refresh(finish: () -> Unit) {
        val book = curBook ?: return finish()
        if (book.originName == "RSS" && !book.intro.isNullOrBlank()) {
            return finish()
        }
        val source = curBookSource ?: run {
            appCtx.toastOnUi("源不存在")
            return finish()
        }

        if (!source.ruleContent?.content.isNullOrBlank()) {
            chapterListData.value?.get(book.durChapterIndex)?.let { loadContent(it) }
        } else finish()
    }

    fun clHtml(content: String): String {
        val webJs = curBookSource?.ruleContent?.webJs
        return if (!webJs.isNullOrEmpty()) {
            """
                <script>
                    $webJs
                </script>
                $content
            """.trimIndent()
        } else {
            """
                <style>
                    img{max-width:100% !important; width:auto; height:auto;}
                    video{object-fit:fill; max-width:100% !important; width:auto; height:auto;}
                    body{word-wrap:break-word; height:auto;max-width: 100%; width:auto;}
                </style>
                $content
            """.trimIndent()
        }
    }

    @Synchronized
    fun readAloud(text: String) {
        val currentTts = tts ?: TTS().apply {
            setSpeakStateListener(object : TTS.SpeakStateListener {
                override fun onStart() {
                    upTtsMenuData.postValue(true)
                }

                override fun onDone() {
                    upTtsMenuData.postValue(false)
                }
            })
        }.also { tts = it }
        currentTts.speak(text)
    }

    override fun onCleared() {
        super.onCleared()
        tts?.clearTts()
    }
}