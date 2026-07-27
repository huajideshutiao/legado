package io.legado.app.ui.main.bookshelf

import androidx.activity.compose.LocalActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.IntentData
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.import.remote.RemoteBookActivity
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.manage.BookshelfManageActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.bookshelf.BookshelfActionsCallbacks
import io.legado.app.ui.bookshelf.BookshelfTabController
import io.legado.app.ui.bookshelf.ShelfScrollState
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.main.bookshelf.style1.BookshelfScreen1
import io.legado.app.ui.main.bookshelf.style1.BookshelfState1
import io.legado.app.ui.main.bookshelf.style2.BookshelfScreen2
import io.legado.app.ui.main.bookshelf.style2.BookshelfState2
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.eventObservable
import io.legado.app.utils.readText
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// BookshelfTabController interface 已下沉到 shared:
// modules/shared/src/sharedUiMain/kotlin/io/legado/app/ui/bookshelf/BookshelfComposablesShared.kt
// (包名 io.legado.app.ui.bookshelf, 本文件 import 引用, BaseBookshelfState 实现该接口)

/**
 * 书架双风格共享的壳层状态：菜单动作/事件计数/添加进度，对照原 BaseBookshelfFragment。
 * ViewModel 均取 activity 级，跨 style 重建保留。
 */
abstract class BaseBookshelfState(
    protected val activity: AppCompatActivity,
    val mainViewModel: MainViewModel,
    val viewModel: BookshelfViewModel,
    protected val scope: CoroutineScope,
) : BookshelfTabController {

    abstract val groupId: Long
    abstract val books: List<Book>

    /** BOOKSHELF_REFRESH 自增: 封面重载 + AppConfig 显示项重读(等价 notifyDataSetChanged) */
    var configTick by mutableIntStateOf(0)
        private set

    /** upSort 自增: 各页重建 DB flow 重排序 */
    var sortTick by mutableIntStateOf(0)
        private set

    /** 正在更新目录的书(UP_BOOKSHELF 增量维护)，条目转圈依据 */
    var refreshingUrls by mutableStateOf<Set<String>>(emptySet())
        private set

    private var waitDialog: WaitDialog? = null

    private val importBookshelfLauncher by lazy {
        activity.registerHandleFile { result ->
            kotlin.runCatching {
                result.uri?.readText(activity)?.let { text ->
                    viewModel.importBookshelf(text, groupId)
                }
            }.onFailure {
                activity.toastOnUi(it.localizedMessage ?: "ERROR")
            }
        }
    }

    abstract fun upGroup(data: List<BookGroup>)

    override fun upSort() {
        sortTick++
    }

    // ---- BookshelfTab 事件接线 ----

    fun onBookshelfRefresh() {
        configTick++
    }

    fun onUpBookshelf(bookUrl: String) {
        refreshingUrls = if (mainViewModel.isUpdate(bookUrl)) {
            refreshingUrls + bookUrl
        } else {
            refreshingUrls - bookUrl
        }
    }

    fun onAddBookProgress(count: Int) {
        if (count < 0) {
            waitDialog?.dismissSafe()
        } else {
            ensureWaitDialog().setText("添加中... ($count)")
        }
    }

    private fun ensureWaitDialog(): WaitDialog {
        return waitDialog ?: WaitDialog.from(activity).apply {
            onCancelListener = {
                viewModel.addBookJob?.cancel()
            }
            waitDialog = this
        }
    }

    // ---- 菜单动作(替原 main_bookshelf.xml, 由 Compose 溢出菜单直调) ----

    fun openSearch() = activity.startActivity<SearchActivity>()

    fun refreshShelf() = mainViewModel.forceRefresh(books)

    fun addLocalBook() = activity.startActivity<ImportBookActivity>()

    fun addRemoteBook() = activity.startActivity<RemoteBookActivity>()

    fun openBookshelfManage() = activity.startActivity<BookshelfManageActivity> {
        putExtra("groupId", groupId)
    }

    fun showGroupManage() = activity.showDialogFragment<GroupManageDialog>()

    fun importBookshelf() = importBookshelfAlert(groupId)

    fun showAppLog() = activity.showDialogFragment<AppLogDialog>()

    fun showAddBookByUrlAlert() {
        activity.alert(titleResource = R.string.add_book_url) {
            val getText = editTextView(hint = "url", autoFocus = true)
            okButton {
                getText().let {
                    ensureWaitDialog().run {
                        setText("添加中...")
                        show()
                    }
                    viewModel.addBookByUrl(it)
                }
            }
            cancelButton()
        }
    }

    private fun importBookshelfAlert(groupId: Long) {
        activity.alert(titleResource = R.string.import_bookshelf) {
            val getText = editTextView(hint = "url/json")
            okButton {
                getText().let {
                    viewModel.importBookshelf(it, groupId)
                }
            }
            cancelButton()
            neutralButton(R.string.select_file) {
                importBookshelfLauncher.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            }
        }
    }

    // ---- 条目动作 ----

    fun open(book: Book) {
        activity.startActivityForBook(book.copy())
    }

    fun openBookInfo(book: Book) {
        activity.startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            IntentData.book = book.copy()
        }
    }

    fun showGroupEdit(group: BookGroup) {
        activity.showDialogFragment(GroupEditDialog(group))
    }
}

