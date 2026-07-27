package io.legado.app.ui.association

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.RuleSub
import io.legado.app.help.coroutine.IoDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 规则订阅数据修改 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `RuleSubViewModel(application: Application) : BaseViewModel(application)`:
 * - 全部 5 个方法 (save/delete/upOrder/toTop/toBottom) 仅依赖 DAO + 协程,
 *   不依赖 Android 专属 API, 可以下沉 commonMain 供多端复用。
 * - DAO 访问走 [AppDbProviders.get].ruleSubDao (宿主启动时注册)。
 * - 原 `execute { ... }` (BaseViewModel 内 launch + try/catch) 改为
 *   `scope.launch(Dispatchers.IO) { ... }`, 行为等价 (DAO 方法 suspend, Room 内部切线程;
 *   保留 IO 调度器与原 `execute` 默认 Dispatchers.IO 一致)。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式:
 * - app 端 RuleSubViewModel `extends BaseViewModel`, 内部持有本类实例;
 * - 通过构造函数注入 [scope] (app 端 = `viewModelScope`);
 * - 5 个方法全部转发到本类, app 端调用方接口不变。
 *
 * # 实现细节保持
 *
 * - 原 `save` 用 `all().maxOfOrNull { it.customOrder } ?: 0` 计算 maxOrder
 *   (不使用 RuleSubDao.maxOrder() SQL 方法, 严格保持原实现逻辑);
 * - 原 `toTop` 用 `all().minOfOrNull { it.customOrder } ?: 0) - 1`,
 *   `toBottom` 用 `all().maxOfOrNull { it.customOrder } ?: 0) + 1`,
 *   且直接修改入参 `ruleSub.customOrder` (不 copy), 下沉后保持一致;
 * - 原 `upOrder` 用 `mapIndexed { index, ruleSub -> ruleSub.copy(customOrder = index + 1) }`
 *   构造新数组 (复制 entity, 避免直接修改导致界面不刷新), 下沉后保持一致。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = `rememberCoroutineScope()`)
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
