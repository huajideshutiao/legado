package io.legado.app.ui.replace

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 替换规则列表查询条件 (KMP 版, 平台无关)。
 *
 * app 端原用本地化字符串 ("未分组" / "group:xxx" / 关键词) 直接驱动 flow 选择,
 * 下沉后 VM 不能依赖 Android getString, 改用 sealed class 显式表达四种查询语义,
 * 宿主端把 UI 搜索文本映射为 [ReplaceQuery], VM 据此切换 DAO flow。
 */
sealed class ReplaceQuery {
    /** 全部规则 (flowAll) */
    object All : ReplaceQuery()
    /** 未分组 (flowNoGroup) */
    object NoGroup : ReplaceQuery()
    /** 按分组名精确筛选 (flowGroupSearch) */
    data class Group(val name: String) : ReplaceQuery()
    /** 按关键词模糊搜索 name/group (flowSearch) */
    data class Keyword(val key: String) : ReplaceQuery()
}

/**
 * 替换规则列表 ViewModel (KMP 版, commonMain 共享)。
 *
 * 对照 app 端 `ReplaceRuleViewModel` + `ReplaceRuleActivity` 中的数据流/选中/排序逻辑,
 * 下沉后由 app/desktop 两个宿主共用同一份业务编排:
 * - DAO 访问走 [AppDbProviders.get].replaceRuleDao (宿主启动时注册)
 * - 写操作走 [Coroutine.async] 链式协程容器 (commonMain 已下沉)
 * - 状态用 [MutableStateFlow], Compose 宿主用 collectAsState 直接订阅
 * - 分组拼接用 [splitNotBlank] + [joinToString] 替代 android.text.TextUtils.join
 * - VM 自带 [scope]/[onCleared], 桌面端无 lifecycleScope 时由宿主窗口退出时调用
 *
 * 修改数据要 copy, 直接修改 entity 字段会导致 Compose 不刷新 (data class equals 按 id)。
 */
class ReplaceRuleListViewModel {

    private val dao get() = AppDbProviders.get().replaceRuleDao

    /** VM 自管 scope, 桌面端无 lifecycleScope; app 端也可用, onCleared 时取消即可 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _rules = MutableStateFlow<List<ReplaceRule>>(emptyList())
    val rules: StateFlow<List<ReplaceRule>> = _rules.asStateFlow()

    private val _groups = MutableStateFlow<List<String>>(emptyList())
    val groups: StateFlow<List<String>> = _groups.asStateFlow()

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _query = MutableStateFlow<ReplaceQuery>(ReplaceQuery.All)
    val query: StateFlow<ReplaceQuery> = _query.asStateFlow()

    /** 当前数据流订阅 job, 切换 query 时取消上一个, 对齐 app 端 replaceRuleFlowJob 语义 */
    private var rulesFlowJob: Job? = null

    init {
        observeRules()
        observeGroups()
    }

    //region 数据流订阅

    /** 启动/重启规则数据流, 对应 app 端 observeReplaceRuleData(searchKey) */
    private fun observeRules() {
        rulesFlowJob?.cancel()
        rulesFlowJob = scope.launch {
            val flow = when (val q = _query.value) {
                ReplaceQuery.All -> dao.flowAll()
                ReplaceQuery.NoGroup -> dao.flowNoGroup()
                is ReplaceQuery.Group -> dao.flowGroupSearch("%${q.name}%")
                is ReplaceQuery.Keyword -> dao.flowSearch("%${q.key}%")
            }
            flow.catch {
                AppLog.put("替换规则管理界面更新数据出错", it)
            }.flowOn(IoDispatcher).conflate().collect { list ->
                _rules.value = list
                // 选中集与最新数据求交, 移除已不存在的 id (对齐 app 端 intersect 语义)
                val ids = list.map { it.id }.toSet()
                _selected.value = _selected.value.intersect(ids)
            }
        }
    }

    /** 订阅分组列表, 对应 app 端 observeGroupData */
    private fun observeGroups() {
        scope.launch {
            dao.flowGroups().conflate().collect {
                _groups.value = it
            }
        }
    }

    /**
     * 切换查询条件并重启数据流, 对应 app 端 setQuery(query)。
     * 宿主端把 UI 搜索文本映射为 [ReplaceQuery] 传入。
     */
    fun setQuery(query: ReplaceQuery) {
        if (_query.value == query) return
        _query.value = query
        observeRules()
    }

    //endregion

    //region 选中操作

    fun toggleSelected(id: Long, checked: Boolean) {
        _selected.value = if (checked) _selected.value + id else _selected.value - id
    }

    fun selectAll(all: Boolean) {
        _selected.value = if (all) _rules.value.map { it.id }.toSet() else emptySet()
    }

