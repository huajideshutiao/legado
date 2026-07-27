package io.legado.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
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
import io.legado.app.data.entities.BookSourcePart
import kotlinx.coroutines.flow.first

/**
 * 鸿蒙端发现 Tab Composable (替代 DiscoverTab.ets)。
 *
 * 顶栏 (56dp, ArcoBlue6 底, 白字, 刷新/菜单图标) + 发现源列表 (源名 / URL / 最后更新时间)。
 * 点击 → onOpenExplore 回调; 长按留作扩展 (编辑/置顶/删除, napi 桥接待扩展)。
 *
 * Material3 等价: 顶栏可用 TopAppBar, 列表项可用 ListItem。
 *
 * 数据源: AppDbProviders.get().bookSourceDao.flowExplore().first() (替代 .ets 的 napi
 * legado.exploreList() 桥接, napi C++ 侧未实现该函数, 改直接调 KMP DAO)。
 */
@Composable
fun OhosExploreTab(
    onOpenExplore: (sourceUrl: String) -> Unit,
) {
    var sources by remember { mutableStateOf<List<BookSourcePart>>(emptyList()) }
    // refreshKey 触发重载 (对齐 DiscoverTab.ets onRefresh 重载 sources)
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        sources = runCatching {
            AppDbProviders.get().bookSourceDao.flowExplore().first()
        }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        ExploreTitleBar(onRefresh = { refreshKey++ })
        if (sources.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无发现源", color = TextSecondary, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().background(AppBackground),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sources, key = { it.bookSourceUrl }) { item ->
                    ExploreSourceItem(item, onClick = { onOpenExplore(item.bookSourceUrl) })
                }
            }
        }
    }
}

/** 顶栏: 白字标题 + 刷新/菜单图标 (对齐 DiscoverTab.ets TitleBar)。 */
@Composable
private fun ExploreTitleBar(onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(ArcoBlue6).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "发现",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            "↻",
            color = Color.White,
            fontSize = 22.sp,
            modifier = Modifier
                .padding(end = 16.dp)
                .clickable(onClick = onRefresh),
        )
        Text("⋮", color = Color.White, fontSize = 22.sp)
    }
}

/** 发现源项: 源名 / URL / 最后更新时间 + 右侧 › 箭头 (对齐 DiscoverTab.ets SourceItem)。 */
@Composable
private fun ExploreSourceItem(
    item: BookSourcePart,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                item.bookSourceName,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.bookSourceUrl,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "更新: ${formatTime(item.lastUpdateTime)}",
                color = TextSecondary,
                fontSize = 10.sp,
            )
        }
        Text("›", color = TextSecondary, fontSize = 20.sp)
    }
}

/** 时间戳格式化 (对齐 DiscoverTab.ets formatTime: yyyy-MM-dd, 0 显示未知)。 */
private fun formatTime(millis: Long): String {
    if (millis <= 0) return "未知"
    // KMP 无 java.text.SimpleDateFormat, 用 kotlinx-datetime 跨平台实现 (ohosMain 已声明依赖)
    val instant = Instant.fromEpochMilliseconds(millis)
    val local = instant.toString().substringBefore('T').ifBlank { "未知" }
    return local
}
