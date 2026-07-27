package io.legado.app.ui.association

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.RuleSub
import io.legado.app.help.openURL
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.platform.rememberString
import kotlinx.coroutines.flow.collectLatest

/**
 * iOS 端规则订阅管理 Screen 入口 (KP5: 包装 shared/sharedUiMain 的 [RuleSubScreen])。
 *
 * # 职责
 *
 * 对照 desktop `desktop/src/main/kotlin/io/legado/desktop/ui/association/RuleSubScreen.kt`
 * 的包装模式, iOS 端在 [io.legado.app.ui.IosNavHost] 的 RULE_SUB 路由分支调用本入口。
 *
 * 仅做 iOS 平台适配, 业务展示与交互逻辑全部下沉到 shared/sharedUiMain:
 * - **VM**: `RuleSubViewModelShared(scope)` (无 importDefaultRules 等额外参数)
 * - **数据流**: 订阅 VM flowAll 自动更新 ruleSubs
 * - **拖拽/置顶置底/删除**: 直接调 VM 方法
 * - **新增/编辑**: 暂留 TODO (依赖 RuleSubEditDialog 下沉)
 * - **打开订阅**: 暂留 TODO (依赖 showDialogFragment, 后续 KP6+ 接入)
 *
 * # 简化项
 *
 * - 新增/编辑订阅对话框暂未接入 (依赖 shared RuleSubEditDialog 下沉)
 * - 打开订阅暂未接入 (依赖 ImportXxxDialog 系列)
 *
 * @param onBack 返回回调 (切回书架路由)
 */
@Composable
fun IosRuleSubScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(scope) { RuleSubViewModelShared(scope) }

    var ruleSubs by remember { mutableStateOf<List<RuleSub>>(emptyList()) }
    LaunchedEffect(Unit) {
        viewModel.flowAll().collectLatest { ruleSubs = it }
    }

    val state = RuleSubUiState(ruleSubs = ruleSubs)

    // iOS 端待接入提示文案 (actions lambda 非 @Composable, 需预先缓存)
    val addRuleSubText = rememberString("ios_add_rule_sub_not_implemented")
    val editRuleSubText = rememberString("ios_edit_rule_sub_not_implemented")
    val openRuleSubText = rememberString("ios_open_rule_sub_not_implemented")

    val actions = remember(scope, onBack) {
        object : RuleSubUiActions {
            override fun onBack() = onBack()
            override fun onAdd() {
                // TODO: 弹 RuleSubEditDialog 新增订阅 (后续 KP6+ 接入)
                Toasters.get().toast(addRuleSubText)
            }
            override fun onEdit(ruleSub: RuleSub) {
                // TODO: 弹 RuleSubEditDialog 编辑订阅 (后续 KP6+ 接入)
                Toasters.get().toast(editRuleSubText)
            }
            override fun onOpenSubscription(ruleSub: RuleSub) {
                // TODO: 根据 ruleSub.type 弹对应 ImportXxxDialog (后续 KP6+ 接入)
                Toasters.get().toast(openRuleSubText)
            }
            override fun onMove(from: Int, to: Int) {
                // 即时交换内存列表 (shared Screen 已处理 UI, 落库走 onPersistOrder)
                ruleSubs = ruleSubs.toMutableList().apply { add(to, removeAt(from)) }
            }
            override fun onPersistOrder() {
                viewModel.upOrder(ruleSubs)
            }
            override fun onToTop(ruleSub: RuleSub) {
                viewModel.toTop(ruleSub)
            }
            override fun onToBottom(ruleSub: RuleSub) {
                viewModel.toBottom(ruleSub)
            }
            override fun onDelete(ruleSub: RuleSub) {
                viewModel.delete(ruleSub)
            }
        }
    }

    RuleSubScreen(state = state, actions = actions)
}
