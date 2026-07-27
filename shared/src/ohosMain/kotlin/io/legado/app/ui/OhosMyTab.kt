package io.legado.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 鸿蒙端"我的" Tab Composable (替代 MyTab.ets)。
 *
 * 顶部应用信息 (图标/名称/版本号) + 中部功能入口列表 + 底部 WebDav 同步状态。
 *
 * Material3 等价: 列表项可用 ListItem, 圆形图标可用 IconButton (圆角变体)。
 *
 * 各入口 bridge 跳转未实现, 点击 → onItemClick(title) 由宿主端路由。
 */

private data class MyTabItem(
    val title: String,
    val summary: String,
    val icon: String,
)

// 功能入口列表 (顺序对齐 MyTab.ets items)
private val myTabItems = listOf(
    MyTabItem("书源管理", "管理本地/网络书源", "书"),
    MyTabItem("订阅源管理", "管理 RSS 订阅源", "订"),
    MyTabItem("替换规则", "内容净化与替换", "换"),
    MyTabItem("备份/恢复", "WebDav 与本地备份", "备"),
    MyTabItem("主题设置", "主题色/字号/行距", "主"),
    MyTabItem("其他设置", "阅读/翻页/服务", "其"),
    MyTabItem("阅读记录", "阅读时长统计", "记"),
    MyTabItem("关于", "版本/帮助/源码", "关"),
)

@Composable
fun OhosMyTab(
    onItemClick: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(AppBackground)) {
        // 顶部应用信息 (图标 + 名称 + 版本号, 对齐 MyTab.ets 顶部 Column)
        Column(
            Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 应用图标占位: 72x72, 40sp 白字, ArcoBlue6 底, 16dp 圆角
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(ArcoBlue6),
                contentAlignment = Alignment.Center,
            ) {
                Text("阅", color = Color.White, fontSize = 40.sp, textAlign = TextAlign.Center)
            }
            Text("阅读", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Legado KMP · 鸿蒙 KP8+ 预览", color = TextSecondary, fontSize = 12.sp)
        }

        // 功能入口列表 (对齐 MyTab.ets List)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(myTabItems, key = { it.title }) { item ->
                MyItemRow(item, onClick = { onItemClick(item.title) })
            }
        }

        // 底部 WebDav 同步状态 (对齐 MyTab.ets 底部 Row)
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(Color.White)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("●", color = TextSecondary, fontSize = 12.sp)
            Text("WebDav: 未配置", color = TextSecondary, fontSize = 12.sp)
        }
    }
}

/** 单个功能入口行 (对齐 MyTab.ets ListItem Row)。 */
@Composable
private fun MyItemRow(item: MyTabItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 圆形图标占位: 40x40, 18sp 白字, ArcoBlue6 底, 20dp 圆角 (对齐 MyTab.ets)
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(ArcoBlue6),
            contentAlignment = Alignment.Center,
        ) {
            Text(item.icon, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, color = TextPrimary, fontSize = 16.sp)
            Text(item.summary, color = TextSecondary, fontSize = 12.sp)
        }
        Text(">", color = TextSecondary, fontSize = 18.sp)
    }
}
