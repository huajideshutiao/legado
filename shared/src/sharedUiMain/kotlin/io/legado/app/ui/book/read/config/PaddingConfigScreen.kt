package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDetailSeekBar
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 边距配置控制器：把 app 端 `ReadBookConfig` 的边距/分隔线字段读写抽象为接口，
 * 由 app 端 thin wrapper 实现并桥接到 `ReadBookConfig`。
 *
 * 字段命名与 app 端 `ReadBookConfig` 完全一致，方便 wrapper 直接转发。
 */
interface PaddingConfigController {
    var showHeaderLine: Boolean
    var showFooterLine: Boolean
    var headerPaddingTop: Int
    var headerPaddingBottom: Int
    var headerPaddingLeft: Int
    var headerPaddingRight: Int
    var paddingTop: Int
    var paddingBottom: Int
    var paddingLeft: Int
    var paddingRight: Int
    var footerPaddingTop: Int
    var footerPaddingBottom: Int
    var footerPaddingLeft: Int
    var footerPaddingRight: Int
}

/**
 * 页眉/正文/页脚边距与分隔线配置对话框正文 Composable。
 *
 * 下沉自 app 端 `PaddingConfigDialog`，原 DialogFragment 宿主由 app 端 thin wrapper 保留：
 * - `BaseComposeDialogFragment.onStart` 中 `clearFlags(FLAG_DIM_BEHIND) + dimAmount=0`（不压暗底层）
 *   保留在 app 端 wrapper
 * - `onDismiss` 中 `ReadBookConfig.save()` 保留在 app 端 wrapper
 * - `ReadBookEvents.postConfig(...)` 通过 [onPostConfig] 回调由 wrapper 桥接
 * - `ReadBookConfig` 读写通过 [controller] 桥接
 *
 * 资源访问替换：
 * - `stringResource(R.string.xxx)` → `rememberString("xxx")`
 *
 * 行为对齐原 PaddingConfigDialog：即时写 [controller] 并 postConfig 刷新渲染，
 * 边距配置走 STYLE（仅样式刷新），正文边距走 CHAPTER_LAYOUT + LOAD_CONTENT（重排）。
 *
 * @param controller 配置读写桥接
 * @param onPostConfig 配置变更通知（对应 `ReadBookEvents.postConfig(changes)`）
 */
@Composable
fun PaddingConfigScreen(
    controller: PaddingConfigController,
    onPostConfig: (List<ReadConfigChange>) -> Unit,
) {
    val styleOnly = listOf(ReadConfigChange.STYLE)
    val layoutAndLoad = listOf(ReadConfigChange.CHAPTER_LAYOUT, ReadConfigChange.LOAD_CONTENT)
    var showHeaderLine by remember { mutableStateOf(controller.showHeaderLine) }
    var showFooterLine by remember { mutableStateOf(controller.showFooterLine) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionTitle(rememberString("header")) {
            LineCheckbox(showHeaderLine) {
                showHeaderLine = it
                controller.showHeaderLine = it
                onPostConfig(listOf(ReadConfigChange.STYLE))
            }
        }
        PaddingSeekBar(rememberString("padding_top"), 100, { controller.headerPaddingTop }, {
            controller.headerPaddingTop = it
        }, styleOnly, onPostConfig)
        PaddingSeekBar(rememberString("padding_bottom"), 100, { controller.headerPaddingBottom }, {
            controller.headerPaddingBottom = it
        }, styleOnly, onPostConfig)
        PaddingSeekBar(rememberString("padding_left"), 100, { controller.headerPaddingLeft }, {
            controller.headerPaddingLeft = it
        }, styleOnly, onPostConfig)
        PaddingSeekBar(rememberString("padding_right"), 100, { controller.headerPaddingRight }, {
            controller.headerPaddingRight = it
        }, styleOnly, onPostConfig)

        SectionTitle(rememberString("main_body"))
        PaddingSeekBar(rememberString("padding_top"), 200, { controller.paddingTop }, {
            controller.paddingTop = it
        }, layoutAndLoad, onPostConfig)
        PaddingSeekBar(rememberString("padding_bottom"), 100, { controller.paddingBottom }, {
            controller.paddingBottom = it
        }, layoutAndLoad, onPostConfig)
        PaddingSeekBar(rememberString("padding_left"), 100, { controller.paddingLeft }, {
            controller.paddingLeft = it
        }, layoutAndLoad, onPostConfig)
        PaddingSeekBar(rememberString("padding_right"), 100, { controller.paddingRight }, {
            controller.paddingRight = it
        }, layoutAndLoad, onPostConfig)

        SectionTitle(rememberString("footer")) {
            LineCheckbox(showFooterLine) {
                showFooterLine = it
                controller.showFooterLine = it
                onPostConfig(listOf(ReadConfigChange.STYLE))
            }
        }
        PaddingSeekBar(rememberString("padding_top"), 100, { controller.footerPaddingTop }, {
            controller.footerPaddingTop = it
        }, styleOnly, onPostConfig)
        PaddingSeekBar(rememberString("padding_bottom"), 100, { controller.footerPaddingBottom }, {
            controller.footerPaddingBottom = it
        }, styleOnly, onPostConfig)
        PaddingSeekBar(rememberString("padding_left"), 100, { controller.footerPaddingLeft }, {
            controller.footerPaddingLeft = it
        }, styleOnly, onPostConfig)
        PaddingSeekBar(rememberString("padding_right"), 100, { controller.footerPaddingRight }, {
            controller.footerPaddingRight = it
        }, styleOnly, onPostConfig)
    }
}

/** 分区标题（accent 18sp，复刻 AccentTextView）+ 可选"显示分隔线"复选框 */
@Composable
private fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = AppTheme.colors.accent, fontSize = 18.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun LineCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Text(rememberString("showLine"), color = AppTheme.colors.primaryText, fontSize = 14.sp)
    AppCheckbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.padding(8.dp),
    )
}

@Composable
private fun PaddingSeekBar(
    title: String,
    max: Int,
    getter: () -> Int,
    setter: (Int) -> Unit,
    changes: List<ReadConfigChange>,
    onPostConfig: (List<ReadConfigChange>) -> Unit,
) {
    var value by remember { mutableIntStateOf(getter()) }
    AppDetailSeekBar(
        title = title,
        value = value,
        max = max,
        onChanged = {
            value = it
            setter(it)
            onPostConfig(changes)
        },
    )
}
