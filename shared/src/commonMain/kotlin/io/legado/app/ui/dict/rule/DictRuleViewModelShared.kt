package io.legado.app.ui.dict.rule

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.DictRule
import io.legado.app.help.toast.Toasters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 字典规则 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `DictRuleViewModel(application: Application) : BaseViewModel(application)`:
 * - 全部方法 (update/delete/upSortNumber/enableSelection/disableSelection/importDefault)
 *   仅依赖 DAO + 协程 + AppLog/Toasters, 可以下沉 commonMain 供多端复用。
 * - DAO 访问走 [AppDbProviders.get].dictRuleDao (宿主启动时注册;
 *   AppDbAccessor 接口已暴露 dictRuleDao, 由 app 端 WebBookProvidersImpl 委托 appDb)。
 * - 原 `execute { ... }.onError { ... }` 改为 `scope.launch(Dispatchers.IO) { try { ... }
 *   catch (e) { ... } }`, 行为等价 (Coroutine.async 也是 try/catch 包装,
 *   onError 由 catch 块等价表达)。
 *
 * # DefaultData 处理 (lambda 注入)
 *
 * `DefaultData.importDefaultDictRules()` 依赖 Android 资源 (assets 读 JSON + appCtx),
 * 未下沉 commonMain。通过构造函数 lambda [importDefaultRules] 注入:
 * - app 端传入 `{ DefaultData.importDefaultDictRules() }`;
 * - 桌面端可传入自己的默认规则加载逻辑 (从 classpath 读 JSON 等)。
 *
 * # Android 专属依赖替换
 *
 * - `context.toastOnUi(msg)` → [Toasters.get].toast(msg)
 *   (Toaster 接口已下沉 commonMain, androidMain 注册的实现内部切主线程,
 *   与 `context.toastOnUi` 行为等价)
 * - [AppLog] 已下沉 commonMain, 直接复用
 *
 * # 实现细节保持
 *
 * - 原 `update/delete` 的 onError 回调 (AppLog.put + toastOnUi) 下沉后用 try/catch
 *   捕获, 在 catch 块中执行相同的 AppLog.put + Toasters.get().toast, 行为等价。
 * - 原 `upSortNumber` 用 `insert(*rules.toTypedArray())` (OnConflictStrategy.REPLACE)
 *   而非 `update`, 下沉后保持一致 (insert + REPLACE 等价于 upsert, 与原行为相同)。
 * - 原 `enableSelection/disableSelection` 用 `insert`, 下沉后保持一致。
 *
 * @param scope 协程作用域, actual 平台注入 (Android = `viewModelScope`)
 * @param importDefaultRules 平台专属: 导入默认字典规则
 *   (app 端用 `DefaultData.importDefaultDictRules()`)
 */
class DictRuleViewModelShared(
    private val scope: CoroutineScope,
    private val importDefaultRules: suspend () -> Unit,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    fun update(vararg dictRule: DictRule) {
        scope.launch(Dispatchers.IO) {
            try {
                appDb.dictRuleDao.update(*dictRule)
            } catch (e: Throwable) {
                val msg = "更新字典规则出错\n${e.localizedMessage}"
                AppLog.put(msg, e)
                // 替代 context.toastOnUi(msg), Toasters.get() 已下沉 commonMain
                Toasters.get().toast(msg)
            }
        }
    }

    fun delete(vararg dictRule: DictRule) {
        scope.launch(Dispatchers.IO) {
            try {
                appDb.dictRuleDao.delete(*dictRule)
            } catch (e: Throwable) {
                val msg = "删除字典规则出错\n${e.localizedMessage}"
                AppLog.put(msg, e)
                // 替代 context.toastOnUi(msg), Toasters.get() 已下沉 commonMain
                Toasters.get().toast(msg)
            }
        }
    }

    fun upSortNumber() {
        scope.launch(Dispatchers.IO) {
            val rules = appDb.dictRuleDao.all()
            for ((index, rule) in rules.withIndex()) {
                rule.sortNumber = index + 1
            }
            appDb.dictRuleDao.insert(*rules.toTypedArray())
        }
    }

    fun enableSelection(vararg dictRule: DictRule) {
        scope.launch(Dispatchers.IO) {
            val array = dictRule.map { it.copy(enabled = true) }.toTypedArray()
            appDb.dictRuleDao.insert(*array)
        }
    }

    fun disableSelection(vararg dictRule: DictRule) {
        scope.launch(Dispatchers.IO) {
            val array = dictRule.map { it.copy(enabled = false) }.toTypedArray()
            appDb.dictRuleDao.insert(*array)
        }
    }

    fun importDefault() {
        scope.launch(Dispatchers.IO) {
            importDefaultRules.invoke()
        }
    }
}
