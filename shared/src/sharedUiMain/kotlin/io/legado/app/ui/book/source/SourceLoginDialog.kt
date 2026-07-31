package io.legado.app.ui.book.source

// I18N KEYS (已注册于 ResourceProvider.jvm.kt):
//   "login_source" / "ok" / "show_login_header" / "del_login_header" / "log" /
//   "login_header" / "copy" / "success"

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.rule.FlexChildStyle
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.help.coroutine.printStackTraceOnDebug
import io.legado.app.help.toast.Toasters
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppFilletTextButton
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.GridPackLayout
import io.legado.app.ui.compose.component.toGridPackSpec
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.FlowBus
import io.legado.app.utils.GSON
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.copy
import legado.shared.generated.resources.del_login_header
import legado.shared.generated.resources.ic_arrow_drop_down
import legado.shared.generated.resources.ic_check
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.log
import legado.shared.generated.resources.login_header
import legado.shared.generated.resources.login_source
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.show_login_header
import legado.shared.generated.resources.success
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 书源登录对话框 (KMP 共享, app + desktop + iOS 复用)。
 *
 * 严格复刻 app 端 `io.legado.app.ui.login.SourceLoginDialog` 的 UI 结构与交互逻辑:
 * - 顶部 DialogTitleBar: 登录标题 + 确认按钮(ic_check) + 溢出菜单(查看/删除登录请求头 + 日志)
 * - GridPackLayout(columnCount=12, rowUnitMinHeight=60dp) 承载 loginUi 各行
 * - 行类型: text/password/select/toggle/button, padding 与原 view 对齐
 * - 确认: loginData 空则 removeLoginInfo + 关闭; 否则 putLoginInfo + 执行 login() JS
 * - 按钮行: action 为 URL 走 [onOpenUrl]; 否则拼 loginJs + buttonFunctionJS 走 evalJS
 *
 * 平台特有行为通过回调注入:
 * - [onOpenUrl]: app 端 openUrl(Intent) / 桌面端 browseUrl(Desktop.browse)
 * - 剪贴板复制: 内部用 LocalClipboardManager (与 shared AppLogDialog 一致)
 * - toast: 内部用 [Toasters.get].toast (shared 抽象)
 * - AppLogDialog: 内部用 shared 版本按需弹出
 * - REFRESH_LOGIN_UI 事件: 内部用 [FlowBus.with] 订阅
 *
 * WebView 相关: 当 source.loginUi() 为空 (URL 登录场景) 时, 由各端
 * [BaseSource.showLoginDialog] 扩展直接打开 WebView, 不进入本对话框 (保留各端 actual)。
 *
 * @param source 待登录书源
 * @param onDismiss 关闭对话框 (返回键 / 外部点击)
 * @param onOpenUrl 按钮行 action 为绝对 URL 时调用, 由各端打开浏览器/WebView
 * @param book JS 上下文 book 绑定 (可空, 对应 app 端 IntentData.book)
 * @param chapter JS 上下文 chapter 绑定 (可空, 对应 app 端 IntentData.chapter)
 */
