package io.legado.app.ui.book.info.edit

import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.model.CoverRatio
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.main.bookshelf.ShelfCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.FileOutputStream

/**
 * 书籍信息编辑 Activity (薄壳模式)。
 *
 * Composable 已下沉到 shared/sharedUiMain 的 [BookInfoEditScreen], 本 Activity 仅:
 * - 持有各编辑态字段 (name/author/typeIndex/coverUrl/intro/bookUrl/coverTick) 的
 *   `mutableStateOf` 与 [BookInfoEditViewModel]
 * - 实现 [BookInfoEditUiActions] 接口, 在回调内桥接平台依赖 (HandleFileContract /
 *   ChangeCoverDialog / FileUtils / MD5Utils / externalFiles / readUri)
 * - [Content] 内构造 state, 调用 [BookInfoEditScreen] 渲染
 *
 * 选图 ([selectCover]) / 换源弹窗 ([showDialogFragment] + [ChangeCoverDialog]) /
 * Uri 落盘 ([coverChangeTo] (Uri)) 仍保留在本类: 它们依赖 Android 专属 API。
 */
class BookInfoEditActivity :
    BaseComposeActivity(),
    ChangeCoverDialog.CallBack,
    BookInfoEditUiActions {

    private val selectCover by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                coverChangeTo(uri)
            }
        }
    }

    val viewModel by viewModels<BookInfoEditViewModel>()

    // ---- 编辑态(镜像原 tie_* 输入框) ----
    private var name by mutableStateOf("")
    private var author by mutableStateOf("")
    private var typeIndex by mutableIntStateOf(0)
    private var coverUrl by mutableStateOf("")
    private var intro by mutableStateOf("")
    private var bookUrl by mutableStateOf("")
    private var coverTick by mutableIntStateOf(0)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.loadBook()
        upView(viewModel.book!!)
    }

    private fun upView(book: Book) {
        name = book.name
        author = book.author
        typeIndex = when {
            book.isRss -> 5
            book.isVideo -> 4
            book.isWebFile -> 3
            book.isImage -> 2
            book.isAudio -> 1
            else -> 0
        }
        coverUrl = book.getDisplayCover().orEmpty()
        intro = book.getDisplayIntro().orEmpty()
        bookUrl = book.bookUrl
        coverTick++
    }

    @Composable
    override fun Content() {
        val state = BookInfoEditUiState(
            book = viewModel.book,
            name = name,
            author = author,
            typeIndex = typeIndex,
            coverUrl = coverUrl,
            intro = intro,
            bookUrl = bookUrl,
            coverTick = coverTick,
        )
        BookInfoEditScreen(
            state = state,
            actions = this,
            coverSlot = { book, modifier ->
                ShelfCover(
                    path = book?.getDisplayCover(),
                    name = book?.name,
                    author = book?.author,
                    origin = book?.origin,
                    ratio = CoverRatio.NOVEL,
                    reloadKey = coverTick,
                    inBookshelf = true,
                    modifier = modifier,
                )
            },
        )
    }

    // ===== BookInfoEditUiActions 适配 =====

    override fun onBack() = finish()

    override fun onSave() = saveData()

    override fun onSelectCover() {
        selectCover.launch {
            mode = HandleFileContract.IMAGE
        }
    }

    override fun onChangeCoverSource() {
        viewModel.book?.let {
            showDialogFragment(ChangeCoverDialog(it.name, it.author))
        }
    }

    override fun onRefreshCover() {
        viewModel.book?.customCoverUrl = coverUrl
        coverTick++
    }

    override fun onNameChange(value: String) {
        name = value
    }

    override fun onAuthorChange(value: String) {
        author = value
    }

    override fun onTypeChange(index: Int) {
        typeIndex = index
    }

    override fun onCoverUrlChange(value: String) {
        coverUrl = value
    }

    override fun onIntroChange(value: String) {
        intro = value
    }

    override fun onBookUrlChange(value: String) {
        bookUrl = value
    }

    // ===== 平台相关方法 (依赖 HandleFileContract / FileUtils / MD5Utils / externalFiles / readUri, 不下沉) =====

    private fun saveData() {
        val book = viewModel.book ?: return
        val oldBook = book.copy()
        book.name = name
        book.author = author
        val local = if (book.isLocal) BookType.local else 0
        val bookType = when (typeIndex) {
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
            BookType.rss
        )
        book.addType(bookType)
        book.customCoverUrl = if (coverUrl == book.coverUrl) null else coverUrl
        book.customIntro = if (intro == book.intro) null else intro
        BookHelp.updateCacheFolder(oldBook, book)
        viewModel.saveBook(book, bookUrl) {
            setResult(RESULT_OK)
            book.bookUrl = bookUrl
            IntentData.book = book
            finish()
        }
    }

    override fun coverChangeTo(coverUrl: String) {
        viewModel.book?.customCoverUrl = coverUrl
        this.coverUrl = coverUrl
        coverTick++
    }

    private fun coverChangeTo(uri: Uri) {
        readUri(uri) { fileDoc, inputStream ->
            runCatching {
                inputStream.use {
                    var file = this.externalFiles
                    val suffix = fileDoc.name.substringAfterLast(".")
                    val fileName = uri.inputStream(this).getOrThrow().use {
                        MD5Utils.md5Encode(it) + ".$suffix"
                    }
                    file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    coverChangeTo(file.absolutePath)
                }
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }
}
