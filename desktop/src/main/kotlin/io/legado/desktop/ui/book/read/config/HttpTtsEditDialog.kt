package io.legado.desktop.ui.book.read.config

import androidx.compose.foundation.layout.widthIn
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.desktop.ui.component.DialogSizes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.read.config.HttpTtsEditViewModelShared
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.browseUrl
import io.legado.app.utils.toJson
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.launch

/**
 * HTTP TTS 引擎编辑对话框 (桌面端 Composable)。
 *
 * 对照 app 端 `io.legado.app.ui.book.read.config.HttpTtsEditDialog` (BaseComposeDialogFragment),
 * 复用已下沉的 [HttpTtsEditViewModelShared] (commonMain 共享核心) 处理 save / importFromClip /
 * importSource 业务, UI 用桌面 Compose Dialog 形式 (与 sharedUiMain 的 SpeakEngineDialog /
 * TxtTocRuleEditDialog 风格对齐)。
 *
 * # 与 app 端的差异 (KMP 限制)
 *
 * - **生命周期**: app 端用 Fragment + viewModels<HttpTtsEditViewModel>(); 桌面端用
 *   [rememberCoroutineScope] + remember 持有 [HttpTtsEditViewModelShared], 随 Compose
 *   composition 生命周期自动取消 (与 desktop ReplaceEditScreen 模式对齐);
 * - **数据初始化**: app 端 `viewModel.initData(arguments) { initView(it) }` 从 Bundle 取 id
 *   后走 DAO 加载已有规则, 桌面端由 SpeakEngineDialog 直接传入完整 [httpTTS] 实体 (调用方
 *   已从 flowAll 列表持有), 不需要再走 DAO 加载; 仅调 [HttpTtsEditViewModelShared.initData]
 *   同步 viewModel.id 用于 [dataFromView] 区分新增/编辑 (与 app 端 viewModel.id 用途一致);
 * - **剪贴板**: app 端 `getClipText()` 不能下沉, 桌面端用 AWT Toolkit 实现 clipTextProvider;
 * - **TTS 引擎刷新**: app 端 `onTtsChanged` lambda 内判断 `ReadAloud.ttsEngine == saved.id.toString()`
 *   后调 `ReadAloud.upReadAloudClass()`, 桌面端 ReadAloud 未下沉, onTtsChanged 留空 (TODO);
 * - **登录测试**: app 端 OverflowMenu 的 login 项调 `httpTts.showLoginDialog(activity)`, 桌面端
 *   showLoginDialog 未下沉, 改名为"测试连接"按钮并简化为 toast 提示 (TODO, 等桌面端网络栈接入);
 * - **登录 Header 显示/删除**: app 端 alert 弹窗 + getLoginHeader/removeLoginHeader 依赖
 *   SourceLoginDialog (未下沉), 桌面端暂不实现;
 * - **删除按钮**: app 端在 SpeakEngineDialog 列表中删除, HttpTtsEditDialog 自身无删除按钮;
 *   桌面端在 EditDialog OverflowMenu 中新增"删除"项, 由调用方传入 [onDelete] 回调处理,
 *   仅当 [httpTTS] 非空 (编辑场景) 时显示 (新增场景不显示)。
 *
 * # 字段 (与 app 端 EditEntity 列表完全一致, 共 8 个)
 *
 * - 名称 (name): 简单文本
 * - URL (url): 多行 (app 端原为 CodeView, 桌面端简化为多行 TextField)
 * - Content-Type (contentType): 简单文本
 * - 并发率 (concurrentRate): 简单文本
 * - 登录 URL (loginUrl): 多行 (原 CodeView)
 * - 登录界面 (loginUi): 多行 (原 CodeView)
 * - 登录检测 JS (loginCheckJs): 多行 (原 CodeView)
 * - 请求头 (header): 多行 (原 CodeView)
 *
 * jsLib / enabledCookieJar / enableDangerousApi / lastUpdateTime 等 UI 未编辑字段
 * 通过 [HttpTTS.copy] 保留 (避免编辑丢失), 与 app 端 `new HttpTTS(id=...)` 直接覆盖的
 * 行为略有差异 (桌面端改进: 保留原实体未编辑字段)。
 *
 * # 设计参考
 *
 * - desktop [io.legado.desktop.ui.replace.ReplaceEditScreen]: VM 生命周期 + 剪贴板桥接模式;
 * - shared/sharedUiMain `TxtTocRuleEditDialog`: Dialog + Surface + 标题栏 + 表单布局;
 * - shared/sharedUiMain `SpeakEngineDialog`: DesignTokens.dialogShape + DialogProperties + 标题栏 actions.
 *
 * @param httpTTS 待编辑的 HttpTTS (null=新增, 非空=编辑已有引擎); 由 SpeakEngineDialog 的
 *   onEditEngines 回调传入 (null 对应"+"按钮新增, 非空对应行"编辑"按钮)
 * @param onDismiss 关闭回调 (用户取消 / 保存成功 / 删除成功后触发)
 * @param onImportLocal 本地导入回调 (null=不显示菜单项, 非空=在 OverflowMenu 显示"本地导入"项);
 *   点击时先关闭 EditDialog 再触发回调, 由调用方 (ReadAloudConfigScreen) 弹 FileDialog 选 JSON
 *   → 新建 DesktopImportVm.httpTts → 渲染 DesktopImportDialog 完成比对+入库 (与 app 端
 *   SpeakEngineDialog OverflowMenu 的 import_local 项流程等价, 因桌面端 SpeakEngineDialog 在
 *   shared 中 actions 仅"+"按钮不可改, 导入入口下放到本 EditDialog)
 * @param onImportOnline 网络导入回调 (null=不显示菜单项, 非空=在 OverflowMenu 显示"网络导入"项);
 *   点击时先关闭 EditDialog 再触发回调, 由调用方弹 AlertDialog 输入 URL → 新建适配器
 *   → 渲染 DesktopImportDialog 触发下载+解析+比对+入库
 */
