package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.rememberStringArray
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.TransType

/**
 * 跨平台"简繁转换"选择器对话框（对照 app 端 `ChineseUtils.showConverterSelector`）。
 *
 * # 职责
 *
 * - 弹选项列表（关闭 / 繁体转简体 / 简体转繁体），单选 + dismiss
 * - 选中后写入 [AppConfigProviders.get().chineseConverterType]（对齐 app 端
 *   `AppConfig.chineseConverterType = i`）
 * - 若 i > 0 调用 [ChineseUtils.loadDict] 预加载对应词典（对齐 app 端 showConverterSelector
 *   内部 loadDict 分支；iOS/鸿蒙端 ChineseUtils.loadDict 为 no-op，字典已内嵌）
 * - 通过 [onChanged] 回调通知调用方更新 UI state + postConfig（对齐 app 端
 *   ReadStyleDialog 的 onChanged 回调：`chineseType = it; unLoad(...); postConfig(LOAD_CONTENT)`）
 *
 * # 资源 fallback
 *
 * `chinese_mode` / `chinese_converter` 未在 jvmMain/iosMain 的资源表中定义时，
 * rememberStringArray 返回空 List、rememberString 返回 key 本身。本 Composable 做了
 * fallback：空列表回退到 values-zh/arrays.xml 的中文默认值，key 回退到 values-zh/strings.xml
 * 的中文翻译，确保 iOS/桌面端功能可用（与 app 端行为一致）。
 *
 * @param currentType 当前简繁转换类型（0=关闭, 1=繁转简, 2=简转繁）
 * @param onChanged 选中回调（参数为新的类型值；调用方负责更新 UI state + postConfig）
 * @param onDismiss 关闭回调
 */
@Composable
fun ChineseConverterSelectorDialog(
    currentType: Int,
    onChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 与 app 端 values-zh/arrays.xml chinese_mode 对齐
    val modes = rememberStringArray("chinese_mode").ifEmpty {
        listOf("关闭", "繁体转简体", "简体转繁体")
    }
    // 与 app 端 values-zh/strings.xml chinese_converter 对齐
    val title = rememberString("chinese_converter").let {
        if (it == "chinese_converter") "繁简转换" else it
    }
    AppSelectorDialog(
        onDismissRequest = onDismiss,
        title = title,
        items = modes,
        onItemSelected = { i ->
            if (i != currentType) {
                AppConfigProviders.get().chineseConverterType = i
                if (i > 0) {
                    when (i) {
                        1 -> ChineseUtils.loadDict(TransType.TRADITIONAL_TO_SIMPLE)
                        2 -> ChineseUtils.loadDict(TransType.SIMPLE_TO_TRADITIONAL)
                    }
                }
                onChanged(i)
            }
            onDismiss()
        },
    )
}