@Composable
fun SourceLoginDialog(
    source: BaseSource,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    book: BaseBook? = null,
    chapter: BookChapter? = null,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val titleText = stringResource(Res.string.login_source, source.getTag())
    val okText = stringResource(Res.string.ok)
    val showLoginHeaderText = stringResource(Res.string.show_login_header)
    val delLoginHeaderText = stringResource(Res.string.del_login_header)
    val logText = stringResource(Res.string.log)
    val loginHeaderText = stringResource(Res.string.login_header)
    val copyText = stringResource(Res.string.copy)
    val successText = stringResource(Res.string.success)

    // 表单值以 rowUi.name 为键：text/password 存文本，select 存选中项，toggle 存 "true"/"false"
    val loginData = remember { mutableStateMapOf<String, String>() }
    val rows = remember { mutableStateListOf<RowUi>() }
    var loginUi by remember { mutableStateOf<List<RowUi>?>(null) }
    var showOverflow by remember { mutableStateOf(false) }
    var headerToShow by remember { mutableStateOf<String?>(null) }
    var showAppLog by remember { mutableStateOf(false) }

    fun getLoginData(): HashMap<String, String> {
        val data = hashMapOf<String, String>()
        loginUi?.forEach { rowUi ->
            when (rowUi.type) {
                RowUi.Type.text, RowUi.Type.password,
                RowUi.Type.select, RowUi.Type.toggle ->
                    loginData[rowUi.name]?.let { data[rowUi.name] = it }
            }
        }
        return data
    }

    fun rebuild() {
        loginUi = runCatching { source.loginUi() }.getOrNull()
        val info = source.getLoginInfoMap()
        loginData.clear()
        loginUi?.forEach { rowUi ->
            when (rowUi.type) {
                RowUi.Type.text, RowUi.Type.password ->
                    loginData[rowUi.name] = info?.get(rowUi.name) ?: ""

                RowUi.Type.select -> {
                    val chars = rowUi.chars ?: emptyList()
                    val idx = chars.indexOf(info?.get(rowUi.name)).coerceAtLeast(0)
                    loginData[rowUi.name] = chars.getOrElse(idx) { "" }
                }

                RowUi.Type.toggle ->
                    loginData[rowUi.name] = (info?.get(rowUi.name) == "true").toString()
            }
        }
        rows.clear()
        loginUi?.let { rows.addAll(it) }
    }

    LaunchedEffect(Unit) {
        rebuild()
        // 对照 app 端 observeEvent<Boolean>(EventBus.REFRESH_LOGIN_UI) { rebuild() }
        FlowBus.with(EventBus.REFRESH_LOGIN_UI).collect { event ->
            if (event is Boolean) rebuild()
        }
    }

    fun handleButtonClick(rowUi: RowUi) {
        scope.launch(Dispatchers.IO) {
            if (rowUi.action.isAbsUrl()) {
                onOpenUrl(rowUi.action!!)
            } else if (rowUi.action != null) {
                val buttonFunctionJS = rowUi.action!!
                val loginJS = source.getLoginJs() ?: ""
                runCatching {
                    runScriptWithContext {
                        source.evalJS("$loginJS\n$buttonFunctionJS") {
                            put("result", { getLoginData() })
                            put("book", book)
                            put("chapter", chapter)
                        }
                    }
                }.onFailure { e ->
                    ensureActive()
                    AppLog.put("LoginUI Button ${rowUi.name} JavaScript error", e, true)
                }
            }
        }
    }

    fun login(loginData: HashMap<String, String>) {
        scope.launch(Dispatchers.IO) {
            if (loginData.isEmpty()) {
                source.removeLoginInfo()
                withContext(Dispatchers.Main) { onDismiss() }
            } else if (source.putLoginInfo(GSON.toJson(loginData))) {
                try {
                    runScriptWithContext { source.login() }
                    Toasters.get().toast(successText)
                    withContext(Dispatchers.Main) { onDismiss() }
                } catch (e: Exception) {
                    AppLog.put("登录出错\n${e.localizedMessage}", e)
                    Toasters.get().toast("登录出错\n${e.localizedMessage}")
                    e.printStackTraceOnDebug()
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        DialogTitleBar(
            title = titleText,
            onBack = onDismiss
        ) {
            IconButton(onClick = { login(getLoginData()) }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_check),
                    contentDescription = okText,
                    tint = colors.primaryText
                )
            }
            Box {
                IconButton(onClick = { showOverflow = true }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_more_vert),
                        contentDescription = null,
                        tint = colors.primaryText
                    )
                }
                AppDropdownMenu(
                    expanded = showOverflow,
                    onDismissRequest = { showOverflow = false }
                ) {
                    DropdownMenuItem(
                        onClick = {
                            showOverflow = false
                            source.getLoginHeader()?.let { headerToShow = it }
                                ?: Toasters.get().toast("没有请求头！")
                        }
                    ) {
                        Text(showLoginHeaderText)
                    }
                    DropdownMenuItem(
                        onClick = {
                            showOverflow = false
                            source.removeLoginHeader()
                        }
                    ) {
                        Text(delLoginHeaderText)
                    }
                    DropdownMenuItem(
                        onClick = {
                            showOverflow = false
                            showAppLog = true
                        }
                    ) {
                        Text(logText)
                    }
                }
            }
        }
        LoginForm(rows, loginData) { handleButtonClick(it) }
    }

    // 登录请求头展示 + 复制按钮 (复刻 app 端 alert DSL)
    headerToShow?.let { loginHeader ->
        AppAlertDialog(
            onDismissRequest = { headerToShow = null },
            title = loginHeaderText,
            message = loginHeader,
            okButton = io.legado.app.ui.compose.component.AlertButton(
                text = copyText,
                onClick = { clipboard.setText(AnnotatedString(loginHeader)) }
            ),
        )
    }

    // 日志对话框 (复刻 app 端 showDialogFragment<AppLogDialog>())
    if (showAppLog) {
        AppLogDialog(onDismiss = { showAppLog = false })
    }
}

