package io.legado.app.ui.association

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 鸿蒙端关联导入 Screen 入口 (对照 app 端 `AssociationActivity` + `FileAssociationFragment`)。
 *
 * # 背景
 *
 * app 端 `AssociationActivity` 是透明壳 Activity, UI 由 headless `FileAssociationFragment`
 * 弹出的 ImportXxxDialog 系列对话框承载, 处理外部 Intent (打开文件/分享文本/legado:// deep link)
 * 导入书源/订阅源/替换规则/TXT 目录规则/HttpTTS/字典规则/主题/阅读配置。
 *
 * 鸿蒙端无 Android Intent/Fragment 体系, 本 Screen 作为 `OhosNavHost.ASSOCIATION` 路由入口,
 * 用标准 Compose 列表展示可关联的项目类型, 选择后由调用方接入对应导入流程。
 *
 * # 简化项
 *
 * - 实际导入对话框 (ImportBookSourceDialog 等) 依赖 Android Fragment, 未下沉到 shared,
 *   选择项目暂 toast 提示待接入
 * - 外部 Intent 派发 (打开文件/分享文本) 未接入 (依赖鸿蒙 Want 桥接, 后续 KP);
 *   legado:// deep link 一路已接: EntryAbility onCreate/onNewWant → napi handleDeepLink →
 *   LegadoDeepLinkHandler → OhosNavHost 尾部 [DeepLinkImportHost] 弹勾选对话框, 不走本 Screen
 *
 * @param type 关联类型 ([AssociationType]), 区分书源/订阅源/替换规则等
 * @param onBack 返回回调 (由 OhosNavHost 注入)
 */
@Composable
fun OhosAssociationScreen(
    type: Int,
    onBack: () -> Unit,
) {
    // 待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val notImplementedText = rememberString("ohos_association_not_implemented")
    val items = remember { AssociationType.entries }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("association_import"),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            items.forEach { item ->
                AssociationItemRow(
                    name = item.displayName,
                    selected = item.value == type,
                    onClick = {
                        // TODO: 接入对应 ImportXxxDialog 下沉版本 (后续 KP)
                        Toasters.get().toast(notImplementedText)
                    },
                )
            }
        }
    }
}

/** 关联类型条目行: 类型名 + 选中态背景。 */
@Composable
private fun AssociationItemRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) {
        AppTheme.colors.accent.copy(alpha = 0.08f)
    } else {
        AppTheme.colors.background
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = bg,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                color = AppTheme.colors.primaryText,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "✓",
                    color = AppTheme.colors.accent,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

/**
 * 关联导入类型枚举 (对照 app 端 `FileAssociationFragment.handleOnLineImport` 的 path 分支)。
 *
 * value 与 [OhosNavHost] 的 `associationType` 状态变量一致。
 */
enum class AssociationType(val value: Int, val displayName: String) {
    BOOK_SOURCE(0, "书源"),
    RSS_SOURCE(1, "订阅源"),
    REPLACE_RULE(2, "替换规则"),
    TXT_TOC_RULE(3, "TXT 目录规则"),
    HTTP_TTS(4, "HttpTTS"),
    DICT_RULE(5, "字典规则"),
    THEME(6, "主题"),
    READ_CONFIG(7, "阅读配置");

    companion object {
        fun fromValue(v: Int): AssociationType =
            entries.firstOrNull { it.value == v } ?: BOOK_SOURCE
    }
}
