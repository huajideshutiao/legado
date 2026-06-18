package io.legado.app.ui.book.search

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.model.webBook.SearchModel
import io.legado.app.utils.ConflateLiveData
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(application: Application) : BaseViewModel(application) {
    val handler = Handler(Looper.getMainLooper())
    val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val upAdapterLiveData = MutableLiveData<String>()
    var searchBookLiveData = ConflateLiveData<List<SearchBook>>(1000)
    val searchScope: SearchScope = SearchScope(AppConfig.searchScope)
    var searchFinishLiveData = MutableLiveData<Boolean>()
    var isSearchLiveData = MutableLiveData<Boolean>()
    val searchOptionsLiveData = MutableLiveData<Unit>()
    var searchKey: String = ""
    var hasMore = true
    val searchOptions = mutableListOf<ExploreOption>()
    private var searchID = 0L
    private var filteredCount = 0
    private val searchModel = SearchModel(viewModelScope, object : SearchModel.CallBack {

        override fun getSearchScope(): SearchScope {
            return searchScope
        }

        override fun onSearchStart() {
            filteredCount = 0
            isSearchLiveData.postValue(true)
        }

        override fun onSearchSuccess(searchBooks: List<SearchBook>) {
            searchBookLiveData.postValue(searchBooks)
        }

        override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) {
            this@SearchViewModel.hasMore = hasMore
            isSearchLiveData.postValue(false)
            searchFinishLiveData.postValue(isEmpty)
            if (filteredCount > 0) {
                context.toastOnUi(
                    context.getString(
                        R.string.source_filter_rule_filtered_count,
                        filteredCount
                    )
                )
            }
        }

        override fun onFiltered(count: Int) {
            filteredCount += count
        }

        override fun onSearchCancel(exception: Throwable?) {
            isSearchLiveData.postValue(false)
            exception?.let {
                context.toastOnUi(it.localizedMessage)
            }
        }

        override fun onSearchOptionsResolved(options: List<ExploreOption>) {
            val structureUnchanged = searchOptions.size == options.size &&
                searchOptions.zip(options).all { (a, b) ->
                    a.name == b.name && a.options == b.options
                }
            if (structureUnchanged) {
                // 保留用户已选中的状态，仅在结构变化时通知 UI
                return
            }
            val merged = options.map { newOpt ->
                searchOptions.find { it.name == newOpt.name }
                    ?.let { existing -> newOpt.copy(selectedValue = existing.selectedValue) }
                    ?: newOpt
            }
            searchOptions.clear()
            searchOptions.addAll(merged)
            searchOptionsLiveData.postValue(Unit)
        }

        override fun getSearchOptions(): List<ExploreOption> {
            return searchOptions
        }

    })

    init {
        execute {
            appDb.bookDao.flowShelfBookKeys().mapLatest { shelfBooks ->
                val keys = arrayListOf<String>()
                shelfBooks.forEach {
                    keys.add("${it.name}-${it.author}")
                    keys.add(it.name)
                    keys.add(it.bookUrl)
                }
                keys
            }.catch {
                AppLog.put("搜索界面获取书籍列表失败\n${it.localizedMessage}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                upAdapterLiveData.postValue("isInBookshelf")
            }
        }.onError {
            AppLog.put("加载书架数据失败", it)
        }
    }

    fun isInBookShelf(book: SearchBook): Boolean {
        val name = book.name
        val author = book.author
        val bookUrl = book.bookUrl
        val key = if (author.isNotBlank()) "$name-$author" else name
        return bookshelf.contains(key) || bookshelf.contains(bookUrl)
    }

    /**
     * 开始搜索
     */
    fun search(key: String, resetOptions: Boolean = true) {
        execute {
            if ((searchKey == key) || key.isNotEmpty()) {
                searchModel.cancelSearch()
                searchID = System.currentTimeMillis()
                searchBookLiveData.postValue(emptyList())
                searchKey = key
                hasMore = true
                if (resetOptions && searchOptions.isNotEmpty()) {
                    searchOptions.clear()
                    searchOptionsLiveData.postValue(Unit)
                }
            }
            if (searchKey.isEmpty()) {
                return@execute
            }
            searchModel.search(searchID, searchKey)
        }
    }

    /**
     * 停止搜索
     */
    fun stop() {
        searchModel.cancelSearch()
    }

    fun pause() {
        searchModel.pause()
    }

    fun resume() {
        searchModel.resume()
    }

    /**
     * 保存搜索关键字
     */
    fun saveSearchKey(key: String) {
        execute {
            appDb.searchKeywordDao.get(key)?.let {
                it.usage += 1
                it.lastUseTime = System.currentTimeMillis()
                appDb.searchKeywordDao.update(it)
            } ?: appDb.searchKeywordDao.insert(SearchKeyword(key, 1))
        }
    }

    /**
     * 清除搜索关键字
     */
    fun clearHistory() {
        execute {
            appDb.searchKeywordDao.deleteAll()
        }
    }

    fun deleteHistory(searchKeyword: SearchKeyword) {
        execute {
            appDb.searchKeywordDao.delete(searchKeyword)
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchModel.close()
    }

}
