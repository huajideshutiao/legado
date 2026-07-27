package io.legado.app.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.toast.Toasters
import io.legado.app.help.ui.OpenUrlProviders

/**
 * 鸿蒙端"关于"页 Screen (对照 iOS `IosAboutScreen`, 复刻 sharedUiMain `AboutScreen` 偏好列表)。
 *
 * 历史上 ohosMain 曾不继承 sharedUiMain, 本 Screen 未复用 AboutScreen/PreferenceScreen 等 sharedUiMain 组件,
 * 用标准 Compose 实现等价偏好列表 UI, 行为与 app/desktop/iOS 端一致。
 *
 * 平台适配:
 * - **外链跳转**: 贡献者 / Telegram / 许可证 / 免责声明 走 [OpenUrlProviders]
 *   (鸿蒙端 [io.legado.app.help.ui.OhosOpenUrlProviderImpl] 桥接 ArkTS startAbility)
 * - **检查更新 / 保存日志 / 堆转储**: 鸿蒙端暂未实现, 回调内 toast 提示
 * - **崩溃日志**: 弹出 [OhosCrashLogsDialog] (读 cacheDir/crash/ 目录)
 * - **更新日志**: 鸿蒙端暂无版本号注入入口, summary 显示空串 (后续 napi 接入后补全)
 *
 * @param onBack 返回回调 (由 OhosNavHost 注入)
 */
@Composable
fun OhosAboutScreen(
    onBack: () -> Unit,
) {
    // 崩溃日志对话框状态
    var showCrashLogDialog by remember { mutableStateOf(false) }
    // 鸿蒙端暂无版本号注入入口, 后续 napi 接入后补全
    val versionSummary = remember { "" }

    Column(modifier = Modifier.fillMaxSize()) {
        OhosAboutTopBar(title = "关于", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            AboutPreference(
                title = "贡献者",
                summary = "gedoor/legado",
                onClick = {
                    OpenUrlProviders.get().openUrl("https://github.com/gedoor/legado/graphs/contributors")
                },
            )
            AboutPreference(
                title = "加入 Telegram 群",
                onClick = {
                    OpenUrlProviders.get().openUrl("https://t.me/legado_cloud")
                },
            )
            AboutPreference(
                title = "更新日志",
                summary = versionSummary,
            )
            AboutPreference(
                title = "检查更新",
                onClick = { Toasters.get().toast("鸿蒙端暂不支持检查更新") },
            )

            AboutCategoryHeader(title = "其他")
            AboutPreference(
                title = "崩溃日志",
                onClick = { showCrashLogDialog = true },
            )
            AboutPreference(
                title = "保存日志",
                onClick = { Toasters.get().toast("鸿蒙端暂不支持保存日志") },
            )
            AboutPreference(
                title = "创建堆转储",
                onClick = { Toasters.get().toast("鸿蒙端不支持堆转储") },
            )
            AboutPreference(
                title = "许可证",
                onClick = {
                    OpenUrlProviders.get().openUrl("https://github.com/gedoor/legado/blob/master/LICENSE")
                },
            )
            AboutPreference(
                title = "免责声明",
                onClick = {
                    OpenUrlProviders.get().openUrl("https://github.com/gedoor/legado/blob/master/disclaimer.md")
                },
            )
        }
    }

    // 崩溃日志对话框
    if (showCrashLogDialog) {
        OhosCrashLogsDialog(onDismiss = { showCrashLogDialog = false })
    }
}

/** 偏好项: 标题 + 可选副标题, 整行可点击。 */
@Composable
private fun AboutPreference(
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    }
    Column(modifier = modifier) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!summary.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 偏好分类标题 (对应 PreferenceFragment 的 preferenceCategory)。 */
@Composable
private fun AboutCategoryHeader(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 鸿蒙端 About 顶栏 (历史遗留自绘实现, 未复用 sharedUiMain AppTitleBar)。
 * 标准 Compose 实现: 返回箭头 + 标题 + 右侧 actions 区。
 */
@Composable
private fun OhosAboutTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ohosMain 未声明 materialIconsExtended, 用 TextButton 替代 Icon(返回箭头)
            TextButton(onClick = onBack) {
                Text("←")
            }
            Text(
                text = title,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
    }
}
