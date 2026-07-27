// I18N KEYS (新增, 待 ResourceProvider.jvm.kt 补全桌面端字面量):
// - speak_engine: "朗读引擎" (已存在 jvmMain)
// - system_tts: "系统 TTS" (已存在 jvmMain, 用作"系统默认"行显示名)
// - system_default: "系统默认"
// - add: "添加" (已存在 jvmMain)
// - edit: "编辑" (已存在 jvmMain)
// - delete: "删除" (已存在 jvmMain)
// - cancel: "取消" (已存在 jvmMain)
//
// PAINTER KEYS (新增, 待 ResourceProvider.jvm.kt 补全桌面端图标):
// - ic_add: 添加 (已存在 jvmMain)
// - ic_edit: 编辑 (已存在 jvmMain)
// - ic_clear_all: 删除 (已存在 jvmMain)

package io.legado.app.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.data.entities.HttpTTS
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/** Arco Design arco_radius_lg = 16dp, 用于对话框圆角。 */
private val ArcoRadiusLg = 16.dp

/**
 * 朗读引擎选择对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.read.config.SpeakEngineDialog`,
 * 但去掉对 Android Fragment / ViewModel / appDb flow / registerHandleFile /
 * showDialogFragment / ReadAloud / ReadBook 的依赖, 改为纯 @Composable + 回调形式:
 * - 调用方传入 [engines] (HTTP TTS 引擎列表, 由调用方负责加载) 与 [selectedEngineUrl]
 *   (当前选中引擎 id 字符串, 系统默认为 null)
 * - 用户点击引擎行通过 [onSelectEngine] 回调 (与原版 `upTts(httpTTS.id.toString())` 对齐)
 * - 用户点击右上角"+"通过 [onEditEngines] 回调 (替代原版 OverflowMenu 中的添加/导入/导出,
 *   由调用方决定具体行为: app 端可弹 HttpTtsEditDialog, 桌面端可弹自定义编辑界面)
 * - 用户点击每行"编辑"按钮也通过 [onEditEngines] 回调 (参数为待编辑的 [HttpTTS], 与原版
 *   `onEdit = { showDialogFragment(HttpTtsEditDialog(httpTTS.id)) }` 对齐);
 *   "+"按钮触发时参数为 null (新增), 行"编辑"按钮触发时参数为对应 [HttpTTS] (编辑)
 * - [onDismiss] 关闭回调 (与原版 dismissAllowingStateLoss 对齐)
 *
 * # 原业务逻辑保留
 *
 * - "系统默认"行 (与原版 `sysDefault` item 对齐): 选中时 [onSelectEngine] 收到 null
 * - HTTP TTS 引擎列表行 (与原版 `items(items.size)` 对齐): 每行显示引擎名 + 编辑/删除按钮
 * - 编辑按钮 (与原版 `onEdit = { showDialogFragment(HttpTtsEditDialog(httpTTS.id)) }` 对齐):
 *   通过 [onEditEngines] 回调, 参数为待编辑的 HttpTTS (与"+"按钮的 null=新增 区分)
 * - 删除按钮 (与原版 `onDelete = { runBlocking { appDb.httpTTSDao.delete(httpTTS) } }` 对齐):
 *   本下沉版本不直接删数据库, 通过 [onDeleteEngine] 回调由调用方处理
 *
 * # 与原版的差异 (KMP 限制)
 *
 * - 桌面端无系统 TTS 引擎列表 (原版 `viewModel.sysEngines` 依赖 Android TextToSpeech 引擎枚举),
 *   本下沉版本仅保留"系统默认"占位行, 不枚举系统 TTS 引擎 (与任务要求"桌面端无系统 TTS 引擎"对齐)
 * - 原版底部"书籍/通用"两个保存按钮依赖 `ReadBook.book?.config` 与 `AppConfig.ttsEngine`,
 *   下沉后由调用方在 [onDismiss] / [onSelectEngine] 中决定保存范围
 * - 原版 OverflowMenu 中的"导入默认规则/本地导入/网络导入/导出"依赖 registerHandleFile +
 *   ACache + showDialogFragment(ImportHttpTtsDialog), 下沉后简化为 [onEditEngines] 单入口
 *
 * # 样式 (Arco Design 规范)
 *
 * - 主色 arcoblue-6 (#165DFF): RadioButton 选中色 (走 AppRadioButton 默认 accent)
 * - 圆角 arco_radius_lg = 16dp: Dialog Surface 圆角
 * - 无阴影 (Surface 默认无阴影)
 *
 * @param engines HTTP TTS 引擎列表 (调用方负责从 appDb.httpTTSDao.flowAll() 加载)
 * @param selectedEngineUrl 当前选中引擎 id 字符串 (null=系统默认)
 * @param onSelectEngine 用户点击引擎行回调 (参数为引擎 id 字符串, null=系统默认)
 * @param onEditEngines 用户点击右上角"+"或行"编辑"按钮回调 (新增/编辑引擎入口):
 *   - 参数为 null: 新增 (对应"+"按钮)
 *   - 参数非 null: 编辑指定 HttpTTS (对应行"编辑"按钮, 替代原版 showDialogFragment(HttpTtsEditDialog(httpTTS.id)))
 * @param onDeleteEngine 用户点击引擎行删除按钮回调 (参数为待删除的 HttpTTS)
 * @param onDismiss 关闭回调
 */
