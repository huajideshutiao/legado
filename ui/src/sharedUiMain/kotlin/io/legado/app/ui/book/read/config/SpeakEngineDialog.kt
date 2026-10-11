// I18N KEYS (均已注册于 ui/src/commonMain/composeResources, 取值走 compose resources 运行时表):
//   speak_engine, system_default, add, edit, delete, login, test,
//   import_local, import_on_line, import_default_rule, export
// PAINTER KEYS:
//   ic_add, ic_edit, ic_clear_all (ic_more_vert 由 OverflowMenu 自带)

package io.legado.app.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
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
import io.legado.app.data.entities.HttpTTS
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppDialogSurface
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.add
import legado.ui.generated.resources.delete
import legado.ui.generated.resources.edit
import legado.ui.generated.resources.export
import legado.ui.generated.resources.ic_add
import legado.ui.generated.resources.ic_clear_all
import legado.ui.generated.resources.ic_edit
import legado.ui.generated.resources.import_default_rule
import legado.ui.generated.resources.import_local
import legado.ui.generated.resources.import_on_line
import legado.ui.generated.resources.login
import legado.ui.generated.resources.speak_engine
import legado.ui.generated.resources.system_default
import legado.ui.generated.resources.test
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 朗读引擎选择对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.read.config.SpeakEngineDialog`,
 * 但去掉对 Android Fragment / ViewModel / appDb flow / registerHandleFile /
 * showDialogFragment / ReadAloud / ReadBook 的依赖, 改为纯 @Composable + 回调形式:
 * - 调用方传入 [engines] (HTTP TTS 引擎列表, 由调用方负责加载) 与 [selectedEngineUrl]
 *   (当前选中引擎 id 字符串, 系统默认为 null)
 * - 用户点击引擎行通过 [onSelectEngine] 回调 (与原版 `upTts(httpTTS.id.toString())` 对齐)
 * - 标题栏"+"新增/行"编辑"通过 [onEditEngines] 回调; 标题栏三点菜单提供
 *   本地导入/网络导入/导入默认规则/导出 (对应原版 speak_engine 菜单)
 * - 行内三点菜单提供 登录(有登录入口才显示)/测试, 系统默认行三点仅测试
 * - [onDismiss] 关闭回调 (与原版 dismissAllowingStateLoss 对齐)
 *
 * # 与原版的差异 (KMP 限制 + 2026-10-12 用户拍板)
 *
 * - 桌面端无系统 TTS 引擎列表, 仅保留"系统默认"占位行
 * - 原版底部"书籍/通用/取消"三个按钮不保留: 选中即由调用方持久化, 关闭走标题栏返回/点遮罩
 * - 标题栏三点菜单 + 行级 登录/测试 为新增功能 (原版菜单只有导入导出族)
 *
 * # 样式 (Arco Design 规范)
 *
 * - 公共对话框基类: 0.9 宽 (上限 800dp) + dialogShape 圆角 + fillet 底色,
 *   高度内容自适应 (FastScrollLazyColumn wrapContentHeight, 超出封顶 0.8 锚点高滚动)
 * - 无阴影 (Surface 默认无阴影)
 *
 * @param engines HTTP TTS 引擎列表 (调用方负责从 appDb.httpTTSDao.flowAll() 加载)
 * @param selectedEngineUrl 当前选中引擎 id 字符串 (null=系统默认)
 * @param onSelectEngine 用户点击引擎行回调 (参数为引擎 id 字符串, null=系统默认)
 * @param onEditEngines 新增(null)/编辑(非 null)指定 HttpTTS
 * @param onDeleteEngine 删除指定 HttpTTS (行内删除按钮)
 * @param onLogin 登录指定引擎 (行三点菜单, 该引擎无登录入口时菜单项不显示)
 * @param onTestSpeak 试发声 (行/系统默认行三点菜单; 参数 null=系统默认)
 * @param onImportLocal 本地导入 (标题栏三点菜单)
 * @param onImportOnline 网络导入 (标题栏三点菜单)
 * @param onImportDefault 导入默认规则 (标题栏三点菜单)
 * @param onExport 导出引擎列表 (标题栏三点菜单)
 * @param onDismiss 关闭回调
 */
@Composable
fun SpeakEngineDialog(
    engines: List<HttpTTS>,
    selectedEngineUrl: String?,
    onSelectEngine: (String?) -> Unit,
    onEditEngines: (HttpTTS?) -> Unit,
    onDeleteEngine: (HttpTTS) -> Unit,
    onLogin: (HttpTTS) -> Unit,
    onTestSpeak: (HttpTTS?) -> Unit,
    onImportLocal: () -> Unit,
    onImportOnline: () -> Unit,
    onImportDefault: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 当前选中引擎 id 字符串 (本地状态, 用于即时更新 RadioButton 选中态)
    var selected by remember(selectedEngineUrl) { mutableStateOf(selectedEngineUrl) }
    val colors = AppTheme.colors

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppDialogSurface(
            modifier = Modifier.appDialogSize(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = stringResource(Res.string.speak_engine),
                    onBack = onDismiss,
                    actions = {
                        // 右上角"+"按钮 (与原版 IconButton + ic_add 对齐)
                        // 参数 null: 表示新增 (与行"编辑"按钮的非 null 参数区分)
                        IconButton(onClick = { onEditEngines(null) }) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_add),
                                contentDescription = stringResource(Res.string.add),
                                tint = colors.primaryText,
                            )
                        }
                        // 三点菜单: 导入导出族 (对应原版 speak_engine 菜单的导入导出项)
                        OverflowMenu { dismiss ->
                            @Composable
                            fun item(text: String, onClick: () -> Unit) {
                                DropdownMenuItem(
                                    onClick = { dismiss(); onClick() },
                                ) { Text(text, color = AppTheme.colors.primaryText) }
                            }
                            item(stringResource(Res.string.import_local)) { onImportLocal() }
                            item(stringResource(Res.string.import_on_line)) { onImportOnline() }
                            item(stringResource(Res.string.import_default_rule)) { onImportDefault() }
                            item(stringResource(Res.string.export)) { onExport() }
                        }
                    },
                )

                // 引擎列表 (与原版 LazyColumn + sysDefault item + items 对齐;
                // 内容自适应: 项少随内容收缩, 超出封顶 0.8 锚点高后内部滚动)
                FastScrollLazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    wrapContentHeight = true,
                ) {
                    // 系统默认行 (与原版 sysDefault item 对齐)
                    // 桌面端无系统 TTS 引擎列表, 仅保留"系统默认"占位行
                    item(key = "sysDefault") {
                        EngineRow(
                            name = stringResource(Res.string.system_default),
                            checked = selected == null,
                            onTestSpeak = { onTestSpeak(null) },
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
                            // 对齐书源编辑界面登录项显隐: 无登录入口不显示
                            onLogin = if (httpTTS.hasLogin()) {
                                { onLogin(httpTTS) }
                            } else null,
                            onTestSpeak = { onTestSpeak(httpTTS) },
                            onSelect = {
                                selected = httpTTS.id.toString()
                                onSelectEngine(httpTTS.id.toString())
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 引擎行: 单选钮 + 名称, HttpTTS 项带编辑/删除/三点(登录、测试)按钮。
 *
 * - 48dp 高度 + 单选钮 + 名称 (weight 1f, maxLines=1)
 * - onEdit 非空时显示编辑按钮 (ic_edit)
 * - onDelete 非空时显示删除按钮 (ic_clear_all)
 * - onTestSpeak 非空时显示三点按钮: 登录(onLogin 非空且引擎有登录入口才显示)/测试
 * - 整行点击触发 onSelect
 */
@Composable
private fun EngineRow(
    name: String,
    checked: Boolean,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onLogin: (() -> Unit)? = null,
    onTestSpeak: (() -> Unit)? = null,
    onSelect: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.viewHeightXl)
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
                    painter = painterResource(Res.drawable.ic_edit),
                    contentDescription = stringResource(Res.string.edit),
                    tint = colors.primaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    painter = painterResource(Res.drawable.ic_clear_all),
                    contentDescription = stringResource(Res.string.delete),
                    tint = colors.primaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (onTestSpeak != null) {
            OverflowMenu(modifier = Modifier.size(40.dp)) { dismiss ->
                @Composable
                fun item(text: String, onClick: () -> Unit) {
                    DropdownMenuItem(
                        onClick = { dismiss(); onClick() },
                    ) { Text(text, color = colors.primaryText) }
                }
                if (onLogin != null) {
                    item(stringResource(Res.string.login)) { onLogin() }
                }
                item(stringResource(Res.string.test)) { onTestSpeak() }
            }
        }
    }
}
