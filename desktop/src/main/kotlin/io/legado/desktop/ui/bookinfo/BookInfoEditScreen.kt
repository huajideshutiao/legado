package io.legado.desktop.ui.bookinfo

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
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changecover.ChangeCoverViewModelShared
import io.legado.app.ui.book.info.edit.BookInfoEditScreen as SharedBookInfoEditScreen
import io.legado.app.ui.book.info.edit.BookInfoEditUiActions
import io.legado.app.ui.book.info.edit.BookInfoEditUiState
import io.legado.app.ui.book.info.edit.BookInfoEditViewModelShared
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.desktop.ui.component.DesktopBookCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame

/**
 * 桌面端书籍信息编辑 Screen 入口 (包装 shared/sharedUiMain 的 [SharedBookInfoEditScreen])。
 *
 * # 职责
 *
 * 对照 desktop [BookInfoScreen] 模式, 仅做桌面平台适配, 业务展示与表单逻辑全部下沉到
 * shared/sharedUiMain 的 [SharedBookInfoEditScreen]:
 *
 * - **数据加载**: [LaunchedEffect] 异步查 [AppDbProviders.get].bookDao.getBook(bookUrl)
 *   加载本地完整 Book (含 customCoverUrl / customIntro 等)
 * - **编辑态**: 持有 name/author/typeIndex/coverUrl/intro/bookUrl/coverTick 各
 *   `mutableStateOf`, book 加载完成后初始化 (对照 [BookInfoEditActivity.upView])
 * - **actions**: 实现 [BookInfoEditUiActions] 12 个方法, 桥接桌面平台依赖:
 *   - onBack / onSave: 路由回调 (onSave 落库后切回 onSaved)
 *   - onSelectCover: 弹 [FileDialog] (LOAD 模式) 选本地图片
 *   - onChangeCoverSource: 弹 shared [ChangeCoverDialog] (书源搜索换封面, KMP 共享核心)
 *   - onRefreshCover: 写入 customCoverUrl + 递增 coverTick 驱动封面重载
 *   - onXxxChange: 更新对应编辑态字段
 * - **coverSlot**: 用 [DesktopBookCover.InfoCover] (JDK ImageIO + OkHttp, 不引入 Glide),
 *   用 [key] 包裹以支持 coverTick 变化时强制重载 (对照 app 端 ShelfCover reloadKey)
 *
 * # saveData 落库逻辑 (对照 [BookInfoEditActivity.saveData])
 *
 * - copy oldBook 备份 → 修改 name/author/type/customCoverUrl/customIntro →
 *   [BookStorageProviders.get].updateCacheFolder 重命名缓存目录 →
 *   调 [BookInfoEditViewModelShared.saveBook] 完成 DAO 写库 (bookUrl 变更则 delete+insert,
 *   否则 update) + 异常日志 (SQLiteConstraintException 类名匹配区分文案) → onSaved()
 *
 * 落库与异常处理下沉到 [BookInfoEditViewModelShared] (替代原 desktop 端直接调 DAO 的 no-op 模式),
 * desktop 端仅保留字段修改 + updateCacheFolder (依赖 [BookStorageProviders] 平台注入)。
 *
 * # 简化项
 *
 * - readBookUpdater 传 `{}` no-op (桌面端阅读流未与编辑流联动, ReadBook 单例未下沉)
 * - onChangeCoverSource 弹 shared ChangeCoverDialog (书源搜索换封面, 已下沉 commonMain + sharedUiMain)
 *
 * # 路由回调 (由 DesktopApp 注入)
 *
 * - [onBack]: 返回回调 (切回详情页/书架)
 * - [onSaved]: 保存成功回调 (切回详情页刷新)
 *
 * @param bookUrl 待编辑书籍的 bookUrl (由 DesktopApp 注入)
 * @param onBack 返回回调
 * @param onSaved 保存成功回调
 */
