package io.legado.app.ui.book.source.debug

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * 书源调试(纯 Compose)。intent 契约不变: 入 `key` extra (sourceUrl)。
 *
 * 薄壳模式: 实现 [BookSourceDebugUiActions] 接口供 shared 端 [BookSourceDebugScreen] 回调,
 * 状态字段由 Activity 托管, [Content] 内打包为 [BookSourceDebugUiState] 传入 shared 端
 * [BookSourceDebugScreen]; `autoLinkText` (依赖 android.util.Patterns.WEB_URL) 以
 * `linkifyText` 回调注入。exploreKinds / clearExploreKindsCache / showDialogFragment /
 * showHelp / selector / toastOnUi 等平台依赖保留在 Activity, 通过回调触发。
 */
class BookSourceDebugActivity : BaseComposeActivity(), BookSourceDebugUiActions {

    val viewModel by viewModels<BookSourceDebugModel>()

    private val logs = mutableStateListOf<String>()
    private var query by mutableStateOf("")
    private var helpVisible by mutableStateOf(true)
    private var loading by mutableStateOf(false)
    private var textMy by mutableStateOf(appCtx.getString(R.string.my))
    private var textFx by mutableStateOf(appCtx.getString(R.string.debug_fx_default))
    private var exploreKinds: List<ExploreKind> = emptyList()

    /** 对齐旧版 SearchView.clearFocus 语义：提交/出错后收键盘失焦，重新点搜索框可唤回帮助面板 */
    private var clearFocusTick by mutableIntStateOf(0)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.init(intent.getStringExtra("key")) {
            initHelpView()
        }
        viewModel.observe { state, msg ->
            lifecycleScope.launch {
                logs.add(msg)
                if (state == -1 || state == 1000) {
                    loading = false
                }
            }
        }
    }

    private fun initHelpView() {
        viewModel.bookSource?.searchRule?.checkKeyWord?.let {
            if (it.isNotBlank()) {
                textMy = it
            }
        }
        initExploreKinds()
    }

    @SuppressLint("SetTextI18n")
    private fun initExploreKinds() {
        lifecycleScope.launch {
            try {
                val kinds = viewModel.bookSource?.exploreKinds()?.filter {
                    !it.url.isNullOrBlank()
                }.orEmpty()
                exploreKinds = kinds
                kinds.firstOrNull()?.let {
                    textFx = "${it.title}::${it.url}"
                    if (it.title.startsWith("ERROR:")) {
                        logs.add(appCtx.getString(R.string.debug_explore_error, it.url))
                        helpVisible = false
                    }
                }
            } catch (e: NullPointerException) {
                logs.add(appCtx.getString(R.string.debug_explore_json_error, e))
                helpVisible = false
            }
        }
    }

    /** 对齐 SearchView.setQuery(text, submit) 语义 */
    private fun setQuery(text: String, submit: Boolean) {
        query = text
        if (submit) {
            helpVisible = false
            clearFocusTick++
            startSearch(text.ifBlank { appCtx.getString(R.string.my) })
        }
    }

    private fun prefixAutoComplete(prefix: String) {
        if (query.isBlank() || query.length <= 2) {
            setQuery(prefix, false)
        } else {
            if (!query.startsWith(prefix)) {
                setQuery("$prefix$query", true)
            } else {
                setQuery(query, true)
            }
        }
    }

    private fun startSearch(key: String) {
        logs.clear()
        viewModel.startDebug(key, {
            loading = true
        }, {
            toastOnUi(appCtx.getString(R.string.no_source_found))
        })
    }

    @Composable
    override fun Content() {
        val state = BookSourceDebugUiState(
            logs = logs,
            query = query,
            helpVisible = helpVisible,
            loading = loading,
            textMy = textMy,
            textFx = textFx,
            clearFocusTick = clearFocusTick,
        )
        BookSourceDebugScreen(
            state = state,
            actions = this,
        )
    }

    // ---- BookSourceDebugUiActions 实现 ----
    // 以 override fun onXxx() = xxx() 桥接现有方法, 不改动 Activity 内部其它调用点

    override fun onBack() = finish()

    override fun onQueryChange(text: String) {
        query = text
    }

    override fun onSubmitQuery() = setQuery(query, true)

    override fun onSearchFocusChanged(focused: Boolean) {
        helpVisible = focused
    }

    override fun onChipMyClick() = setQuery(textMy, true)

    override fun onChipSystemClick() = setQuery(appCtx.getString(R.string.system), true)

    override fun onChipFxClick() = setQuery(textFx, true)

    override fun onChipFxLongClick() {
        val kinds = exploreKinds
        if (kinds.isNotEmpty()) {
            @Suppress("USELESS_ELVIS")
            selector(appCtx.getString(R.string.select_explore), kinds.map { it.title ?: "" }) { _, index ->
                val explore = kinds[index]
                textFx = "${explore.title}::${explore.url}"
                setQuery(textFx, true)
            }
        }
    }

    override fun onChipDetailClick() = setQuery(query, true)

    override fun onChipTocClick() = prefixAutoComplete("++")

    override fun onChipContentClick() = prefixAutoComplete("--")

    override fun onShowSearchSrc() {
        showDialogFragment(TextDialog("html", viewModel.searchSrc))
    }

    override fun onShowBookSrc() {
        showDialogFragment(TextDialog("html", viewModel.bookSrc))
    }

    override fun onShowTocSrc() {
        showDialogFragment(TextDialog("html", viewModel.tocSrc))
    }

    override fun onShowContentSrc() {
        showDialogFragment(TextDialog("html", viewModel.contentSrc))
    }

    override fun onShowReviewSrc() {
        showDialogFragment(TextDialog("html", viewModel.reviewSrc))
    }

    override fun onRefreshExplore() {
        lifecycleScope.launch {
            viewModel.bookSource?.clearExploreKindsCache()
            logs.clear()
            helpVisible = true
            initExploreKinds()
        }
    }

    override fun onShowHelp() = showHelp("debugHelp")

}
