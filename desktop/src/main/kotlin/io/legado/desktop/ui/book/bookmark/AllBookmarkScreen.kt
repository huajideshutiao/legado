package io.legado.desktop.ui.book.bookmark

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.bookmark.AllBookmarkScreen as SharedAllBookmarkScreen
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.bookmark.AllBookmarkUiActions
import io.legado.app.ui.book.bookmark.AllBookmarkUiState
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 所有书签管理 Screen 桌面端入口。
 *
 * 包装 shared/sharedUiMain 下沉的 [SharedAllBookmarkScreen], 注入桌面端 Compose
 * CompositionLocal Provider (ThemeStore/AppConfig/EventBus/PreferenceStore),
 * 并用 [AppTheme] 提供统一主题, 使 commonMain 的 AppTitleBar + 分组吸顶列表
 * 在桌面 JVM 上正常工作。
 *
 * # 路由回调 (由 DesktopApp 注入)
 * - [onBack]: 切回上一路由
 * - [onOpenBookmark]: 点击书签跳转阅读, 由 DesktopApp 注入路由切换逻辑
 *   (设置 readerBook + pendingChapterIndex + 切到 READER 路由); 默认 no-op
 *   保证 DesktopApp 未注入时仍可编译, 跳转功能待 DesktopApp 接入后生效
 *
 * # 平台适配 (对照 app 端 AllBookmarkActivity / AllBookmarkViewModel)
 * - 导出 JSON: [FileDialog] SAVE 选保存路径 → [bookmarkDao.all] → [GSON.toJson] → 写文件
 * - 导出 Markdown: [FileDialog] SAVE 选保存路径 → [bookmarkDao.all] → Markdown 拼接 → 写文件
 * - 点击书签跳转阅读: 查 [bookDao.getBook] → 调 [onOpenBookmark] 回调 (DesktopApp 设置路由状态)
 * - 长按编辑书签: 接入 shared/sharedUiMain 下沉的 BookmarkDialog (onConfirm 入库 update, onDelete 删除)
 *
 * # 已实现的核心功能
 * - 列表加载 (flowAll 订阅, 按书名/作者排序)
 * - 按书分组吸顶展示 (shared 端 BookmarkList 内部分组逻辑)
 *
 * @param onBack 返回回调 (由 DesktopApp 注入)
 * @param onOpenBookmark 点击书签跳转阅读回调 (book + chapterIndex), 由 DesktopApp 注入
 */
@Composable
fun AllBookmarkScreen(
    onBack: () -> Unit,
    onOpenBookmark: (book: Book, chapterIndex: Int) -> Unit = { _, _ -> },
) {
    // 桌面端 Provider: 全部用 jvmMain/DesktopProviders.kt 的内存实现
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                AllBookmarkContent(
                    onBack = onBack,
                    onOpenBookmark = onOpenBookmark,
                )
            }
        }
    }
}

