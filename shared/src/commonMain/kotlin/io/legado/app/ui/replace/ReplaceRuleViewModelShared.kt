package io.legado.app.ui.replace

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 替换规则数据修改 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `ReplaceRuleViewModel(application: Application) : BaseViewModel(application)`:
 * - 全部方法 (update/delete/toTop/topSelect/toBottom/bottomSelect/upOrder/
 *   enableSelection/disableSelection/delSelection/addGroup/upGroup/delGroup)
 *   仅依赖 DAO + 协程, 不依赖 Android 专属 API, 可以下沉 commonMain 供多端复用。
 * - DAO 访问走 [AppDbProviders.get].replaceRuleDao (宿主启动时注册)。
 * - 原 `execute { ... }` (BaseViewModel 内 launch + try/catch) 改为
 *   `scope.launch(Dispatchers.IO) { ... }`, 行为等价 (DAO 方法 suspend, Room 内部切线程;
 *   保留 IO 调度器与原 `execute` 默认 Dispatchers.IO 一致)。
 *
 * # 与 [ReplaceRuleListViewModel] 区别
 *
 * - [ReplaceRuleListViewModel] 下沉的是列表展示 VM (含 StateFlow / 选中集 / 分组管理 /
 *   拖拽排序), Compose 宿主用 collectAsState 直接订阅;
 * - 本类下沉的是**操作类** VM (仅 12 个 DAO 写方法, 无状态流), 调用方一次性操作完即结束,
 *   不需要 observe。命名用 `ReplaceRuleViewModelShared` 避免与列表 VM 冲突。
 *
 * # Android 专属依赖替换
 *
 * - `android.text.TextUtils.join(",", set)` → `set.joinToString(",")`
 *   (Kotlin 标准库 joinToString, 行为等价)
 * - [splitNotBlank] 已下沉 commonMain (utils/StringExtensions.shared.kt), 直接复用
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式:
 * - app 端 ReplaceRuleViewModel `extends BaseViewModel`, 内部持有本类实例;
 * - 通过构造函数注入 [scope] (app 端 = `viewModelScope`);
 * - 12 个方法全部转发到本类, app 端调用方接口不变。
 *
 * # 实现细节保持
 *
 * - 原 `delGroup` 嵌套 `execute { execute { ... } }` 保持嵌套结构 (外层 launch 包装
 *   内层 launch, 行为等价于单层 launch, 但保留原代码结构避免"改变实现逻辑")。
 * - 原 `enableSelection/disableSelection` 用 `Array(rules.size) { rules[it].copy(...) }`
 *   构造新数组 (复制 entity, 避免直接修改导致界面不刷新), 下沉后保持一致。
 * - 原 `topSelect/bottomSelect` 直接修改入参 `it.order = ...` (不 copy), 下沉后保持一致
 *   (与 app 端原行为完全相同, 不"偷懒"加 copy)。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 */
class ReplaceRuleViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    fun update(vararg rule: ReplaceRule) {
        scope.launch(Dispatchers.IO) {
            appDb.replaceRuleDao.update(*rule)
        }
    }

    fun delete(rule: ReplaceRule) {
        scope.launch(Dispatchers.IO) {
            appDb.replaceRuleDao.delete(rule)
        }
    }

    fun toTop(rule: ReplaceRule) {
        scope.launch(Dispatchers.IO) {
            rule.order = appDb.replaceRuleDao.minOrder() - 1
            appDb.replaceRuleDao.update(rule)
        }
    }

    fun topSelect(rules: List<ReplaceRule>) {
        scope.launch(Dispatchers.IO) {
            var minOrder = appDb.replaceRuleDao.minOrder() - rules.size
            rules.forEach {
                it.order = ++minOrder
            }
            appDb.replaceRuleDao.update(*rules.toTypedArray())
        }
    }

    fun toBottom(rule: ReplaceRule) {
        scope.launch(Dispatchers.IO) {
            rule.order = appDb.replaceRuleDao.maxOrder() + 1
            appDb.replaceRuleDao.update(rule)
        }
    }

    fun bottomSelect(rules: List<ReplaceRule>) {
        scope.launch(Dispatchers.IO) {
            var maxOrder = appDb.replaceRuleDao.maxOrder()
            rules.forEach {
                it.order = maxOrder++
            }
            appDb.replaceRuleDao.update(*rules.toTypedArray())
        }
    }

    fun upOrder() {
        scope.launch(Dispatchers.IO) {
            val rules = appDb.replaceRuleDao.all()
            for ((index, rule) in rules.withIndex()) {
                rule.order = index + 1
            }
            appDb.replaceRuleDao.update(*rules.toTypedArray())
        }
    }

    fun enableSelection(rules: List<ReplaceRule>) {
        scope.launch(Dispatchers.IO) {
            val array = Array(rules.size) {
                rules[it].copy(isEnabled = true)
            }
            appDb.replaceRuleDao.update(*array)
        }
    }

    fun disableSelection(rules: List<ReplaceRule>) {
        scope.launch(Dispatchers.IO) {
            val array = Array(rules.size) {
                rules[it].copy(isEnabled = false)
            }
            appDb.replaceRuleDao.update(*array)
        }
    }

    fun delSelection(rules: List<ReplaceRule>) {
        scope.launch(Dispatchers.IO) {
            appDb.replaceRuleDao.delete(*rules.toTypedArray())
        }
    }

    fun addGroup(group: String) {
        scope.launch(Dispatchers.IO) {
            val sources = appDb.replaceRuleDao.noGroup()
            sources.forEach { source ->
                source.group = group
            }
            appDb.replaceRuleDao.update(*sources.toTypedArray())
        }
    }

    fun upGroup(oldGroup: String, newGroup: String?) {
        scope.launch(Dispatchers.IO) {
            val sources = appDb.replaceRuleDao.getByGroup(oldGroup)
            sources.forEach { source ->
                source.group?.splitNotBlank(",")?.toHashSet()?.let {
                    it.remove(oldGroup)
                    if (!newGroup.isNullOrEmpty())
                        it.add(newGroup)
                    // 替代 android.text.TextUtils.join(",", it), commonMain 用 joinToString
                    source.group = it.joinToString(",")
                }
            }
            appDb.replaceRuleDao.update(*sources.toTypedArray())
        }
    }

    fun delGroup(group: String) {
        // 保持原 app 端嵌套 execute 结构 (外层 launch 包装内层 launch, 行为等价于单层)
        scope.launch(Dispatchers.IO) {
            scope.launch(Dispatchers.IO) {
                val sources = appDb.replaceRuleDao.getByGroup(group)
                sources.forEach { source ->
                    source.group?.splitNotBlank(",")?.toHashSet()?.let {
                        it.remove(group)
                        // 替代 android.text.TextUtils.join(",", it), commonMain 用 joinToString
                        source.group = it.joinToString(",")
                    }
                }
                appDb.replaceRuleDao.update(*sources.toTypedArray())
            }
        }
    }
}
