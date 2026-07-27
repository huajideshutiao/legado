package io.legado.app.ui.replace

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 替换规则编辑 ViewModel (KMP 版, commonMain 共享)。
 *
 * 对照 app 端 `ReplaceEditViewModel` 下沉, 去掉 Android Intent/剪贴板依赖:
 * - [initData] 用显式参数 (id/pattern/isRegex/scope) 替代 Intent 取值
 * - [pasteRule] 由宿主获取剪贴板文本后传入 (app 端 getClipText / desktop 端 Clipboard)
 * - DAO 访问走 [AppDbProviders.get].replaceRuleDao
 * - 写操作走 [Coroutine.async] 链式协程容器
 *
 * @see ReplaceEditScreen
 */
class ReplaceEditViewModel {

    private val dao get() = AppDbProviders.get().replaceRuleDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前编辑的规则 (initData 后非空) */
    var replaceRule: ReplaceRule? = null
        private set

    private val _state = MutableStateFlow<ReplaceRule?>(null)
    /** 规则加载完成信号, Compose 用 collectAsState 驱动表单回填 */
    val state: StateFlow<ReplaceRule?> = _state.asStateFlow()

    /**
     * 初始化规则数据, 对应 app 端 initData(intent)。
     *
     * @param id 规则 id, >0 表示编辑已有; <=0 表示新增 (用 pattern/isRegex/ruleScope 构造空模板)
     */
    fun initData(
        id: Long,
        pattern: String? = null,
        isRegex: Boolean = false,
        ruleScope: String? = null,
    ) {
        Coroutine.async(scope = scope) {
            replaceRule = if (id > 0) {
                dao.findById(id)
            } else {
                ReplaceRule(
                    name = pattern ?: "",
                    pattern = pattern ?: "",
                    isRegex = isRegex,
                    scope = ruleScope,
                )
            }
            replaceRule
        }.onSuccess {
            _state.value = it
        }
    }

    /**
     * 粘贴规则, 对应 app 端 pasteRule。
     *
     * 宿主端先获取剪贴板文本传入 [clipText], VM 解析 JSON 成 ReplaceRule 后回调 [success];
     * 解析失败 (空文本/格式错误) 回调 [error]。
     */
    fun pasteRule(
        clipText: String?,
        success: (ReplaceRule) -> Unit,
        error: (String) -> Unit,
    ) {
        Coroutine.async(scope = scope) {
            if (clipText.isNullOrBlank()) {
                throw NoStackTraceException("剪贴板为空")
            }
            GSON.fromJsonObject<ReplaceRule>(clipText).getOrNull()
                ?: throw NoStackTraceException("格式不对")
        }.onSuccess {
            success(it)
        }.onError {
            error(it.localizedMessage ?: "Error")
        }
    }

    /**
     * 保存规则, 对应 app 端 save。
     *
     * 校验 [replaceRule] 有效性后插入; 新规则 (order==Int.MIN_VALUE) 自动取 maxOrder+1。
     * 成功回调 [success], 失败 (校验错/DB 错) 回调 [error]。
     */
    fun save(rule: ReplaceRule, success: () -> Unit, error: (String) -> Unit) {
        Coroutine.async(scope = scope) {
            rule.checkValid()
            if (rule.order == Int.MIN_VALUE) {
                rule.order = dao.maxOrder() + 1
            }
            dao.insert(rule)
        }.onSuccess {
            success()
        }.onError {
            error("save error, ${it.localizedMessage}")
        }
    }

    /** 宿主销毁时调用, 取消所有协程 */
    fun onCleared() {
        scope.cancel()
    }
}