@Composable
fun HttpTtsEditDialog(
    httpTTS: HttpTTS?,
    onDismiss: () -> Unit,
    onImportLocal: (() -> Unit)? = null,
    onImportOnline: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    // rememberCoroutineScope: Compose 桌面端提供, 绑定 composition 生命周期,
    // onDispose 时自动取消 (与 desktop ReplaceEditScreen 模式对齐)
    val scope = rememberCoroutineScope()

    // 所有字符串一次性在 @Composable 主体内 rememberString, 避免 onClick 中误用 @Composable
    val titleText = rememberString("edit")
    val saveDescText = rememberString("action_save")
    val nameLabelText = rememberString("dict_rule_edit_name")
    val concurrentRateLabelText = rememberString("concurrent_rate")
    val loginUrlLabelText = rememberString("source_login_url")
    val loginUiLabelText = rememberString("source_login_ui")
    val loginCheckJsLabelText = rememberString("login_check_js")
    val headerLabelText = rememberString("source_http_header")
    val testConnectionText = rememberString("test_connection")
    val copySourceText = rememberString("copy_source")
    val pasteSourceText = rememberString("paste_source")
    // 导入入口文案 (onImportLocal/onImportOnline 非 null 时在 OverflowMenu 显示, 与 app 端
    // SpeakEngineDialog OverflowMenu 的 import_local/import_on_line 项对齐)
    val importLocalText = rememberString("import_local")
    val importOnlineText = rememberString("import_on_line")
    val deleteText = rememberString("delete")
    val helpText = rememberString("help")
    val copiedText = rememberString("content_edit_copy_success")

    // 复用 shared 核心 VM (注入 scope + AWT Clipboard clipTextProvider + onTtsChanged no-op)
    // key by httpTTS: 切换实例时重建 VM, 重置内部 id 状态 (HttpTtsEditViewModelShared.id 为 private set,
    // 外部无法重置; 重建确保新增场景不会沿用上次编辑的 id 导致覆盖误删/覆盖)
    val viewModel = remember(httpTTS) {
        HttpTtsEditViewModelShared(
            scope = scope,
            clipTextProvider = {
                // 读剪贴板文本 (替代 app 端 getClipText), desktop AWT Clipboard 可跨线程访问
                runCatching {
                    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
                }.getOrNull()
            },
            onTtsChanged = {
                // TODO: 桌面端 ReadAloud 未下沉, TTS 引擎刷新由桌面端播放器自行处理
            },
        )
    }

    // 同步 viewModel.id (供 dataFromView 区分新增/编辑, 与 app 端 viewModel.id 用途一致)
    // httpTTS?.id 非空且非 0 时为编辑场景, 否则为新增 (viewModel.id 保持 null, save 时用 currentTimeMillis)
    LaunchedEffect(httpTTS) {
        val editId = httpTTS?.id?.takeIf { it != 0L }
        viewModel.initData(id = editId) { /* 回填由下面的 remember(httpTTS) 完成, 无需操作 */ }
    }

    // 表单状态 (按 httpTTS 实例 keyed, 切换实例时重新初始化, 与 app 端 initView(httpTTS) 对齐)
    var name by remember(httpTTS) { mutableStateOf(httpTTS?.name.orEmpty()) }
    var url by remember(httpTTS) { mutableStateOf(httpTTS?.url.orEmpty()) }
    var contentType by remember(httpTTS) { mutableStateOf(httpTTS?.contentType.orEmpty()) }
    var concurrentRate by remember(httpTTS) { mutableStateOf(httpTTS?.concurrentRate.orEmpty()) }
    var loginUrl by remember(httpTTS) { mutableStateOf(httpTTS?.loginUrl.orEmpty()) }
    var loginUi by remember(httpTTS) { mutableStateOf(httpTTS?.loginUi.orEmpty()) }
    var loginCheckJs by remember(httpTTS) { mutableStateOf(httpTTS?.loginCheckJs.orEmpty()) }
    var header by remember(httpTTS) { mutableStateOf(httpTTS?.header.orEmpty()) }

    /**
     * 组装当前输入为 HttpTTS, 与 app 端 dataFromView 行为对齐, 并改进保留原实体未编辑字段
     * (jsLib / enabledCookieJar / enableDangerousApi / lastUpdateTime), 避免编辑丢失。
     *
     * - 编辑场景 (httpTTS 非空): 用 [HttpTTS.copy] 保留原实体字段, 仅覆盖 UI 编辑字段;
     * - 新增场景 (httpTTS 为空): new 一个 HttpTTS (id 用 viewModel.id ?: System.currentTimeMillis());
     * - 文本字段 trim 后空串转 null (与 app 端 EditEntity.text 行为一致);
     * - concurrentRate 保留 "0" 兜底 (与 HttpTTS 实体 defaultValue = "0" 对齐)。
     */
    fun dataFromView(): HttpTTS {
        val base = httpTTS ?: HttpTTS(id = viewModel.id ?: System.currentTimeMillis())
        return base.copy(
            name = name,
            url = url,
            contentType = contentType.takeIf { it.isNotBlank() },
            concurrentRate = concurrentRate.takeIf { it.isNotBlank() } ?: "0",
            loginUrl = loginUrl.takeIf { it.isNotBlank() },
            loginUi = loginUi.takeIf { it.isNotBlank() },
            loginCheckJs = loginCheckJs.takeIf { it.isNotBlank() },
            header = header.takeIf { it.isNotBlank() },
        )
    }

    /** 保存并关闭 (与 app 端 IconButton save 对齐, 加 toast 提示 + onDismiss)。 */
    fun saveAndDismiss() {
        val data = dataFromView()
        viewModel.save(data) {
            Toasters.get().toast(jvmGetString("save_success"))
            onDismiss()
        }
    }

    /** 删除当前引擎并关闭 (app 端 HttpTtsEditDialog 无此按钮, 桌面端按任务要求新增)。 */
    fun deleteAndDismiss() {
        val data = dataFromView()
        scope.launch {
            runCatching {
                AppDbProviders.get().httpTTSDao.delete(data)
            }.onSuccess {
                Toasters.get().toast(jvmGetString("delete_success"))
                onDismiss()
            }.onFailure {
                AppLog.put(jvmGetString("delete_http_tts_failed"), it)
            }
        }
    }

    /** 复制源 JSON 到剪贴板 (与 app 端 sendToClip + GSON.toJson 对齐, 加 toast 提示)。 */
    fun copySource() {
        val data = dataFromView()
        runCatching {
            val json = GSON.toJson(data)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(json), null)
            Toasters.get().toast(copiedText)
        }.onFailure { AppLog.put(jvmGetString("copy_http_tts_failed"), it) }
    }

    /** 粘贴源 (委托 viewModel.importFromClip, 内部走 clipTextProvider 取文本 + KS_JSON 解析)。 */
    fun pasteSource() {
        viewModel.importFromClip { pasted ->
            // 与 app 端 initView(httpTTS) 一致: 把粘贴的字段写回输入框
            name = pasted.name
            url = pasted.url
            contentType = pasted.contentType.orEmpty()
            concurrentRate = pasted.concurrentRate.orEmpty()
            loginUrl = pasted.loginUrl.orEmpty()
            loginUi = pasted.loginUi.orEmpty()
            loginCheckJs = pasted.loginCheckJs.orEmpty()
            header = pasted.header.orEmpty()
        }
    }

    /**
     * 测试连接 (替代 app 端 OverflowMenu 的 login 项)。
     *
     * 桌面端 showLoginDialog 未下沉, 简化为 toast 提示 TODO,
     * 等桌面端网络栈 + 登录流程接入后补全实际测试逻辑。
     */
    fun testConnection() {
        Toasters.get().toast(jvmGetString("desktop_test_not_implemented"))
    }

    /** 打开 HTTP TTS 帮助页 (对应 app 端 showHelp("httpTTSHelp"))。 */
    fun openHelp() {
        // app 端 httpTTSHelp 对应 README 中的 HttpTTS 章节, 这里指向项目 wiki
        browseUrl("https://github.com/gedoor/legado/wiki")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.background,
            modifier = Modifier.fillMaxWidth().widthIn(max = DialogSizes.dialogMaxWidth()).padding(16.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = titleText,
                    onBack = onDismiss,
                    actions = {
                        // 标题栏保存按钮 (与 app 端 IconButton + ic_save 对齐)
                        IconButton(onClick = { saveAndDismiss() }) {
                            Icon(
                                painter = rememberPainter("ic_save"),
                                contentDescription = saveDescText,
                                tint = colors.primaryText,
                            )
                        }
                        OverflowMenu { dismissMenu ->
                            DropdownMenuItem(
                                onClick = { dismissMenu(); testConnection() },
                            ) {
                                Text(testConnectionText, color = colors.primaryText)
                            }
                            DropdownMenuItem(
                                onClick = { dismissMenu(); copySource() },
                            ) {
                                Text(copySourceText, color = colors.primaryText)
                            }
                            DropdownMenuItem(
                                onClick = { dismissMenu(); pasteSource() },
                            ) {
                                Text(pasteSourceText, color = colors.primaryText)
                            }
                            // 导入入口 (onImportLocal/onImportOnline 非 null 时显示)
                            // 因桌面端 SpeakEngineDialog 在 shared 中 actions 仅"+"按钮不可改,
                            // 导入入口下放到本 EditDialog; 点击时先 dismiss EditDialog 再触发回调,
                            // 避免 EditDialog 与 DesktopImportDialog 叠加 (与 app 端 SpeakEngineDialog
                            // OverflowMenu 的 import_local/import_on_line 项流程等价)
                            if (onImportLocal != null) {
                                DropdownMenuItem(
                                    onClick = { dismissMenu(); onDismiss(); onImportLocal() },
                                ) {
                                    Text(importLocalText, color = colors.primaryText)
                                }
                            }
                            if (onImportOnline != null) {
                                DropdownMenuItem(
                                    onClick = { dismissMenu(); onDismiss(); onImportOnline() },
                                ) {
                                    Text(importOnlineText, color = colors.primaryText)
                                }
                            }
                            // 仅编辑已有引擎时显示删除按钮 (新增场景不显示)
                            if (httpTTS != null) {
                                DropdownMenuItem(
                                    onClick = { dismissMenu(); deleteAndDismiss() },
                                ) {
                                    Text(deleteText, color = colors.primaryText)
                                }
                            }
                            DropdownMenuItem(
                                onClick = { dismissMenu(); openHelp() },
                            ) {
                                Text(helpText, color = colors.primaryText)
                            }
                        }
                    },
                )
                // 表单字段 (与 app 端 FormEditFields(editEntities) 等价, 8 个字段顺序一致)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    // name: 简单文本字段 (app 端 EditEntity "name")
                    AppOutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = nameLabelText,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.heightIn(min = 8.dp))
                    // url: 多行字段 (app 端 CodeView, 桌面端简化为多行 TextField)
                    AppOutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = "url",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.heightIn(min = 8.dp))
                    // contentType: 短文本字段 (MIME 类型)
                    AppOutlinedTextField(
                        value = contentType,
                        onValueChange = { contentType = it },
                        label = "Content-Type",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.heightIn(min = 8.dp))
                    // concurrentRate: 短文本字段 (限速值)
                    AppOutlinedTextField(
                        value = concurrentRate,
                        onValueChange = { concurrentRate = it },
                        label = concurrentRateLabelText,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.heightIn(min = 8.dp))
                    // loginUrl: 多行字段 (app 端 CodeView)
                    AppOutlinedTextField(
                        value = loginUrl,
                        onValueChange = { loginUrl = it },
                        label = loginUrlLabelText,
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.heightIn(min = 8.dp))
                    // loginUi: 多行字段 (app 端 CodeView + json pattern)
                    AppOutlinedTextField(
                        value = loginUi,
                        onValueChange = { loginUi = it },
                        label = loginUiLabelText,
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.heightIn(min = 8.dp))
                    // loginCheckJs: 多行字段 (app 端 CodeView + js pattern)
                    AppOutlinedTextField(
                        value = loginCheckJs,
                        onValueChange = { loginCheckJs = it },
                        label = loginCheckJsLabelText,
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.heightIn(min = 8.dp))
                    // header: 多行字段 (app 端 CodeView + all pattern)
                    AppOutlinedTextField(
                        value = header,
                        onValueChange = { header = it },
                        label = headerLabelText,
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
