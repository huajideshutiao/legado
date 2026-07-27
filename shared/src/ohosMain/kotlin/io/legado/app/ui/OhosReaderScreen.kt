package io.legado.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookStorageProviders
import kotlinx.coroutines.launch

/**
 * 鸿蒙端阅读界面 Composable (替代 Reader.ets)。
 *
 * 顶栏 (返回 + 书名 + 章节序号 + 菜单) + 正文区 (Text 16sp 行高 28) + 底部翻页按钮 (上一章/Slider/下一章)
 * + 左侧章节目录抽屉 (70% 面板 + 30% 遮罩)。
 *
 * 现状: 顶栏/抽屉/翻页按钮均用 Compose 实现 (对齐 Material3 TopAppBar/ModalDrawer/Button)。
 *
 * 数据源:
 * - `AppDbProviders.get().bookChapterDao.getChapterList(bookUrl)` 取章节目录 (替代 napi chapterList)
 * - `BookStorageProviders.get().getContent(book, chapter)` 取章节正文 (替代 napi loadChapter)
 * - 仅读本地缓存, 不联网拉取 (对齐 Reader.ets 简化语义)
 */
@Composable
fun OhosReaderScreen(
    bookUrl: String,
    bookName: String,
    initialChapterIndex: Int,
    onBack: () -> Unit,
    // 章节换源回调: 传递当前 book/chapterIndex/chapterTitle, 由 OhosNavHost 设置状态并切换到 CHANGE_CHAPTER_SOURCE 路由
    onChapterChangeSource: (Book, Int, String) -> Unit = { _, _, _ -> },
    // 书内全文搜索回调: 传递当前 book, 由 OhosNavHost 设置状态并切换到 SEARCH_CONTENT 路由
    onSearchContentClick: (Book) -> Unit = {},
) {
    var chapterList by remember { mutableStateOf<List<BookChapter>>(emptyList()) }
    var chapterIndex by remember { mutableStateOf(initialChapterIndex) }
    var chapterContent by remember { mutableStateOf("加载中...") }
    var readChapters by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var drawerVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableStateOf(initialChapterIndex.toFloat()) }
    val coroutineScope = rememberCoroutineScope()

    // 加载章节目录 + 起始章节正文 (对齐 Reader.ets aboutToAppear)
    LaunchedEffect(bookUrl) {
        chapterList = runCatching {
            AppDbProviders.get().bookChapterDao.getChapterList(bookUrl)
        }.getOrDefault(emptyList())
        loadChapterContent(bookUrl, chapterIndex) { content ->
            chapterContent = content
            readChapters = readChapters + chapterIndex
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶部标题栏 (对齐 Reader.ets 顶部 Row)
            Row(
                Modifier.fillMaxWidth().height(56.dp).background(ArcoBlue6).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "返回",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = onBack),
                )
                Text(
                    bookName.ifBlank { "(未选择书籍)" },
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    "第 ${chapterIndex + 1} 章",
                    color = Color.White,
                    fontSize = 12.sp,
                )
                // 菜单按钮 (点击展开目录/设置/朗读, 对齐 Reader.ets ☰ bindMenu)
                Box {
                    Text(
                        "☰",
                        color = Color.White,
                        fontSize = 20.sp,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .clickable { menuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("目录") },
                            onClick = {
                                menuExpanded = false
                                drawerVisible = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("设置") },
                            onClick = { menuExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("朗读") },
                            onClick = { menuExpanded = false },
                        )
                        // 章节换源 (对齐 app 端 ReadMenuAction.CHAPTER_CHANGE_SOURCE, 传递 book/index/title 触发路由切换)
                        DropdownMenuItem(
                            text = { Text("章节换源") },
                            onClick = {
                                menuExpanded = false
                                coroutineScope.launch {
                                    val book = AppDbProviders.get().bookDao.getBook(bookUrl) ?: return@launch
                                    val chapter = chapterList.getOrNull(chapterIndex) ?: return@launch
                                    onChapterChangeSource(book, chapter.index, chapter.title)
                                }
                            },
                        )
                        // 书内全文搜索 (对齐 app 端 ReadMenuAction.SEARCH_CONTENT, 传递 book 触发路由切换)
                        DropdownMenuItem(
                            text = { Text("全文搜索") },
                            onClick = {
                                menuExpanded = false
                                coroutineScope.launch {
                                    val book = AppDbProviders.get().bookDao.getBook(bookUrl) ?: return@launch
                                    onSearchContentClick(book)
                                }
                            },
                        )
                    }
                }
            }

            // 正文区域 (对齐 Reader.ets Scroll + Text, 16sp 行高 28, padding 20)
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .background(Color.White)
                    .padding(20.dp),
            ) {
                Text(
                    chapterContent,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    lineHeight = 28.sp,
                )
            }

            // 底部翻页按钮区 (对齐 Reader.ets 底部 Row, 72dp 高, padding 12)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(AppBackground)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 上一章按钮 (◀ 44x44, app_background 底, primary 字色, chapterIndex>0 时启用)
                Button(
                    onClick = {
                        if (chapterIndex > 0) {
                            val target = chapterIndex - 1
                            chapterIndex = target
                            sliderValue = target.toFloat()
                            coroutineScope.launch {
                                loadChapterContent(bookUrl, target) { content ->
                                    chapterContent = content
                                    readChapters = readChapters + target
                                }
                            }
                        }
                    },
                    enabled = chapterIndex > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppBackground,
                        contentColor = TextPrimary,
                    ),
                    modifier = Modifier.size(44.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("◀", fontSize = 18.sp)
                }

                // 章节进度条 (对齐 Reader.ets Slider, 松手时跳转)
                val maxIndex = (chapterList.size - 1).coerceAtLeast(0)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        val target = sliderValue.toInt()
                        if (target != chapterIndex) {
                            chapterIndex = target
                            coroutineScope.launch {
                                loadChapterContent(bookUrl, target) { content ->
                                    chapterContent = content
                                    readChapters = readChapters + target
                                }
                            }
                        }
                    },
                    valueRange = 0f..maxIndex.toFloat(),
                    steps = if (maxIndex > 0) maxIndex - 1 else 0,
                    modifier = Modifier.weight(1f),
                )

                // 下一章按钮 (▶ 44x44, ArcoBlue6 底, 白字, 非末章时启用)
                val hasNext = chapterList.isEmpty() || chapterIndex < chapterList.size - 1
                Button(
                    onClick = {
                        val target = chapterIndex + 1
                        chapterIndex = target
                        sliderValue = target.toFloat()
                        coroutineScope.launch {
                            loadChapterContent(bookUrl, target) { content ->
                                chapterContent = content
                                readChapters = readChapters + target
                            }
                        }
                    },
                    enabled = hasNext,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ArcoBlue6,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.size(44.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("▶", fontSize = 18.sp)
                }
            }
        }

        // 章节目录抽屉 (对齐 Reader.ets Stack drawerVisible, 70% 面板 + 30% 遮罩)
        if (drawerVisible) {
            Row(Modifier.fillMaxSize()) {
                // 抽屉面板: 标题 + 章节列表
                Column(
                    Modifier
                        .fillMaxHeight()
                        .weight(0.7f)
                        .background(Color.White),
                ) {
                    Text(
                        "章节目录",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .padding(top = 4.dp),
                    )
                    if (chapterList.isEmpty()) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("暂无章节", color = TextSecondary, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(
                                chapterList,
                                key = { "${it.index}_${it.url}" },
                            ) { ch ->
                                val isSelected = ch.index == chapterIndex
                                val isRead = ch.index in readChapters
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (isSelected) AppBackground else Color.White)
                                        .clickable {
                                            chapterIndex = ch.index
                                            sliderValue = ch.index.toFloat()
                                            drawerVisible = false
                                            coroutineScope.launch {
                                                loadChapterContent(bookUrl, ch.index) { content ->
                                                    chapterContent = content
                                                    readChapters = readChapters + ch.index
                                                }
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    // 已读标记 (● 8sp primary, 10dp 宽, 未读留空对齐)
                                    Text(
                                        if (isRead) "●" else "",
                                        color = ArcoBlue6,
                                        fontSize = 8.sp,
                                        modifier = Modifier.width(10.dp),
                                    )
                                    Text(
                                        ch.title,
                                        color = if (isSelected) ArcoBlue6 else TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
                // 右侧遮罩 (30% 宽, 半透明黑, 点击关闭)
                Box(
                    Modifier
                        .fillMaxHeight()
                        .weight(0.3f)
                        .background(Color(0x80000000))
                        .clickable { drawerVisible = false },
                )
            }
        }
    }
}

/**
 * 加载章节正文 (对齐 Reader.ets loadChapter)。
 *
 * 内部 runBlocking 转 suspend DAO, 缓存未命中/越界/异常时显示占位提示。
 * 抽出为顶层 suspend 函数避免 Composable 内 runBlocking 卡主线程。
 */
private suspend fun loadChapterContent(
    bookUrl: String,
    chapterIndex: Int,
    setContent: (String) -> Unit,
) {
    val content = runCatching {
        val book = AppDbProviders.get().bookDao.getBook(bookUrl) ?: run {
            setContent("未选择书籍, 无法加载章节。")
            return
        }
        val list = AppDbProviders.get().bookChapterDao.getChapterList(bookUrl)
        val chapter = list.getOrNull(chapterIndex) ?: run {
            setContent("章节越界 (已到末章)。")
            return
        }
        BookStorageProviders.get().getContent(book, chapter).orEmpty().ifBlank {
            "暂无章节内容 (缓存未命中或已到末章)。"
        }
    }.getOrElse { e ->
        "加载章节失败: ${e.message ?: e}"
    }
    setContent(content)
}
