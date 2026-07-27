package io.legado.app.ui.book.toc.rule

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.TxtTocRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * TxtToc 规则 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `TxtTocRuleViewModel(app: Application) : BaseViewModel(app)`:
 * - 全部方法 (save/del/update/importDefault/toTop/toBottom/upOrder/
 *   enableSelection/disableSelection) 仅依赖 DAO + 协程, 可以下沉 commonMain 供多端复用。
 * - DAO 访问走 [AppDbProviders.get].txtTocRuleDao (宿主启动时注册;
 *   AppDbAccessor 接口已暴露 txtTocRuleDao, 由 app 端 WebBookProvidersImpl 委托 appDb)。
 * - 原 `execute { ... }` 改为 `scope.launch(Dispatchers.IO) { ... }`, 行为等价。
 *
 * # DefaultData 处理 (lambda 注入)
 *
 * `DefaultData.importDefaultTocRules()` 依赖 Android 资源 (assets 读 JSON + appCtx),
 * 未下沉 commonMain。通过构造函数 lambda [importDefaultRules] 注入:
 * - app 端传入 `{ DefaultData.importDefaultTocRules() }`;
 * - 桌面端可传入自己的默认规则加载逻辑 (从 classpath 读 JSON 等)。
 *
 * # 实现细节保持
 *
 * - 原 `toTop/toBottom` 直接修改 `source.serialNumber = ...` (不 copy), 下沉后保持一致
 *   (与 app 端原行为完全相同, 不"偷懒"加 copy)。
 * - 原 `enableSelection/disableSelection` 用 `insert` (OnConflictStrategy.REPLACE)
 *   而非 `update`, 下沉后保持一致 (insert + REPLACE 等价于 upsert, 与原行为相同)。
 * - 原 `upOrder` 用 `update(*sources.toTypedArray())`, 下沉后保持一致。
 *
 * @param scope 协程作用域, actual 平台注入 (Android = `viewModelScope`)
 * @param importDefaultRules 平台专属: 导入默认 TxtToc 规则
 *   (app 端用 `DefaultData.importDefaultTocRules()`)
 */
class TxtTocRuleViewModelShared(
    private val scope: CoroutineScope,
    private val importDefaultRules: suspend () -> Unit,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    fun save(txtTocRule: TxtTocRule) {
        scope.launch(Dispatchers.IO) {
            appDb.txtTocRuleDao.insert(txtTocRule)
        }
    }

    fun del(vararg txtTocRule: TxtTocRule) {
        scope.launch(Dispatchers.IO) {
            appDb.txtTocRuleDao.delete(*txtTocRule)
        }
    }

    fun update(vararg txtTocRule: TxtTocRule) {
        scope.launch(Dispatchers.IO) {
            appDb.txtTocRuleDao.update(*txtTocRule)
        }
    }

    fun importDefault() {
        scope.launch(Dispatchers.IO) {
            importDefaultRules.invoke()
        }
    }

    fun toTop(vararg rules: TxtTocRule) {
        scope.launch(Dispatchers.IO) {
            val minOrder = appDb.txtTocRuleDao.minOrder() - 1
            rules.forEachIndexed { index, source ->
                source.serialNumber = minOrder - index
            }
            appDb.txtTocRuleDao.update(*rules)
        }
    }

    fun toBottom(vararg sources: TxtTocRule) {
        scope.launch(Dispatchers.IO) {
            val maxOrder = appDb.txtTocRuleDao.maxOrder() + 1
            sources.forEachIndexed { index, source ->
                source.serialNumber = maxOrder + index
            }
            appDb.txtTocRuleDao.update(*sources)
        }
    }

    fun upOrder() {
        scope.launch(Dispatchers.IO) {
            val sources = appDb.txtTocRuleDao.all()
            for ((index: Int, source: TxtTocRule) in sources.withIndex()) {
                source.serialNumber = index + 1
            }
            appDb.txtTocRuleDao.update(*sources.toTypedArray())
        }
    }

    fun enableSelection(vararg txtTocRule: TxtTocRule) {
        scope.launch(Dispatchers.IO) {
            val array = txtTocRule.map { it.copy(enable = true) }.toTypedArray()
            appDb.txtTocRuleDao.insert(*array)
        }
    }

    fun disableSelection(vararg txtTocRule: TxtTocRule) {
        scope.launch(Dispatchers.IO) {
            val array = txtTocRule.map { it.copy(enable = false) }.toTypedArray()
            appDb.txtTocRuleDao.insert(*array)
        }
    }
}