    fun revertSelection() {
        _selected.value = _rules.value.map { it.id }.toSet() - _selected.value
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** 当前选中对应的规则列表 (供批量操作/导出回调使用) */
    fun selection(): List<ReplaceRule> =
        _rules.value.filter { _selected.value.contains(it.id) }

    //endregion

    //region 单条/批量增删改 (对应 app ReplaceRuleViewModel)

    fun update(vararg rule: ReplaceRule) {
        Coroutine.async(scope = scope) {
            dao.update(*rule)
        }
    }

    fun delete(rule: ReplaceRule) {
        Coroutine.async(scope = scope) {
            dao.delete(rule)
        }
    }

    fun deleteSelection() {
        val rules = selection()
        if (rules.isEmpty()) return
        Coroutine.async(scope = scope) {
            dao.delete(*rules.toTypedArray())
        }
        clearSelection()
    }

    fun toTop(rule: ReplaceRule) {
        Coroutine.async(scope = scope) {
            rule.order = dao.minOrder() - 1
            dao.update(rule)
        }
    }

    fun toBottom(rule: ReplaceRule) {
        Coroutine.async(scope = scope) {
            rule.order = dao.maxOrder() + 1
            dao.update(rule)
        }
    }

    fun topSelect(rules: List<ReplaceRule>) {
        if (rules.isEmpty()) return
        Coroutine.async(scope = scope) {
            var minOrder = dao.minOrder() - rules.size
            rules.forEach { it.order = ++minOrder }
            dao.update(*rules.toTypedArray())
        }
    }

    fun bottomSelect(rules: List<ReplaceRule>) {
        if (rules.isEmpty()) return
        Coroutine.async(scope = scope) {
            var maxOrder = dao.maxOrder()
            rules.forEach { it.order = maxOrder++ }
            dao.update(*rules.toTypedArray())
        }
    }

    fun enableSelection(enabled: Boolean) {
        val rules = selection()
        if (rules.isEmpty()) return
        Coroutine.async(scope = scope) {
            val array = Array(rules.size) { rules[it].copy(isEnabled = enabled) }
            dao.update(*array)
        }
    }

    /**
     * 拖拽中即时交换列表顺序 (仅改 _rules state, 不写库), 对齐 app 端 onMove 语义。
     * flow 未 re-emit (DB 未变), 故 state 不会被覆盖; 松手时由 [persistOrder] 落库。
     */
    fun moveItem(from: Int, to: Int) {
        val list = _rules.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _rules.value = list
    }

    /**
     * 拖拽松手后持久化当前列表顺序, 对应 app 端 persistOrder。
     * 取 [_rules] 当前顺序写回 sortOrder, flow re-emit 后用新顺序展示。
     */
    fun persistOrder() {
        val rules = _rules.value
        rules.forEachIndexed { index, item -> item.order = index + 1 }
        update(*rules.toTypedArray())
    }

    //endregion

    //region 分组管理 (对应 app ReplaceRuleViewModel.addGroup/upGroup/delGroup)

    // 使用 DAO 查询 noGroup() 与 app 端 ReplaceRuleViewModelShared.addGroup 行为一致
    // (避免 _rules.value 因筛选/未刷新导致遗漏, 与 app 端 addGroup 等价)
    fun addGroup(group: String) {
        Coroutine.async(scope = scope) {
            val sources = dao.noGroup()
            sources.forEach { source ->
                source.group = group
            }
            dao.update(*sources.toTypedArray())
        }
    }

    /** 改名: 把规则中包含 oldGroup 的分组字段替换为 newGroup (null=删除该分组标签) */
    fun upGroup(oldGroup: String, newGroup: String?) {
        Coroutine.async(scope = scope) {
            val rules = dao.getByGroup(oldGroup)
            rules.forEach { rule ->
                rule.group?.splitNotBlank(",")?.toHashSet()?.let { set ->
                    set.remove(oldGroup)
                    if (!newGroup.isNullOrEmpty()) set.add(newGroup)
                    // 替代 android.text.TextUtils.join(",", set), commonMain 用 joinToString
                    rule.group = set.joinToString(",")
                }
            }
            dao.update(*rules.toTypedArray())
        }
    }

    fun delGroup(group: String) {
        Coroutine.async(scope = scope) {
            val rules = dao.getByGroup(group)
            rules.forEach { rule ->
                rule.group?.splitNotBlank(",")?.toHashSet()?.let { set ->
                    set.remove(group)
                    rule.group = set.joinToString(",")
                }
            }
            dao.update(*rules.toTypedArray())
        }
    }

    //endregion

    /** 宿主销毁时调用, 取消所有数据流订阅与协程 */
    fun onCleared() {
        scope.cancel()
    }
}
