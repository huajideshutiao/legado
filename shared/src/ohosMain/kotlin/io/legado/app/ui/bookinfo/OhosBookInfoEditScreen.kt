package io.legado.app.ui.bookinfo

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.addType
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.file.pickDocuments
import io.legado.app.ui.book.info.edit.BookInfoEditScreen as SharedBookInfoEditScreen
import io.legado.app.ui.book.info.edit.BookInfoEditUiActions
import io.legado.app.ui.book.info.edit.BookInfoEditUiState
import io.legado.app.ui.book.info.edit.BookInfoEditViewModelShared
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 鸿蒙端书籍信息编辑 Screen 入口 (包装 shared/sharedUiMain 的 [SharedBookInfoEditScreen])。
 *
 * 对照 iOS 端 [io.legado.app.ui.bookinfo.IosBookInfoEditScreen] / desktop `BookInfoEditScreen.kt` 的包装模式,
 * 鸿蒙端在 OhosNavHost 的 BOOK_INFO_EDIT 路由分支调用本入口。
 *
 * 本文件仅做鸿蒙平台适配, 业务展示与表单逻辑全部下沉到 shared/sharedUiMain:
 * - **数据加载**: [LaunchedEffect] 异步查 [AppDbProviders.get].bookDao.getBook(bookUrl)
 * - **编辑态**: 持有 name/author/typeIndex/coverUrl/intro/bookUrl/coverTick 各 mutableStateOf
 * - **actions**: 实现 [BookInfoEditUiActions] 12 个方法, 桥接鸿蒙平台依赖:
 *   - onSelectCover: 调 [pickDocuments] (ohos stub, 后续接入 ohos.file.picker)
 *   - onChangeCoverSource: no-op + TODO (依赖 ChangeCoverPlatform actual, 鸿蒙端暂未提供)
 *   - onRefreshCover: 写入 customCoverUrl + 递增 coverTick
 *   - onSave: 字段修改 + updateCacheFolder + shared.saveBook 落库
 * - **coverSlot**: 用 stub Box (后续接入 Coil3 KMP 图片加载)
 *
 * readBookUpdater 传 `{}` no-op (鸿蒙阅读流未与编辑流联动, ReadBook 单例未下沉)。
 *
 * # 简化项 (与 iOS / desktop 差异)
 *
 * - 不接入 ChangeCoverDialog: 依赖 ChangeCoverPlatform actual 实现, 鸿蒙端暂未提供
 *   (对照 OhosBookInfoScreen 的 OhosBookInfoActions.onCoverLongClick TODO)
 * - onSelectCover 调 [pickDocuments] ohos stub (当前返回 null, 后续接入 DocumentViewPicker)
 * - 封面渲染为 stub Box (后续接入 Coil3 KMP 图片加载)
 *
 * @param bookUrl 待编辑书籍的 bookUrl (由 OhosNavHost 注入)
 * @param onBack 返回回调 (切回 BOOK_INFO 路由)
 * @param onSaved 保存成功回调 (切回 BOOK_INFO 路由)
 */
@Composable
fun OhosBookInfoEditScreen(
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
    // 落库 VM (KMP 共享核心, readBookUpdater no-op: 鸿蒙阅读流未与编辑流联动)
    val shared = remember(scope) {
        BookInfoEditViewModelShared(scope = scope, readBookUpdater = {})
    }

    val actions = remember(onBack, onSaved, scope, shared) {
        OhosBookInfoEditActions(
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
        )
    }

    SharedBookInfoEditScreen(
        state = state,
        actions = actions,
        coverSlot = { b, modifier -> OhosInfoCover(b, modifier) },
    )
}

/**
 * 鸿蒙端 [BookInfoEditUiActions] 实现 (对照 iOS `IosBookInfoEditActions` / desktop `DesktopBookInfoEditActions`)。
 *
 * 持有各编辑态 [MutableState] 引用, 回调内读写 .value 即时触发重组。
 * 落库逻辑对照 [BookInfoEditViewModelShared.saveBook]。
 */
private class OhosBookInfoEditActions(
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
        // 调 ohos DocumentViewPicker 选本地图片 (对照 iOS pickDocuments / desktop FileDialog)
        // 当前 pickDocuments 为 stub (返回 null), 后续接入 ohos.file.picker.DocumentViewPicker
        scope.launch {
            val urls = pickDocuments(
                contentTypes = listOf("public.image"),
                allowsMultiple = false,
            ) ?: return@launch
            val firstUrl = urls.firstOrNull() ?: return@launch
            coverUrlState.value = firstUrl
            coverTickState.value++
        }
    }

    override fun onChangeCoverSource() {
        // TODO: 弹 ChangeCoverDialog (书源搜索换封面), 依赖 ChangeCoverPlatform actual, 鸿蒙端暂未提供
        // 对照 OhosBookInfoScreen 的 OhosBookInfoActions.onCoverLongClick TODO
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

// ---- 鸿蒙端封面 stub (对照 OhosBookInfoScreen.OhosInfoCover / OhosBookshelfManageScreen.OhosInfoCover, 后续接入 Coil3 KMP) ----

/** 书籍封面 (stub, 后续接入 Coil3 KMP 图片加载) */
@Composable
private fun OhosInfoCover(book: Book?, modifier: Modifier = Modifier) {
    Box(modifier)
}
