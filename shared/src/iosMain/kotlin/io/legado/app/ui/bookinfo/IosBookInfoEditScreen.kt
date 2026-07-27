package io.legado.app.ui.bookinfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.addType
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.file.pickDocuments
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changecover.ChangeCoverViewModelShared
import io.legado.app.ui.book.info.edit.BookInfoEditScreen as SharedBookInfoEditScreen
import io.legado.app.ui.book.info.edit.BookInfoEditUiActions
import io.legado.app.ui.book.info.edit.BookInfoEditUiState
import io.legado.app.ui.book.info.edit.BookInfoEditViewModelShared
import io.legado.app.ui.bookshelf.IosInfoCover
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.sharedStringTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * iOS 端书籍信息编辑 Screen 入口 (包装 shared/sharedUiMain 的 [SharedBookInfoEditScreen])。
 *
 * 对照 desktop `BookInfoEditScreen.kt` 包装模式, 仅做 iOS 平台适配, 业务展示与表单逻辑
 * 全部下沉到 shared/sharedUiMain:
 *
 * - **数据加载**: [LaunchedEffect] 异步查 [AppDbProviders.get].bookDao.getBook(bookUrl)
 * - **编辑态**: 持有 name/author/typeIndex/coverUrl/intro/bookUrl/coverTick 各 mutableStateOf
 * - **actions**: 实现 [BookInfoEditUiActions] 12 个方法, 桥接 iOS 平台依赖:
 *   - onSelectCover: 调 [pickDocuments] (UIDocumentPickerViewController) 选本地图片
 *   - onChangeCoverSource: 弹 shared [ChangeCoverDialog] (KMP 共享核心)
 *   - onRefreshCover: 写入 customCoverUrl + 递增 coverTick
 *   - onSave: 字段修改 + updateCacheFolder + shared.saveBook 落库
 * - **coverSlot**: 用 [IosInfoCover] (UIImage + Skia ImageBitmap, 不引入 Glide)
 *
 * readBookUpdater 传 `{}` no-op (iOS 阅读流未与编辑流联动, ReadBook 单例未下沉)。
 *
 * @param bookUrl 待编辑书籍的 bookUrl (由 IosNavHost 注入)
 * @param onBack 返回回调
 * @param onSaved 保存成功回调
 */
@Composable
fun IosBookInfoEditScreen(
    bookUrl: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val bookState = remember { mutableStateOf<Book?>(null) }
    var book by bookState
    LaunchedEffect(bookUrl) {
        book = AppDbProviders.get().bookDao.getBook(bookUrl)
    }

    val nameState = remember { mutableStateOf("") }
    val authorState = remember { mutableStateOf("") }
    val typeIndexState = remember { mutableStateOf(0) }
    val coverUrlState = remember { mutableStateOf("") }
    val introState = remember { mutableStateOf("") }
    val editBookUrlState = remember { mutableStateOf("") }
    val coverTickState = remember { mutableStateOf(0) }

    // book 加载完成后初始化编辑态 (对照 BookInfoEditActivity.upView)
    LaunchedEffect(book) {
        val b = book ?: return@LaunchedEffect
        nameState.value = b.name
        authorState.value = b.author
        typeIndexState.value = when {
            b.isRss -> 5
            b.isVideo -> 4
            b.isWebFile -> 3
            b.isImage -> 2
            b.isAudio -> 1
            else -> 0
        }
        coverUrlState.value = b.getDisplayCover().orEmpty()
        introState.value = b.getDisplayIntro().orEmpty()
        editBookUrlState.value = b.bookUrl
        coverTickState.value++
    }

    val state = BookInfoEditUiState(
        book = book,
        name = nameState.value,
        author = authorState.value,
        typeIndex = typeIndexState.value,
        coverUrl = coverUrlState.value,
        intro = introState.value,
        bookUrl = editBookUrlState.value,
        coverTick = coverTickState.value,
    )

    val scope = rememberCoroutineScope()
    // 落库 VM (KMP 共享核心, readBookUpdater no-op)
    val shared = remember(scope) {
        BookInfoEditViewModelShared(scope = scope, readBookUpdater = {})
    }
    // 换封面 VM (KMP 共享核心, iOS 端 IosChangeCoverPlatform 注入)
    val changeCoverVm = remember(scope) {
        ChangeCoverViewModelShared(scope, IosChangeCoverPlatform())
    }
    LaunchedEffect(book) {
        val b = book ?: return@LaunchedEffect
        changeCoverVm.initData(b.name, b.author)
    }

    val selectCoverImageLabel = rememberString("select_cover_image")

    var showChangeCoverDialog by remember { mutableStateOf(false) }

    val actions = remember(onBack, onSaved, scope, shared) {
        IosBookInfoEditActions(
            bookState = bookState,
            nameState = nameState,
            authorState = authorState,
            typeIndexState = typeIndexState,
            coverUrlState = coverUrlState,
            introState = introState,
            bookUrlState = editBookUrlState,
            coverTickState = coverTickState,
            scope = scope,
            shared = shared,
            onBack = onBack,
            onSaved = onSaved,
            selectCoverImageLabel = selectCoverImageLabel,
            onShowChangeCoverDialog = { showChangeCoverDialog = true },
        )
    }

    SharedBookInfoEditScreen(
        state = state,
        actions = actions,
        coverSlot = { b, modifier ->
            // key(coverTick) 包裹: coverTick 变化时强制重载封面 (对照 app 端 ShelfCover reloadKey)
            key(coverTickState.value) {
                IosInfoCover(b, modifier)
            }
        },
    )

    // 换封面对话框 (KMP 共享核心)
    if (showChangeCoverDialog) {
        ChangeCoverDialog(
            viewModel = changeCoverVm,
            onCoverSelected = { coverUrl ->
                if (coverUrl == "use_default_cover") {
                    book?.let { b -> coverUrlState.value = b.coverUrl ?: "" }
                } else if (coverUrl.isNotBlank()) {
                    coverUrlState.value = coverUrl
                }
                coverTickState.value++
            },
            onDismiss = { showChangeCoverDialog = false },
            coverSlot = { searchBook, modifier ->
                IosInfoCover(searchBook.toBook(), modifier)
            },
        )
    }
}

