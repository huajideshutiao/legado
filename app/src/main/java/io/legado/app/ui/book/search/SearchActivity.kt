package io.legado.app.ui.book.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BaseBook
import io.legado.app.help.IntentData
import io.legado.app.help.book.addType
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.filter.SourceFilterRuleListDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.rss.ReadRssActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.video.VideoPlayActivity
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * 搜索界面宿主 (纯 Compose, UI 下沉 shared SearchScreen)。
 *
 * 重构说明 (统一 app/shared 单一来源, 消除同包同名遮蔽):
 * - 原 app 端 SearchScreen.kt + SearchViewModel.kt 已删除, 复用 shared 端 KMP 版:
 *   - UI: shared `SearchScreen(viewModel, navCallbacks)` (sharedUiMain)
 *   - VM: shared `SearchViewModel(scope)` (commonMain, StateFlow 驱动)
 * - 本 Activity 仅承担 Android 专属职责:
 *   - 持有 sharedVm (注入 lifecycleScope, destroy 自动取消协程)
 *   - 实现 SearchNavCallbacks 路由跳转 (startActivity<BookInfoActivity> 等)
 *   - 实现 SearchScopeDialog.Callback 范围确认
 *   - 处理 Intent 契约 (key/searchScope/submit)
 *   - 弹 Android 专属对话框 (SearchScopeDialog/AppLogDialog/SourceFilterRuleListDialog/清空历史确认)
 *
 * intent 契约 (key/searchScope/submit) 不变。
 *
 * 新增 string key 需求: 无 (shared 端 rememberString 复用已有 R.string.* actual 映射;
 * 清空历史确认复用 R.string.draw / R.string.sure_clear_search_history)
 */
class SearchActivity : BaseComposeActivity(), SearchScopeDialog.Callback, SearchNavCallbacks {

    /** shared 端 SearchViewModel (StateFlow 驱动), 注入 lifecycleScope 绑定生命周期。 */
    private lateinit var sharedVm: SearchViewModel

    @Composable
    override fun Content() {
        SearchScreen(viewModel = sharedVm, navCallbacks = this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        sharedVm = SearchViewModel(lifecycleScope)
        receiptIntent(intent)
        // 对齐原 initData 的 resume/pause 生命周期绑定 (repeatOnLifecycle RESUMED)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                sharedVm.resume()
                try {
                    awaitCancellation()
                } finally {
                    sharedVm.pause()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiptIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::sharedVm.isInitialized) sharedVm.close()
    }

    /**
     * 处理传入数据 (intent 契约: searchScope / key / submit)。
     *
     * 对齐原 receiptIntent:
     * - searchScope 分支: updateSearchScope (不 checkSearch, key 可能尚未设置)
     * - key 空分支: requestFocus (focusEpoch++)
     * - key 非空分支: setQuery(key, submit) (setQuery 内部 onQuerySubmit 已含 showInputHelp(false))
     */
    private fun receiptIntent(intent: Intent? = null) {
        val searchScope = intent?.getStringExtra("searchScope")
        searchScope?.let { sharedVm.updateSearchScope(it) }
        val key = intent?.getStringExtra("key")
        if (key.isNullOrBlank()) {
            sharedVm.requestFocus()
        } else {
            val submit = intent.getBooleanExtra("submit", true)
            sharedVm.setQuery(key, submit)
        }
    }

    // ---- SearchNavCallbacks 实现 (Android 专属路由) ----

    override fun onBack() = finish()

    /** 点击书籍: 补 notShelf type (对齐原 registerListener) 后进详情。 */
    override fun onBookClick(book: BaseBook, longClick: Boolean) {
        if (!sharedVm.isInBookShelf(book)) {
            book.addType(BookType.notShelf)
        }
        showBookInfo(book, longClick)
    }

    override fun onManageBookSources() = startActivity<BookSourceActivity>()

    override fun onAlertSearchScope() = showDialogFragment<SearchScopeDialog>()

    override fun onShowSourceFilterRule() =
        showDialogFragment(SourceFilterRuleListDialog(sharedVm.searchScope.toString()))

    override fun onShowAppLog() = showDialogFragment(AppLogDialog())

    /** 清空搜索历史: 弹确认框 (保留原 alertClearHistory 交互)。 */
    override fun onClearHistory() {
        alert(R.string.draw) {
            setMessage(R.string.sure_clear_search_history)
            yesButton { sharedVm.clearHistory() }
            noButton()
        }
    }

    // ---- SearchScopeDialog.Callback ----

    override fun onSearchScopeOk(searchScope: SearchScope) {
        sharedVm.onSearchScopeOk(searchScope)
    }

    // ---- 跳转 (Android 专属) ----

    /** 显示书籍详情 (对齐原 showBookInfo 跳转分派)。 */
    private fun showBookInfo(book: BaseBook, longClick: Boolean) {
        val urlParts = book.bookUrl.split("::", limit = 2)
        if (urlParts.size == 2) {
            IntentData.source = null
            startActivity<ExploreShowActivity> {
                putExtra("exploreName", urlParts[0])
                putExtra("exploreUrl", urlParts[1])
                putExtra("sourceUrl", book.origin)
            }
            return
        }
        IntentData.book = book
        when {
            longClick || !AppConfig.devFeat -> startActivity<BookInfoActivity> {
                putExtra("name", book.name)
                putExtra("author", book.author)
            }

            book.isVideo -> startActivity<VideoPlayActivity>()
            book.isRss -> startActivity<ReadRssActivity>()
            else -> startActivity<BookInfoActivity>()
        }
    }

    companion object {

        fun start(context: Context, key: String?) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
            }
        }

    }
}
