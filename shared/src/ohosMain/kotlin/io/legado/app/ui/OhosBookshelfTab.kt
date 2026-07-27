package io.legado.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.AlertDialog
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupViewModelShared
import io.legado.app.ui.bookshelf.rememberOhosCoverBitmap
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 鸿蒙端书架 Tab Composable (替代 BookshelfTab.ets)。
 *
 * 顶栏 (56dp, ArcoBlue6 底, 白字) + 书籍列表 (封面 + 书名 + 未读徽标 + 作者 + 最新章节)。
 * 点击 → onOpenReader 回调 (宿主端跳 Reader); 长按 → 详情 Dialog。
 *
 * 顶栏搜索/菜单和列表项均通过回调交由宿主路由，使用 Compose material (MD2) 自绘，
 * 不引入 material3 构件。
 *
 * 数据源: AppDbProviders.get().bookDao.getBooksByGroup(BookGroup.IdAll) (直接调 KMP,
 * 替代 .ets 的 napi legado.bookshelfList() 桥接)。
 */
@Composable
fun OhosBookshelfTab(
    onOpenReader: (bookUrl: String, bookName: String, chapterIndex: Int) -> Unit,
    onSearchClick: () -> Unit,
    onOpenBookshelfManage: () -> Unit,
) {
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var detailBook by remember { mutableStateOf<Book?>(null) }
    var showGroupManage by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val groupViewModel = remember(scope) { GroupViewModelShared(scope) }
    val groups by produceState<List<BookGroup>>(emptyList()) {
        AppDbProviders.get().bookGroupDao.flowAll().collect { value = it }
    }

    LaunchedEffect(Unit) {
        books = runCatching {
            AppDbProviders.get().bookDao.getBooksByGroup(BookGroup.IdAll)
        }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        BookshelfTitleBar(
            onSearchClick = onSearchClick,
            onOpenBookshelfManage = onOpenBookshelfManage,
            onOpenGroupManage = { showGroupManage = true },
            onOpenLog = { showLogDialog = true },
        )
        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("书架为空", color = TextSecondary, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().background(AppBackground),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(books, key = { it.bookUrl }) { book ->
                    BookshelfItem(
                        book = book,
                        onClick = { onOpenReader(book.bookUrl, book.name, book.durChapterIndex) },
                        onLongClick = { detailBook = book },
                    )
                }
            }
        }
    }

    detailBook?.let { book ->
        BookDetailDialog(book, onDismiss = { detailBook = null })
    }

    if (showGroupManage) {
        GroupManageDialog(
            groups = groups,
            onAddGroup = { name ->
                groupViewModel.addGroup(name, -1, true, null) {}
            },
            onRenameGroup = { groupId, name ->
                groups.find { it.groupId == groupId.toLong() }?.let {
                    groupViewModel.upGroup(it.copy(groupName = name))
                }
            },
            onDeleteGroup = { groupId ->
                groups.find { it.groupId == groupId.toLong() }?.let {
                    groupViewModel.delGroup(it) {}
                }
            },
            onDismiss = { showGroupManage = false },
        )
    }

    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }
}

/** 顶栏: 白字标题 + 可点击搜索/溢出菜单 (对齐 BookshelfTab.ets TitleBar)。 */
@Composable
private fun BookshelfTitleBar(
    onSearchClick: () -> Unit,
    onOpenBookshelfManage: () -> Unit,
    onOpenGroupManage: () -> Unit,
    onOpenLog: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(DesignTokens.arcoBlue6).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "书架",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(48.dp).clickable(onClick = onSearchClick),
            contentAlignment = Alignment.Center,
        ) {
            Text("搜索", color = Color.White, fontSize = 14.sp)
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxSize().clickable { menuExpanded = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("⋮", color = Color.White, fontSize = 22.sp)
            }
            AppDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                BookshelfMenuItem("书架管理") {
                    menuExpanded = false
                    onOpenBookshelfManage()
                }
                BookshelfMenuItem("分组管理") {
                    menuExpanded = false
                    onOpenGroupManage()
                }
                BookshelfMenuItem("日志") {
                    menuExpanded = false
                    onOpenLog()
                }
            }
        }
    }
}

@Composable
private fun BookshelfMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(text)
    }
}

/** 书籍项: 封面 (失败回退首字符占位) + 书名/未读徽标 + 作者 + 最新章节 (对齐 BookshelfTab.ets BookItem)。 */
@Composable
private fun BookshelfItem(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 封面: OhosBookCover 真实加载; 失败回退原占位 (64x80, app_background 底, 首字符 28sp ArcoBlue6)
        val coverBitmap = rememberOhosCoverBitmap(book.getDisplayCover(), book)
        if (coverBitmap != null) {
            Image(
                bitmap = coverBitmap,
                contentDescription = book.name,
                modifier = Modifier.size(width = 64.dp, height = 80.dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier.size(width = 64.dp, height = 80.dp).background(AppBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    book.name.firstOrNull()?.toString() ?: "?",
                    color = DesignTokens.arcoBlue6,
                    fontSize = 28.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // 书籍信息: 80dp 高, 4dp 间距
        Column(
            Modifier.weight(1f).height(80.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 书名行 + 未读徽标
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    book.name,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val unread = unreadCount(book)
                if (unread > 0) {
                    // 未读徽标: lastCheckCount>0 用 ArcoBlue6, 否则 TextSecondary (对齐 .ets UnreadBadge)
                    val badgeColor = if (book.lastCheckCount > 0) DesignTokens.arcoBlue6 else TextSecondary
                    Text(
                        unread.toString(),
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clip(DesignTokens.shapeDefault)
                            .background(badgeColor)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                book.author.ifBlank { "—" },
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                book.latestChapterTitle.orEmpty(),
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 未读章数 (对齐 app 端 Book.getUnreadChapterNum 非模拟分支)。 */
private fun unreadCount(book: Book): Int {
    val n = book.totalChapterNum - book.durChapterIndex + (if (book.durChapterPos < 0) -1 else 0)
    return if (n > 0) n else 0
}

/** 详情对话框 (展示书名/作者/进度/最新/简介, 对齐 BookshelfTab.ets showBookInfo)。 */
@Composable
private fun BookDetailDialog(book: Book, onDismiss: () -> Unit) {
    val progress = "第 ${book.durChapterIndex + 1}/${book.totalChapterNum} 章"
    val latest = book.latestChapterTitle ?: "—"
    val intro = book.intro?.ifBlank { null } ?: "—"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.name) },
        text = {
            Column {
                Text("作者: ${book.author.ifBlank { "—" }}")
                Text("进度: $progress")
                Text("最新: $latest")
                Text("简介: $intro")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
