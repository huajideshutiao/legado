package io.legado.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup

/**
 * 鸿蒙端书架 Tab Composable (替代 BookshelfTab.ets)。
 *
 * 顶栏 (56dp, ArcoBlue6 底, 白字) + 书籍列表 (封面占位 + 书名 + 未读徽标 + 作者 + 最新章节)。
 * 点击 → onOpenReader 回调 (宿主端跳 Reader); 长按 → 详情 Dialog。
 *
 * Material3 等价: 顶栏可用 TopAppBar, 列表项可用 ListItem, 弹窗可用 AlertDialog。
 *
 * 数据源: AppDbProviders.get().bookDao.getBooksByGroup(BookGroup.IdAll) (直接调 KMP,
 * 替代 .ets 的 napi legado.bookshelfList() 桥接)。
 */
@Composable
fun OhosBookshelfTab(
    onOpenReader: (bookUrl: String, bookName: String, chapterIndex: Int) -> Unit,
) {
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var detailBook by remember { mutableStateOf<Book?>(null) }

    LaunchedEffect(Unit) {
        books = runCatching {
            AppDbProviders.get().bookDao.getBooksByGroup(BookGroup.IdAll)
        }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        BookshelfTitleBar()
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
}

/** 顶栏: 白字标题 + 搜索/溢出菜单占位 (对齐 BookshelfTab.ets TitleBar)。 */
@Composable
private fun BookshelfTitleBar() {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(ArcoBlue6).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "书架",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text("搜索", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(end = 16.dp))
        Text("⋮", color = Color.White, fontSize = 22.sp)
    }
}

/** 书籍项: 封面占位 (首字符) + 书名/未读徽标 + 作者 + 最新章节 (对齐 BookshelfTab.ets BookItem)。 */
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
        // 封面占位: 64x80, app_background 底, 首字符 28sp ArcoBlue6 (沿用 .ets 约定)
        Box(
            Modifier.size(width = 64.dp, height = 80.dp).background(AppBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                book.name.firstOrNull()?.toString() ?: "?",
                color = ArcoBlue6,
                fontSize = 28.sp,
            )
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
                    val badgeColor = if (book.lastCheckCount > 0) ArcoBlue6 else TextSecondary
                    Text(
                        unread.toString(),
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
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