/**
 * iOS 端 [BookInfoEditUiActions] 实现。
 *
 * 持有各编辑态 [MutableState] 引用, 回调内读写 .value 即时触发重组。
 * 落库逻辑对照 [io.legado.app.ui.book.info.edit.BookInfoEditViewModelShared.saveBook]。
 */
private class IosBookInfoEditActions(
    private val bookState: MutableState<Book?>,
    private val nameState: MutableState<String>,
    private val authorState: MutableState<String>,
    private val typeIndexState: MutableState<Int>,
    private val coverUrlState: MutableState<String>,
    private val introState: MutableState<String>,
    private val bookUrlState: MutableState<String>,
    private val coverTickState: MutableState<Int>,
    private val scope: CoroutineScope,
    private val shared: BookInfoEditViewModelShared,
    private val onBack: () -> Unit,
    private val onSaved: () -> Unit,
    private val selectCoverImageLabel: String,
    private val onShowChangeCoverDialog: () -> Unit,
) : BookInfoEditUiActions {

    override fun onBack() = onBack.invoke()

    override fun onSave() {
        scope.launch {
            val book = bookState.value ?: return@launch
            val oldBook = book.copy()
            book.name = nameState.value
            book.author = authorState.value
            val local = if (book.isLocal) BookType.local else 0
            val bookType = when (typeIndexState.value) {
                5 -> BookType.rss or local
                4 -> BookType.video or local
                3 -> BookType.webFile or local
                2 -> BookType.image or local
                1 -> BookType.audio or local
                else -> BookType.text or local
            }
            book.removeType(
                BookType.local,
                BookType.image,
                BookType.audio,
                BookType.text,
                BookType.video,
                BookType.webFile,
                BookType.rss,
            )
            book.addType(bookType)
            val newCoverUrl = coverUrlState.value
            val newIntro = introState.value
            val newBookUrl = bookUrlState.value
            book.customCoverUrl = if (newCoverUrl == book.coverUrl) null else newCoverUrl
            book.customIntro = if (newIntro == book.intro) null else newIntro
            BookStorageProviders.get().updateCacheFolder(oldBook, book)
            shared.saveBook(book, newBookUrl) { onSaved.invoke() }
        }
    }

    override fun onSelectCover() {
        // 调 UIDocumentPickerViewController 选本地图片 (对照 desktop FileDialog)
        scope.launch {
            val urls = pickDocuments(
                contentTypes = listOf("public.image"),
                allowsMultiple = false,
            ) ?: return@launch
            val firstUrl = urls.firstOrNull() ?: return@launch
            // 将图片拷贝到 customCoverPath (与 app 端选图后存路径一致), 这里直接用 url.absoluteString
            val path = firstUrl.path ?: run {
                AppLog.put(sharedStringTable["pick_image_failed_log"]!!)
                return@launch
            }
            coverUrlState.value = path
            coverTickState.value++
        }
    }

    override fun onChangeCoverSource() {
        onShowChangeCoverDialog.invoke()
    }

    override fun onRefreshCover() {
        val book = bookState.value ?: return
        book.customCoverUrl = coverUrlState.value
        coverTickState.value++
    }

    override fun onNameChange(value: String) { nameState.value = value }
    override fun onAuthorChange(value: String) { authorState.value = value }
    override fun onTypeChange(index: Int) { typeIndexState.value = index }
    override fun onCoverUrlChange(value: String) { coverUrlState.value = value }
    override fun onIntroChange(value: String) { introState.value = value }
    override fun onBookUrlChange(value: String) { bookUrlState.value = value }
}