@Composable
fun BookInfoEditScreen(
    bookUrl: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    // 异步加载本地完整 Book (对照 BookInfoScreen.kt 的 LaunchedEffect 加载模式)
    // bookState 直接传给 actions 持有 (稳定引用, 回调内读写 .value 即时生效)
    val bookState = remember { mutableStateOf<Book?>(null) }
    var book by bookState
    LaunchedEffect(bookUrl) {
        book = AppDbProviders.get().bookDao.getBook(bookUrl)
    }

    // 编辑态字段 (对照 BookInfoEditActivity 同名字段)
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
    // 落库 ViewModel (KMP 共享核心, 替代原 desktop 端直接调 DAO 的 no-op 模式)
    // readBookUpdater 传 {} no-op: 桌面端阅读流未与编辑流联动, ReadBook 单例未下沉
    val shared = remember(scope) {
        BookInfoEditViewModelShared(scope = scope, readBookUpdater = {})
    }
    // 换封面 ViewModel (KMP 共享核心, commonMain ChangeCoverViewModelShared)
    // 桌面端通过 ChangeCoverPlatformDesktop 注入 threadCount + cleanAuthor
    val changeCoverVm = remember(scope) {
        ChangeCoverViewModelShared(scope, ChangeCoverPlatformDesktop())
    }
    // book 加载完成后初始化换封面 VM (book 变化时重新初始化, 与 ChangeSourceScreen 模式一致)
    LaunchedEffect(book) {
        val b = book ?: return@LaunchedEffect
        changeCoverVm.initData(b.name, b.author)
    }
    // 封面选择文案 (DesktopBookInfoEditActions 非 @Composable, 需预先缓存传入)
    val selectCoverImageLabel = rememberString("select_cover_image")

    // 换封面对话框状态 (false=隐藏, true=显示; DesktopBookInfoEditActions.onChangeCoverSource
    // 调注入的 onShowChangeCoverDialog 触发显示, 末尾 ChangeCoverDialog 渲染分支读取)
    var showChangeCoverDialog by remember { mutableStateOf(false) }

    val actions = remember(onBack, onSaved, scope, shared) {
        DesktopBookInfoEditActions(
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
            onShowChangeCoverDialog = {
                // 触发 ChangeCoverDialog 显示 (替换原 URL 输入 AlertDialog;
                // 末尾 ChangeCoverDialog 渲染分支读取 showChangeCoverDialog)
                showChangeCoverDialog = true
            },
        )
    }

    SharedBookInfoEditScreen(
        state = state,
        actions = actions,
        coverSlot = { b, modifier ->
            // key(coverTick) 包裹: coverTick 变化时强制重载封面 (对照 app 端 ShelfCover reloadKey)
            key(coverTickState.value) {
                DesktopBookCover.InfoCover(b, modifier)
            }
        },
    )

    // ---- ChangeCoverDialog 渲染 (书源搜索换封面, KMP 共享核心) ----
    // onChangeCoverSource 触发 showChangeCoverDialog=true; 用户点击搜索结果后
    //   onCoverSelected 写入 coverUrlState + 递增 coverTickState 驱动封面重载,
    //   复刻原 URL 对话框 `if (url.isNotBlank()) { coverUrlState.value = url; coverTickState.value++ }` 语义;
    //   "use_default_cover" 标记恢复原始封面 (清自定义, 对照 app 端 ChangeCoverDialog 默认封面项语义)
    if (showChangeCoverDialog) {
        ChangeCoverDialog(
            viewModel = changeCoverVm,
            onCoverSelected = { coverUrl ->
                // 默认封面标记: 恢复原始封面 (coverUrlState = book.coverUrl),
                // onSave 时 `book.customCoverUrl = if (newCoverUrl == book.coverUrl) null else newCoverUrl`
                // 会将 customCoverUrl 置 null (清自定义), 与 app 端 BookHelp.clearCover 语义一致
                if (coverUrl == "use_default_cover") {
                    book?.let { b -> coverUrlState.value = b.coverUrl ?: "" }
                } else if (coverUrl.isNotBlank()) {
                    // 普通 URL: 与原 URL 对话框行为一致 (写入 coverUrlState + 递增 coverTick)
                    coverUrlState.value = coverUrl
                }
                coverTickState.value++
            },
            onDismiss = { showChangeCoverDialog = false },
            coverSlot = { searchBook, modifier ->
                // 桌面端封面渲染: SearchBook 转 Book 后用 DesktopBookCover.InfoCover
                // (JDK ImageIO + OkHttp, 不引入 Glide; 与详情页 coverSlot 一致)
                DesktopBookCover.InfoCover(searchBook.toBook(), modifier)
            },
        )
    }
}

