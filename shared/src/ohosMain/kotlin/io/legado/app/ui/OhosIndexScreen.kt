package io.legado.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.BottomNavTag
import kotlinx.coroutines.launch

/**
 * 鸿蒙端主框架 Composable (替代 ohosApp/entry/.../pages/Index.ets)。
 *
 * 4 个 tab (home/bookshelf/discovery/my) + 自定义底栏 (对齐 app 端 MainScreen + MainBottomBar)。
 * 选中色 ARCO_BLUE_6 (#165DFF), 未选中 ARCO_GRAY_3 (#86909C), 底栏高度 56dp 无阴影。
 *
 * Material3 等价: 自定义 Row 底栏可替换为 NavigationBar (视觉一致)。
 */
@Composable
fun OhosIndexScreen(
    onOpenReader: (bookUrl: String, bookName: String, chapterIndex: Int) -> Unit,
    onOpenExplore: (sourceUrl: String) -> Unit,
    onMyItemClick: (String) -> Unit,
) {
    val visibleTags = listOf(
        BottomNavTag.HOME,
        BottomNavTag.BOOKSHELF,
        BottomNavTag.DISCOVERY,
        BottomNavTag.MY,
    )
    // 默认落点 bookshelf (对齐 Index.ets loadBottomNavConfig initialPage)
    val initialPage = visibleTags.indexOf(BottomNavTag.BOOKSHELF).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage) { visibleTags.size }
    val coroutineScope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            when (visibleTags[page]) {
                BottomNavTag.HOME -> OhosHomePlaceholder()
                BottomNavTag.BOOKSHELF -> OhosBookshelfTab(onOpenReader = onOpenReader)
                BottomNavTag.DISCOVERY -> OhosExploreTab(onOpenExplore = onOpenExplore)
                else -> OhosMyTab(onItemClick = onMyItemClick)
            }
        }
        OhosBottomBar(
            tags = visibleTags,
            selectedIndex = pagerState.currentPage,
            onSelect = { index ->
                coroutineScope.launch { pagerState.animateScrollToPage(index) }
            },
        )
    }
}

/** 自定义底栏 (对齐 Index.ets BottomBar, 56dp 高, 白底无阴影)。 */
@Composable
private fun OhosBottomBar(
    tags: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(ArcoBgWhite),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tags.forEachIndexed { index, tag ->
            val selected = index == selectedIndex
            val color = if (selected) ArcoBlue6 else ArcoGray3
            Column(
                Modifier
                    .height(56.dp)
                    .clickable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = ohosTabIcon(tag), color = color, fontSize = 22.sp)
                Text(
                    text = ohosTabLabel(tag),
                    color = color,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 主页 tab 占位 (后续 KP 接入 HomeTab)。 */
@Composable
private fun OhosHomePlaceholder() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("主页", color = Color.Black, fontSize = 16.sp)
        Text(
            "待实现 (后续 KP 接入 HomeTab)",
            color = ArcoGray3,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// Arco Design 主题色 (对齐 Index.ets ARCO_BLUE_6/ARCO_GRAY_3/ARCO_BG_WHITE)
internal val ArcoBlue6 = Color(0xFF165DFF)
internal val ArcoGray3 = Color(0xFF86909C)
internal val ArcoBgWhite = Color(0xFFFFFFFF)
internal val AppBackground = Color(0xFFF5F5F5)
internal val TextPrimary = Color(0xFF212121)
internal val TextSecondary = Color(0xFF86909C)
internal val TextDanger = Color(0xFFD32F2F)

// tab 元数据 (对齐 Index.ets getItem, 顺序与图标一致)
private fun ohosTabIcon(tag: String): String = when (tag) {
    BottomNavTag.HOME -> "🏠"
    BottomNavTag.BOOKSHELF -> "📚"
    BottomNavTag.DISCOVERY -> "🧭"
    BottomNavTag.MY -> "👤"
    else -> "?"
}

private fun ohosTabLabel(tag: String): String = when (tag) {
    BottomNavTag.HOME -> "主页"
    BottomNavTag.BOOKSHELF -> "书架"
    BottomNavTag.DISCOVERY -> "发现"
    BottomNavTag.MY -> "我的"
    else -> tag
}