@Composable
fun SpeakEngineDialog(
    engines: List<HttpTTS>,
    selectedEngineUrl: String?,
    onSelectEngine: (String?) -> Unit,
    onEditEngines: (HttpTTS?) -> Unit,
    onDeleteEngine: (HttpTTS) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    // 当前选中引擎 id 字符串 (本地状态, 用于即时更新 RadioButton 选中态)
    var selected by remember(selectedEngineUrl) { mutableStateOf(selectedEngineUrl) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(ArcoRadiusLg),
            color = colors.background,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = rememberString("speak_engine"),
                    onBack = onDismiss,
                    actions = {
                        // 右上角"+"按钮 (与原版 IconButton + ic_add 对齐)
                        // 参数 null: 表示新增 (与行"编辑"按钮的非 null 参数区分)
                        IconButton(onClick = { onEditEngines(null) }) {
                            Icon(
                                painter = rememberPainter("ic_add"),
                                contentDescription = rememberString("add"),
                                tint = colors.primaryText,
                            )
                        }
                    },
                )

                // 引擎列表 (与原版 LazyColumn + sysDefault item + items 对齐)
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 400.dp),
                ) {
                    // 系统默认行 (与原版 sysDefault item 对齐)
                    // 桌面端无系统 TTS 引擎列表, 仅保留"系统默认"占位行
                    item(key = "sysDefault") {
                        EngineRow(
                            name = rememberString("system_default"),
                            checked = selected == null,
                            onSelect = {
                                selected = null
                                onSelectEngine(null)
                            },
                        )
                    }
                    // HTTP TTS 引擎列表 (与原版 items(items.size, key = { items[it].id }) 对齐)
                    items(engines, key = { it.id }) { httpTTS ->
                        EngineRow(
                            name = httpTTS.name,
                            checked = httpTTS.id.toString() == selected,
                            onEdit = { onEditEngines(httpTTS) },
                            onDelete = { onDeleteEngine(httpTTS) },
                            onSelect = {
                                selected = httpTTS.id.toString()
                                onSelectEngine(httpTTS.id.toString())
                            },
                        )
                    }
                }

                // 底部按钮栏 (与原版 Row + 取消 对齐; 简化, 不含"书籍/通用"两个保存范围按钮)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppTextButton(
                        text = rememberString("cancel"),
                        color = colors.secondaryText,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

/**
 * 引擎行: 单选钮 + 名称, HttpTTS 项带编辑/删除按钮。
 *
 * 复刻自 app 端 `SpeakEngineDialog.EngineRow`:
 * - 48dp 高度 + 单选钮 + 名称 (weight 1f, maxLines=1)
 * - onEdit 非空时显示编辑按钮 (ic_edit)
 * - onDelete 非空时显示删除按钮 (ic_clear_all)
 * - 整行点击触发 onSelect
 */
@Composable
private fun EngineRow(
    name: String,
    checked: Boolean,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onSelect: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppRadioButton(selected = checked, onClick = onSelect)
        Text(
            text = name,
            color = colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                Icon(
                    painter = rememberPainter("ic_edit"),
                    contentDescription = rememberString("edit"),
                    tint = colors.primaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    painter = rememberPainter("ic_clear_all"),
                    contentDescription = rememberString("delete"),
                    tint = colors.primaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
