package io.legado.desktop.ui.replace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.compose.platform.jvmGetString

import io.legado.app.ui.replace.edit.ReplaceEditViewModelShared
import io.legado.app.utils.GSON
import io.legado.app.utils.browseUrl
import io.legado.app.utils.toJson
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * 桌面端替换规则编辑 Screen 入口 (包装 shared/sharedUiMain 的 [io.legado.app.ui.replace.ReplaceEditScreen])。
 *
 * # 职责
 *
 * 对照 desktop [ReplaceRuleScreen] 模式, 仅做桌面平台适配, 业务展示与表单逻辑全部下沉到
 * shared/sharedUiMain 的 [io.legado.app.ui.replace.ReplaceEditScreen]; VM 改用下沉的
 * [ReplaceEditViewModelShared] (commonMain 共享核心, 替代旧版 commonMain
 * `ReplaceEditViewModel`)。
 *
 * - **VM 生命周期**: `rememberCoroutineScope()` 提供应用作用域, 注入
 *   [ReplaceEditViewModelShared] 的 `scope` 参数; VM 随 Compose composition 生命周期
 *   自动取消 (rememberCoroutineScope 在 onDispose 时取消 scope), 无需手动 onCleared
 *   (旧版 commonMain `ReplaceEditViewModel` 自带 SupervisorJob scope 故需 onCleared);
 * - **数据初始化**: `LaunchedEffect(ruleId) { viewModel.initData(id = ruleId) { } }`
 *   加载规则, `finally` 空回调 (desktop 不需要 finally, 通过 `viewModel.state.collectAsState`
 *   在 sharedUiMain Screen 内订阅触发回填; ruleId > 0 编辑已有, ruleId <= 0 新增);
 * - **剪贴板适配 (clipTextProvider)**: 用 [Toolkit.getDefaultToolkit].systemClipboard
 *   替代 app 端 `getClipText`, 通过 lambda 注入 VM 构造函数 (替代 app 端组合委托的
 *   `{ getClipText() }`); VM 内部 `pasteRule` 调用 clipTextProvider 取文本, desktop 端
 *   `onPasteRule` 回调仅需转发 `success` (无需外部 error 回调, 错误由 Shared 内部
 *   `Toasters.get().toast(...)` 处理);
 * - **复制规则 (onCopyRule)**: 序列化 ReplaceRule 为 JSON 写入系统剪贴板
 *   (替代 app 端 sendToClip);
 * - **帮助页**: 用 [Desktop.browse] 打开浏览器跳转正则教程 (对应 app 端 showHelp("regexHelp"));
 * - **错误提示**: 由 Shared VM 内部 `Toasters.get().toast(...)` 统一处理 (旧版 desktop
 *   `onShowError = { AppLog.put(...) }` 已移除, 因 sharedUiMain Screen 不再暴露该回调)。
 *
 * # 路由回调 (由 DesktopApp 注入)
 *
 * - [onBack]: 切回 REPLACE 路由
 * - [onSaved]: 保存成功后切回 REPLACE 路由
 *
 * @param ruleId 规则 id, >0 编辑已有; <=0 新增 (由 DesktopApp 注入, 默认 -1)
 * @param onBack 返回回调 (切回 REPLACE 路由)
 * @param onSaved 保存成功回调 (切回 REPLACE 路由)
 */
@Composable
fun ReplaceEditScreen(
    ruleId: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    // rememberCoroutineScope: Compose 桌面端提供, 绑定 composition 生命周期,
    // onDispose 时自动取消 (替代旧版 viewModel.onCleared() 手动取消 SupervisorJob)
    val scope = rememberCoroutineScope()
    val viewModel = remember {
        ReplaceEditViewModelShared(
            scope = scope,
            clipTextProvider = {
                // 读剪贴板文本 (替代 app 端 getClipText), desktop AWT Clipboard 可跨线程访问
                runCatching {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.getData(DataFlavor.stringFlavor) as? String
                }.getOrNull()
            },
        )
    }
    // 加载规则数据 (ruleId 变化时重新加载)
    // finally 空回调: desktop 不需要 finally, 通过 viewModel.state.collectAsState
    // 在 sharedUiMain ReplaceEditScreen 内订阅触发回填
    LaunchedEffect(ruleId) {
        viewModel.initData(id = ruleId) { }
    }

    io.legado.app.ui.replace.ReplaceEditScreen(
        viewModel = viewModel,
        onBack = onBack,
        onSaved = onSaved,
        onCopyRule = { rule: ReplaceRule ->
            // 序列化 ReplaceRule 为 JSON 写入系统剪贴板 (替代 app 端 sendToClip)
            runCatching {
                val json = GSON.toJson(rule)
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(json), null)
                AppLog.put(jvmGetString("replace_rule_copied_to_clipboard"))
            }.onFailure { AppLog.put(jvmGetString("copy_rule_failed"), it) }
        },
        onPasteRule = { success ->
            // Shared 内部通过 clipTextProvider 取剪贴板文本, desktop 端无需自行读取
            // 解析失败由 Shared 内部 Toasters.get().toast(...) 提示, 无 error 回调
            viewModel.pasteRule(success)
        },
        onHelp = {
            // 打开浏览器跳转正则帮助页 (对应 app 端 showHelp("regexHelp"))
            browseUrl("https://www.runoob.com/regexp/regexp-tutorial.html")
        },
    )
}