/**
 * 桌面端 [BookInfoEditUiActions] 实现。
 *
 * 持有各编辑态 [MutableState] 引用 (Compose 局部 mutableStateOf 的稳定引用),
 * 回调内读写 `.value` 即时触发重组。落库逻辑对照 [BookInfoEditActivity.saveData]。
 *
 * @param bookState 当前编辑的 Book (null 时 onSave/onRefreshCover 直接返回)
 * @param nameState 书名编辑态
 * @param authorState 作者编辑态
 * @param typeIndexState 类型索引编辑态 (0..5 对照 book_type 数组)
 * @param coverUrlState 封面路径编辑态
 * @param introState 简介编辑态
 * @param bookUrlState 书 URL 编辑态
 * @param coverTickState 封面重载 key (递增驱动 coverSlot 重载)
 * @param scope 协程作用域 (onSelectCover 弹文件选择)
 * @param shared 落库核心 (KMP 共享, onSave 调 [BookInfoEditViewModelShared.saveBook] 完成 DAO 写库 + 异常日志)
 * @param onBack 由 DesktopApp 注入的返回回调
 * @param onSaved 由 DesktopApp 注入的保存成功回调
 */
private class DesktopBookInfoEditActions(
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
    // 触发 ChangeCoverDialog 显示 (书源搜索换封面, KMP 共享核心;
    // BookInfoEditScreen 末尾 ChangeCoverDialog 渲染分支读取 showChangeCoverDialog)
    private val onShowChangeCoverDialog: () -> Unit,
) : BookInfoEditUiActions {

    override fun onBack() = onBack.invoke()

    override fun onSave() {
        // 字段修改 + updateCacheFolder 在 scope.launch 内执行 (与原 desktop 端一致,
        // 异常被协程处理器捕获而非崩溃; DAO 写库 + 异常日志下沉到 shared.saveBook 异步执行)
        scope.launch {
            val book = bookState.value ?: return@launch
            val oldBook = book.copy()
            book.name = nameState.value
            book.author = authorState.value
            // 构造 bookType: typeIndex 映射 + 保留 local 位 (对照 BookInfoEditActivity.saveData)
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
                BookType.rss
            )
            book.addType(bookType)
            // 与原始 coverUrl/intro 一致则置 null (清自定义), 否则写入编辑值
            val newCoverUrl = coverUrlState.value
            val newIntro = introState.value
            val newBookUrl = bookUrlState.value
            book.customCoverUrl = if (newCoverUrl == book.coverUrl) null else newCoverUrl
            book.customIntro = if (newIntro == book.intro) null else newIntro
            // 重命名缓存目录 (书名/作者变更后, 对照 BookHelp.updateCacheFolder)
            BookStorageProviders.get().updateCacheFolder(oldBook, book)
            // 落库 + 异常日志下沉到 shared (替代原直接调 dao; shared 内部 scope.launch(Dispatchers.IO))
            shared.saveBook(book, newBookUrl) {
                onSaved.invoke()
            }
        }
    }

    override fun onSelectCover() {
        // 弹 FileDialog (LOAD 模式) 选择本地图片文件, 写入 coverUrl 编辑态
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                val dialog = FileDialog(Frame(), selectCoverImageLabel, FileDialog.LOAD)
                dialog.setFile("*.png;*.jpg;*.jpeg;*.gif;*.bmp")
                dialog.isVisible = true
                dialog.files?.firstOrNull()?.absolutePath
            } ?: run {
                AppLog.put(jvmGetString("select_cover_cancelled"))
                return@launch
            }
            coverUrlState.value = path
            coverTickState.value++
        }
    }

    override fun onChangeCoverSource() {
        // 触发 ChangeCoverDialog 显示 (书源搜索换封面, KMP 共享核心;
        // BookInfoEditScreen 末尾 ChangeCoverDialog 渲染分支读取 showChangeCoverDialog,
        // onCoverSelected 写入 coverUrlState + 递增 coverTickState,
        // 复刻原 URL 对话框 `if (url.isNotBlank()) { coverUrlState.value = url; coverTickState.value++ }` 语义)
        onShowChangeCoverDialog.invoke()
    }

    override fun onRefreshCover() {
        // 写入 customCoverUrl + 递增 coverTick 驱动封面重载 (对照 BookInfoEditActivity.onRefreshCover)
        val book = bookState.value ?: return
        book.customCoverUrl = coverUrlState.value
        coverTickState.value++
    }

    override fun onNameChange(value: String) {
        nameState.value = value
    }

    override fun onAuthorChange(value: String) {
        authorState.value = value
    }

    override fun onTypeChange(index: Int) {
        typeIndexState.value = index
    }

    override fun onCoverUrlChange(value: String) {
        coverUrlState.value = value
    }

    override fun onIntroChange(value: String) {
        introState.value = value
    }

    override fun onBookUrlChange(value: String) {
        bookUrlState.value = value
    }
}