@Composable
private fun LoginForm(
    rows: List<RowUi>,
    loginData: MutableMap<String, String>,
    onButtonClick: (RowUi) -> Unit,
) {
    // 占格打包复刻原 GridLayout(columnCount=12) 先到先占格：rows>1 纵跨项占住的列，
    // 后续项在下一行绕开填空，而非流式换行挤到更下方
    val specs = rows.map { rowUi ->
        val defaultStyle =
            if (rowUi.type == RowUi.Type.text || rowUi.type == RowUi.Type.password) {
                FlexChildStyle(cols = 1)
            } else {
                FlexChildStyle.defaultStyle2
            }
        rowUi.style(defaultStyle).toGridPackSpec()
    }
    GridPackLayout(
        specs = specs,
        rowUnitMinHeight = 60.dp, // 原 view.minimumHeight = 60dp × rows
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        rows.forEach { rowUi ->
            // 原 GridLayout 单元格 0 margin，间距由各行自身 padding/按钮背景 inset 提供
            LoginRow(rowUi, Modifier, loginData, onButtonClick)
        }
    }
}

@Composable
private fun LoginRow(
    rowUi: RowUi,
    modifier: Modifier,
    loginData: MutableMap<String, String>,
    onButtonClick: (RowUi) -> Unit,
) {
    val colors = AppTheme.colors
    when (rowUi.type) {
        // 原 createSourceEditView: setPadding(0, xs, 0, 0)；文本框另加左右下各 4dp 输入边距
        RowUi.Type.text -> AppOutlinedTextField(
            value = loginData[rowUi.name] ?: "",
            onValueChange = { loginData[rowUi.name] = it },
            label = rowUi.name,
            singleLine = false,
            modifier = modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
        )

        RowUi.Type.password -> AppOutlinedTextField(
            value = loginData[rowUi.name] ?: "",
            onValueChange = { loginData[rowUi.name] = it },
            label = rowUi.name,
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = modifier.padding(top = 4.dp)
        )

        // 原 select/toggle 行: setPadding(space.default)=8dp
        RowUi.Type.select -> SelectRow(rowUi, modifier.padding(8.dp), loginData, onButtonClick)

        RowUi.Type.toggle -> Row(
            modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(rowUi.name, color = colors.primaryText, modifier = Modifier.weight(1f))
            AppSwitch(
                checked = loginData[rowUi.name] == "true",
                onCheckedChange = {
                    loginData[rowUi.name] = it.toString()
                    onButtonClick(rowUi)
                }
            )
        }

        // 原 button 行: item_fillet_text + setPadding(space.lg)=16dp 四向
        else -> AppFilletTextButton(
            text = rowUi.name,
            modifier = modifier,
            contentPadding = PaddingValues(16.dp),
            onClick = { onButtonClick(rowUi) },
        )
    }
}

@Composable
private fun SelectRow(
    rowUi: RowUi,
    modifier: Modifier,
    loginData: MutableMap<String, String>,
    onButtonClick: (RowUi) -> Unit,
) {
    val colors = AppTheme.colors
    val chars = rowUi.chars ?: emptyList()
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(rowUi.name, color = colors.primaryText, modifier = Modifier.padding(end = 8.dp))
        // 自定义实现替代 MD3 ExposedDropdownMenuBox: Box + TextField + AppDropdownMenu
        Box(modifier = Modifier.weight(1f)) {
            AppOutlinedTextField(
                value = loginData[rowUi.name] ?: "",
                onValueChange = {},
                readOnly = true,
                // 点击 TextField 切换菜单展开状态
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                // trailing icon 用 IconButton + ic_arrow_drop_down, 点击切换展开
                trailingIcon = {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_drop_down),
                            contentDescription = null,
                            tint = colors.secondaryText
                        )
                    }
                }
            )
            AppDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                chars.forEach { item ->
                    DropdownMenuItem(onClick = {
                        expanded = false
                        if (loginData[rowUi.name] != item) {
                            loginData[rowUi.name] = item
                            onButtonClick(rowUi)
                        }
                    }) {
                        Text(item)
                    }
                }
            }
        }
    }
}
