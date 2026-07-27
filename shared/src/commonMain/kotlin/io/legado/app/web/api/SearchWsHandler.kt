package io.legado.app.web.api

import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.model.webBook.SearchModel
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJson
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.utils.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 全网搜索的平台无关处理器 (零 android import)。
 *
 * 出站经注入的 [WsSession]; 平台壳负责帧解码与 30s ping。
 * @param cannotEmptyMsg 由平台壳注入的「不能为空」本地化文案 (原 R.string.cannot_empty)
 *
 * 下沉 commonMain: 依赖 (SearchModel/SearchBook/GSON) 均已下沉; 原 app 端 AppConfig.searchScope
 * (app 专属) 替换为 AppConfigProviders.get().searchScope (shared 跨平台访问器, 值同源)。
 */
class SearchWsHandler(
    private val session: WsSession,
    private val cannotEmptyMsg: String,
) : WsHandler, SearchModel.CallBack, CoroutineScope by MainScope() {

    private val searchModel = SearchModel(this, this)

    private val searchFinish = "Search finish"

    override fun onMessage(text: String) {
        launch(IoDispatcher) {
            kotlin.runCatching {
                if (!text.isJson()) {
                    session.send("数据必须为Json格式")
                    session.close(searchFinish)
                    return@launch
                }
                val searchMap =
                    GSON.fromJsonObject<Map<String, String>>(text).getOrNull()
                if (searchMap != null) {
                    val key = searchMap["key"]
                    if (key.isNullOrBlank()) {
                        session.send(cannotEmptyMsg)
                        session.close(searchFinish)
                        return@launch
                    }
                    searchModel.search(systemCurrentTimeMillis(), key)
                }
            }
        }
    }

    override fun onClose() {
        cancel()
        searchModel.close()
    }

    override fun onException() {

    }

    override fun getSearchScope(): SearchScope = SearchScope(AppConfigProviders.get().searchScope)

    override fun onSearchStart() {

    }

    override fun onSearchSuccess(searchBooks: List<SearchBook>) {
        session.send(GSON.toJson(searchBooks))
    }

    override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) = session.close(searchFinish)

    override fun onSearchCancel(exception: Throwable?) =
        session.close(exception?.toString() ?: searchFinish)

    override fun onSearchOptionsResolved(options: List<ExploreOption>) {
    }

    override fun getSearchOptions(): List<ExploreOption> {
        return emptyList()
    }
}
