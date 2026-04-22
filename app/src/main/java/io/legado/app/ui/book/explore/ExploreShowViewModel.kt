package io.legado.app.ui.book.explore

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.BuildConfig
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst.timeLimit
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.IntentData
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.webBook.WebBook.getBookListAwait
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreShowViewModel(application: Application) : BaseViewModel(application) {
    val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val upAdapterLiveData = MutableLiveData<String>()
    val booksData = MutableLiveData<List<SearchBook>>()
    val errorLiveData = MutableLiveData<String>()
    val sourceReadyLiveData = MutableLiveData<Unit>()
    var bookSource: BookSource? = null
        private set
    val exploreStyle get() = bookSource?.exploreStyle ?: 0
    private var rawExploreUrl: String? = null
    val exploreOptions = mutableListOf<ExploreOption>()
    private var optionRegexes = mutableMapOf<String, Regex>()
    var page = 1
        private set
    private var books = linkedSetOf<SearchBook>()

    init {
        execute {
            appDb.bookDao.flowAll().mapLatest { books ->
                val keys = arrayListOf<String>()
                books.filterNot { it.isNotShelf }
                    .forEach {
                        keys.add("${it.name}-${it.author}")
                        keys.add(it.name)
                        keys.add(it.bookUrl)
                }
                keys
            }.catch {
                AppLog.put("发现列表界面获取书籍数据失败\n${it.localizedMessage}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                upAdapterLiveData.postValue("isInBookshelf")
            }
        }.onError {
            AppLog.put("加载书架数据失败", it)
        }
    }

    fun initData(intent: Intent) {
        execute {
            rawExploreUrl = intent.getStringExtra("exploreUrl")
            optionRegexes.clear()
            if (bookSource == null) {
                bookSource = (IntentData.source as? BookSource)
            }
            parseExploreOptions()
            sourceReadyLiveData.postValue(Unit)
            explore()
        }
    }

    private fun parseExploreOptions() {
        val url = rawExploreUrl ?: return
        exploreOptions.clear()
        val regex = "<(\\w+)\\((.*?)\\)>".toRegex()
        regex.findAll(url).forEach { match ->
            val name = match.groupValues[1]
            val pairs = match.groupValues[2].split(",").mapNotNull { s ->
                val split = s.split(":", limit = 2)
                val first = split.getOrNull(0)?.trim() ?: return@mapNotNull null
                if (first.isEmpty()) return@mapNotNull null
                val second = split.getOrNull(1)?.trim() ?: first
                first to second
            }
            if (pairs.isNotEmpty()) {
                exploreOptions.add(ExploreOption(name, pairs, pairs[0].second))
            }
        }
    }

    fun explore(resetPage: Boolean = false) {
        val source = bookSource ?: return
        var url = rawExploreUrl ?: return
        if (resetPage) {
            page = 1
            books.clear()
        }
        exploreOptions.forEach { option ->
            val regex = optionRegexes.getOrPut(option.name) {
                "<${option.name}\\(.*?\\)>".toRegex()
            }
            url = url.replace(regex, option.selectedValue)
        }
        Coroutine.async(viewModelScope) {
            getBookListAwait(source, url, page, isSearch = false)
        }.timeout(if (BuildConfig.DEBUG) 0L else timeLimit)
            .onSuccess(IO) { searchBooks ->
                books.addAll(searchBooks)
                booksData.postValue(books.toList())
                page++
            }.onError {
                it.printOnDebug()
                errorLiveData.postValue(it.stackTraceStr)
            }
    }

    fun isInBookShelf(book: BaseBook): Boolean {
        val key = if (book.author.isNotBlank()) "${book.name}-${book.author}" else book.name
        return bookshelf.contains(key) || bookshelf.contains(book.bookUrl)
    }

    fun switchLayout() {
        bookSource?.let {
            it.exploreStyle = (it.exploreStyle + 1) % 3
            execute {
                appDb.bookSourceDao.update(it)
            }
        }
    }

    data class ExploreOption(
        val name: String,
        val options: List<Pair<String, String>>,
        var selectedValue: String
    )

}