@Composable
private fun AllBookmarkContent(
    onBack: () -> Unit,
    onOpenBookmark: (book: Book, chapterIndex: Int) -> Unit,
) {
    // state: 书签列表 (无选中集合, 主键 time: Long)
    val state = remember { mutableStateOf(AllBookmarkUiState()) }
    // 书签编辑对话框状态 (null=隐藏, 非空=显示编辑对话框; editBookmark 触发, 末尾 BookmarkDialog 渲染)
    var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }
    val scope = rememberCoroutineScope()

    // 收集全部书签 (按 bookName/bookAuthor/chapterIndex/chapterPos 排序, DAO SQL 已排序)
    LaunchedEffect(Unit) {
        AppDatabaseProviders.get().appDb.bookmarkDao.flowAll()
            .catch { AppLog.put("所有书签界面获取数据失败\n${it.localizedMessage}", it) }
            .flowOn(Dispatchers.IO)
            .conflate()
            .collectLatest { bookmarks ->
                state.value = state.value.copy(bookmarks = bookmarks)
            }
    }

    // actions: 匿名 object 实现接口, 捕获 onBack / onOpenBookmark / scope
    val actions = remember {
        object : AllBookmarkUiActions {
            override fun onBack() = onBack()

            // 导出 JSON: FileDialog SAVE 选保存路径 → bookmarkDao.all → GSON.toJson → 写文件
            // 对照 app 端 AllBookmarkViewModel.exportBookmark (SAF 选目录 + GSON.writeToOutputStream)
            override fun export() {
                scope.launch {
                    val targetPath = withContext(Dispatchers.IO) {
                        val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
                        val dialog = FileDialog(Frame(), jvmGetString("export_bookmark_json"), FileDialog.SAVE)
                        dialog.setFile("bookmark-${dateFormat.format(Date())}.json")
                        dialog.isVisible = true
                        val dir = dialog.directory ?: return@withContext null
                        val file = dialog.file ?: return@withContext null
                        dir + file
                    } ?: run {
                        AppLog.put("导出书签: 用户取消选择")
                        return@launch
                    }
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val bookmarks = AppDatabaseProviders.get().appDb.bookmarkDao.all()
                            File(targetPath).writeText(GSON.toJson(bookmarks))
                        }
                        Toasters.get().toast(jvmGetString("export_success"))
                    }.onFailure {
                        AppLog.put("导出失败\n${it.localizedMessage}", it, true)
                    }
                }
            }

            // 导出 Markdown: FileDialog SAVE 选保存路径 → bookmarkDao.all → Markdown 拼接 → 写文件
            // 对照 app 端 AllBookmarkViewModel.exportBookmarkMd (SAF 选目录 + outputStream.write)
            override fun exportMd() {
                scope.launch {
                    val targetPath = withContext(Dispatchers.IO) {
                        val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
                        val dialog = FileDialog(Frame(), jvmGetString("export_bookmark_md"), FileDialog.SAVE)
                        dialog.setFile("bookmark-${dateFormat.format(Date())}.md")
                        dialog.isVisible = true
                        val dir = dialog.directory ?: return@withContext null
                        val file = dialog.file ?: return@withContext null
                        dir + file
                    } ?: run {
                        AppLog.put("导出书签: 用户取消选择")
                        return@launch
                    }
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val bookmarks = AppDatabaseProviders.get().appDb.bookmarkDao.all()
                            val sb = StringBuilder()
                            var name = ""
                            var author = ""
                            bookmarks.forEach {
                                if (it.bookName != name && it.bookAuthor != author) {
                                    name = it.bookName
                                    author = it.bookAuthor
                                    sb.append("## ${it.bookName} ${it.bookAuthor}\n\n")
                                }
                                sb.append("#### ${it.chapterName}\n\n")
                                sb.append("###### 原文\n ${it.bookText}\n\n")
                                sb.append("###### 摘要\n ${it.content}\n\n")
                            }
                            File(targetPath).writeText(sb.toString())
                        }
                        Toasters.get().toast(jvmGetString("export_success"))
                    }.onFailure {
                        AppLog.put("导出失败\n${it.localizedMessage}", it, true)
                    }
                }
            }

            // 点击书签跳转阅读: 查 book → 调 onOpenBookmark 回调 (DesktopApp 设置 readerBook + pendingChapterIndex)
            // 对照 app 端 AllBookmarkActivity.openBookmark (查 book + startActivityForBook 传 chapterIndex/Pos)
            // 注: 桌面端 ReaderScreen 当前仅消费 pendingChapterIndex, chapterPos 暂未对接 (需改 ReaderScreen)
            override fun openBookmark(bookmark: Bookmark) {
                scope.launch {
                    val book = withContext(Dispatchers.IO) {
                        AppDatabaseProviders.get().appDb.bookDao.getBook(bookmark.bookName, bookmark.bookAuthor)
                    }
                    if (book == null) {
                        Toasters.get().toast(jvmGetString("no_book"))
                        return@launch
                    }
                    onOpenBookmark(book, bookmark.chapterIndex)
                }
            }

            override fun editBookmark(bookmark: Bookmark, pos: Int) {
                // 触发 BookmarkDialog 显示 (末尾渲染分支读取 editingBookmark)
                editingBookmark = bookmark
            }
        }
    }

    SharedAllBookmarkScreen(state.value, actions)

    // ---- 书签编辑对话框 (editBookmark 触发, 调用 shared/sharedUiMain 下沉的 BookmarkDialog) ----
    // onConfirm: 把修改后的 bookmark 入库 (bookmarkDao.update), 对齐 app 端 BookmarkViewModel.upBookmark
    // onDismiss: 清空 editingBookmark 隐藏对话框
    // onDelete: 清空 editingBookmark 并从数据库删除该 bookmark, 对齐 app 端 BookmarkViewModel.delBookmark
    editingBookmark?.let { bm ->
        BookmarkDialog(
            bookmark = bm,
            showDelete = true,
            onConfirm = { updated ->
                editingBookmark = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabaseProviders.get().appDb.bookmarkDao.update(updated)
                    }
                }
            },
            onDismiss = { editingBookmark = null },
            onDelete = {
                val toDelete = editingBookmark
                editingBookmark = null
                toDelete?.let {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AppDatabaseProviders.get().appDb.bookmarkDao.delete(it)
                        }
                    }
                }
            },
        )
    }
}
