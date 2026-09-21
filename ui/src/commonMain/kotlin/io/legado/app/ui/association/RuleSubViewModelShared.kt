package io.legado.app.ui.association

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.RuleSub
import io.legado.app.help.coroutine.IoDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 规则订阅数据修改 VM 共享核心 (commonMain)。
 *
 * 对照 app 端 `RuleSubViewModel(app) : BaseViewModel(app)`: 全部 5 个方法
 * (save/delete/upOrder/toTop/toBottom) 仅依赖 DAO + 协程, 可下沉多端复用。
 * DAO 走 [AppDbProviders.get].ruleSubDao; `execute{...}` 改为
 * `scope.launch(Dispatchers.IO) { ... }`, 行为等价。
 *
 * 实现细节保持: save 用 `all().maxOfOrNull { it.customOrder } ?: 0` (不用 SQL maxOrder,
 * 严格保持原实现); toTop/toBottom 用 minOf/maxOf ± 1 且直接改入参 (不 copy);
 * upOrder 用 mapIndexed 构造新数组 (复制 entity, 避免直接修改导致界面不刷新)。
 *
 * 设计: 组合委托 (BaseViewModel 是 AndroidViewModel 不能继承), 注入 [scope]。
 *
 * @param scope 协程作用域 (Android = viewModelScope / 桌面 = rememberCoroutineScope())
 */
class RuleSubViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    fun save(ruleSub: RuleSub) {
        scope.launch(IoDispatcher) {
            if (ruleSub.customOrder == 0) {
                ruleSub.customOrder =
                    (appDb.ruleSubDao.all().maxOfOrNull { it.customOrder } ?: 0) + 1
            }
            appDb.ruleSubDao.insert(ruleSub)
        }
    }

    fun delete(ruleSub: RuleSub) {
        scope.launch(IoDispatcher) {
            appDb.ruleSubDao.delete(ruleSub)
        }
    }

    fun upOrder(items: List<RuleSub>) {
        scope.launch(IoDispatcher) {
            val array = items.mapIndexed { index, ruleSub ->
                ruleSub.copy(customOrder = index + 1)
            }.toTypedArray()
            appDb.ruleSubDao.update(*array)
        }
    }

    fun toTop(ruleSub: RuleSub) {
        scope.launch(IoDispatcher) {
            val minOrder = (appDb.ruleSubDao.all().minOfOrNull { it.customOrder } ?: 0) - 1
            ruleSub.customOrder = minOrder
            appDb.ruleSubDao.update(ruleSub)
        }
    }

    fun toBottom(ruleSub: RuleSub) {
        scope.launch(IoDispatcher) {
            val maxOrder = (appDb.ruleSubDao.all().maxOfOrNull { it.customOrder } ?: 0) + 1
            ruleSub.customOrder = maxOrder
            appDb.ruleSubDao.update(ruleSub)
        }
    }
}