/**
 * 把 BaseBookshelfState 的菜单动作适配为 shared 版 [BookshelfActionsCallbacks]。
 *
 * shared 版 [io.legado.app.ui.bookshelf.BookshelfActions] 接受 BookshelfActionsCallbacks
 * 而非 BaseBookshelfState (避免 shared 反向依赖 app 模块), app 端调用方用此扩展函数桥接。
 */
fun BaseBookshelfState.toBookshelfActionsCallbacks(): BookshelfActionsCallbacks =
    BookshelfActionsCallbacks(
        onOpenSearch = { openSearch() },
        onRefreshShelf = { refreshShelf() },
        onAddLocalBook = { addLocalBook() },
        onAddRemoteBook = { addRemoteBook() },
        onShowAddBookByUrlAlert = { showAddBookByUrlAlert() },
        onOpenBookshelfManage = { openBookshelfManage() },
        onShowGroupManage = { showGroupManage() },
        onImportBookshelf = { importBookshelf() },
        onShowAppLog = { showAppLog() },
    )

/**
 * 书架 tab（状态上浮）：style 同 AppConfig.bookGroupStyle，0=分组 tab(style1)，1=文件夹单列表(style2)。
 * key(style) 整树重建，等价旧 adapter POSITION_NONE。
 */
@Composable
fun BookshelfTab(style: Int, onController: (BookshelfTabController) -> Unit) {
    key(style) {
        val activity = LocalActivity.current as AppCompatActivity
        val mainViewModel = remember(activity) { ViewModelProvider(activity)[MainViewModel::class.java] }
        val shelfViewModel = remember(activity) { ViewModelProvider(activity)[BookshelfViewModel::class.java] }
        val scope = rememberCoroutineScope()
        if (style == 1) {
            val scroll = rememberSaveable(saver = ShelfScrollState.Saver) { ShelfScrollState() }
            val state = remember {
                BookshelfState2(activity, mainViewModel, shelfViewModel, scope, scroll)
            }
            BookshelfEffects(state)
            SideEffect { onController(state) }
            BookshelfScreen2(state)
        } else {
            val scrollStates = rememberSaveable(saver = ScrollMapSaver) {
                hashMapOf<Long, ShelfScrollState>()
            }
            val state = remember {
                BookshelfState1(activity, mainViewModel, shelfViewModel, scope, scrollStates)
            }
            BookshelfEffects(state)
            SideEffect { onController(state) }
            BookshelfScreen1(state)
        }
    }
}

/** style1 每分组滚动位: 展平为 [groupId, listIdx, listOff, gridIdx, gridOff] * n */
private val ScrollMapSaver = listSaver<HashMap<Long, ShelfScrollState>, Any>(
    save = { map ->
        map.flatMap { (id, s) ->
            listOf(
                id,
                s.list.firstVisibleItemIndex, s.list.firstVisibleItemScrollOffset,
                s.grid.firstVisibleItemIndex, s.grid.firstVisibleItemScrollOffset,
            )
        }
    },
    restore = { flat ->
        flat.chunked(5).associateTo(hashMapOf()) {
            it[0] as Long to ShelfScrollState(it[1] as Int, it[2] as Int, it[3] as Int, it[4] as Int)
        }
    },
)

/** 壳层副作用：分组 Flow/添加进度/事件总线，对照原 initBookGroupData + observeLiveBus。 */
@Composable
private fun BookshelfEffects(state: BaseBookshelfState) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(state, owner) {
        val progressObserver = Observer<Int> { state.onAddBookProgress(it) }
        state.viewModel.addBookProgressLiveData.observe(owner, progressObserver)
        onDispose {
            state.viewModel.addBookProgressLiveData.removeObserver(progressObserver)
        }
    }
    LaunchedEffect(state, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            appDb.bookGroupDao.flowShow().collect { state.upGroup(it) }
        }
    }
    LaunchedEffect(state) {
        launch {
            eventObservable(EventBus.UP_BOOKSHELF).collect {
                (it as? String)?.let(state::onUpBookshelf)
            }
        }
        launch {
            eventObservable(EventBus.BOOKSHELF_REFRESH).collect {
                if (it is String) state.onBookshelfRefresh()
            }
        }
    }
}
