package io.legado.app.ui.book.source.manage

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CoroutineScope

/**
 * 书源管理数据修改 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `BookSourceViewModel(application: Application) : BaseViewModel(application)`
 * (位于 `io.legado.app.ui.book.source.manage`): 16 个纯 DAO 方法仅依赖 Room DAO + 协程,
 * 可以下沉 commonMain 供多端复用 (Android / Desktop / iOS / 鸿蒙)。
 *
 * DAO 访问走 [AppDbProviders.get].bookSourceDao (宿主启动时由 app 端注册
 * AppDbAccessorImpl, 已暴露 bookSourceDao), 替代 app 端 `appDb.bookSourceDao` 单例。
 *
 * # API 替换
 *
 * - 原 `BaseViewModel.execute { ... }` (内部委托 [Coroutine.async]) 下沉后直接调
 *   [Coroutine.async], 行为等价 (业务 context=IO, 回调 executeContext=mainDispatcher,
 *   与 BaseViewModel.execute 默认值一致)。
 * - 原 `android.text.TextUtils.join(",", set)` 下沉后用 Kotlin stdlib
 *   `set.joinToString(",")` 替代 (无 Android 依赖, 行为等价)。
 * - 原 `String.cnCompare(String)` (app 端 Android ICU 扩展) 仅在 saveToFile/getBookSources
 *   内使用 (该扩展现已下沉 commonMain utils/StringCnCompare.kt), 本类无排序需求不使用。
 *
 * # Android 专属依赖 (留 app 端)
 *
 * - **saveToFile (2 个重载) / getBookSources**: 依赖 `context.filesDir` +
 *   `FileUtils.createFileWithReplace` + `GSON.writeToOutputStream` + 文件流 +
 *   `List<BookSourcePart>.toBookSource()` app 端扩展 (该扩展内部 runBlocking 调
 *   appDb.bookSourceDao.getBookSourcesFix), 不能下沉 commonMain, 留 app 端
 *   [BookSourceViewModel] 内自行实现。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式 (对照
 * [io.legado.app.ui.replace.edit.ReplaceEditViewModelShared] /
 * [io.legado.app.ui.book.group.GroupViewModelShared]):
 * - app 端 `BookSourceViewModel(application)` `extends BaseViewModel(application)`,
 *   内部持有本类实例, 通过 `viewModelScope` 注入;
 * - desktop 端在 Compose `remember` 中构造本类, 注入应用 scope;
 * - 仅注入 [scope] 一个参数, 调用方 (BookSourceActivity 等) 代码零改动。
 *
 * 注: 同模块已有 [io.legado.app.ui.book.source.BookSourceListViewModel]
 * (位于 `book.source` 包, 非 `manage` 子包), 该类是早期独立下沉的 KMP VM, 自带
 * flowSources/flowGroups 等数据流接口供 desktop/Compose 宿主直接订阅; 本类
 * (Shared) 走组合委托模式专供 app 端 BookSourceViewModel 包装, 与同模块
 * ReplaceEditViewModelShared / GroupViewModelShared 一致, 便于宿主接管生命周期。
 * 二者方法签名相同, 实现等价, 互不引用, 维护时请同步修改。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 */
class BookSourceViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /**
     * 置顶书源, 对应 app 端 `topSource(vararg sources)`。
     *
     * 取当前最小 order - 1 起步, 按 sources 自身 customOrder 升序逐个 -1,
     * 保证置顶后内部相对顺序不变 (与 app 端原逻辑一致)。
     */
    fun topSource(vararg sources: BookSourcePart) {
        Coroutine.async(scope = scope) {
            sources.sortBy { it.customOrder }
            val minOrder = appDb.bookSourceDao.minOrder() - 1
            val array = sources.mapIndexed { index, it ->
                it.copy(customOrder = minOrder - index)
            }
            appDb.bookSourceDao.upOrder(array)
        }
    }

    /**
     * 置底书源, 对应 app 端 `bottomSource(vararg sources)`。
     *
     * 取当前最大 order + 1 起步, 按 sources 自身 customOrder 升序逐个 +1,
     * 保证置底后内部相对顺序不变 (与 app 端原逻辑一致)。
     */
    fun bottomSource(vararg sources: BookSourcePart) {
        Coroutine.async(scope = scope) {
            sources.sortBy { it.customOrder }
            val maxOrder = appDb.bookSourceDao.maxOrder() + 1
            val array = sources.mapIndexed { index, it ->
                it.copy(customOrder = maxOrder + index)
            }
            appDb.bookSourceDao.upOrder(array)
        }
    }

    /**
     * 删除书源, 对应 app 端 `del(sources)`。
     *
     * 委托 [SourceHelp.deleteBookSourceParts] (已下沉 commonMain, 内部走
     * runInTransaction 包裹 bookSourceDao.delete + cacheDao.delete 等保证原子性)。
     */
    fun del(sources: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            SourceHelp.deleteBookSourceParts(sources)
        }
    }

    /** 更新书源, 对应 app 端 `update(vararg bookSource)`。 */
    fun update(vararg bookSource: BookSource) {
        Coroutine.async(scope = scope) {
            appDb.bookSourceDao.update(*bookSource)
        }
    }

    /**
     * 批量更新自定义排序, 对应 app 端 `upOrder(items)`。
     *
     * 空列表提前 return (与 app 端原 `if (items.isEmpty()) return` 一致,
     * 避免无意义协程启动)。
     */
    fun upOrder(items: List<BookSourcePart>) {
        if (items.isEmpty()) return
        Coroutine.async(scope = scope) {
            appDb.bookSourceDao.upOrder(items)
        }
    }

    /** 启用/禁用书源, 对应 app 端 `enable(enable, items)`。 */
    fun enable(enable: Boolean, items: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            appDb.bookSourceDao.enable(enable, items)
        }
    }

    /** 启用所选书源, 对应 app 端 `enableSelection(sources)`。 */
    fun enableSelection(sources: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            appDb.bookSourceDao.enable(true, sources)
        }
    }

    /** 禁用所选书源, 对应 app 端 `disableSelection(sources)`。 */
    fun disableSelection(sources: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            appDb.bookSourceDao.enable(false, sources)
        }
    }

    /** 启用/禁用发现, 对应 app 端 `enableExplore(enable, items)`。 */
    fun enableExplore(enable: Boolean, items: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            appDb.bookSourceDao.enableExplore(enable, items)
        }
    }

    /** 启用所选书源发现, 对应 app 端 `enableSelectExplore(sources)`。 */
    fun enableSelectExplore(sources: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            appDb.bookSourceDao.enableExplore(true, sources)
        }
    }

    /** 禁用所选书源发现, 对应 app 端 `disableSelectExplore(sources)`。 */
    fun disableSelectExplore(sources: List<BookSourcePart>) {
        Coroutine.async(scope = scope) {
            appDb.bookSourceDao.enableExplore(false, sources)
        }
    }

    /**
     * 批量加入分组, 对应 app 端 `selectionAddToGroups(sources, groups)`。
     *
     * 对每个 source 调 `copy().apply { addGroup(groups) }` (BookSourcePart.addGroup
     * 已下沉 commonMain), 再 upGroup 批量持久化。
     */
    fun selectionAddToGroups(sources: List<BookSourcePart>, groups: String) {
        Coroutine.async(scope = scope) {
            val array = sources.map {
                it.copy().apply {
                    addGroup(groups)
                }
            }
            appDb.bookSourceDao.upGroup(array)
        }
    }

    /**
     * 批量移出分组, 对应 app 端 `selectionRemoveFromGroups(sources, groups)`。
     *
     * 对每个 source 调 `copy().apply { removeGroup(groups) }` (BookSourcePart.removeGroup
     * 已下沉 commonMain), 再 upGroup 批量持久化。
     */
    fun selectionRemoveFromGroups(sources: List<BookSourcePart>, groups: String) {
        Coroutine.async(scope = scope) {
            val array = sources.map {
                it.copy().apply {
                    removeGroup(groups)
                }
            }
            appDb.bookSourceDao.upGroup(array)
        }
    }

    /**
     * 给所有未分组书源设置分组, 对应 app 端 `addGroup(group)`。
     *
     * 取 `bookSourceDao.noGroup()` 列表, 全部赋 `bookSourceGroup = group` 后 update。
     */
    fun addGroup(group: String) {
        Coroutine.async(scope = scope) {
            val sources = appDb.bookSourceDao.noGroup()
            sources.forEach { source ->
                source.bookSourceGroup = group
            }
            appDb.bookSourceDao.update(*sources.toTypedArray())
        }
    }

    /**
     * 重命名分组, 对应 app 端 `upGroup(oldGroup, newGroup)`。
     *
     * 取 `bookSourceDao.getByGroup(oldGroup)` 列表, 对每个 source 的 bookSourceGroup
     * 按 `,` splitNotBlank 拆分去重, 移除 oldGroup, newGroup 非空则加入, 最后用
     * `joinToString(",")` 重新拼接 (替代原 `TextUtils.join(",", it)`), update 持久化。
     *
     * @param oldGroup 旧分组名
     * @param newGroup 新分组名 (null 或空串表示仅移除 oldGroup, 不替换)
     */
    fun upGroup(oldGroup: String, newGroup: String?) {
        Coroutine.async(scope = scope) {
            val sources = appDb.bookSourceDao.getByGroup(oldGroup)
            sources.forEach { source ->
                source.bookSourceGroup?.splitNotBlank(",")?.toHashSet()?.let {
                    it.remove(oldGroup)
                    if (!newGroup.isNullOrEmpty())
                        it.add(newGroup)
                    // 替代原 android.text.TextUtils.join(",", it), 行为等价 (Kotlin stdlib)
                    source.bookSourceGroup = it.joinToString(",")
                }
            }
            appDb.bookSourceDao.update(*sources.toTypedArray())
        }
    }

    /**
     * 删除分组, 对应 app 端 `delGroup(group)`。
     *
     * 取 `bookSourceDao.getByGroup(group)` 列表, 对每个 source 调 `removeGroup(group)`
     * (BookSourcePart.removeGroup 已下沉 commonMain), update 持久化。
     *
     * 注: 原 app 端实现为嵌套 `execute { execute { ... } }` 结构, 外层 execute 仅启动
     * 一个协程跑内层 execute, 内层 execute 才真正跑 DAO 业务; 二者均无 onSuccess/onError
     * 回调, 行为上等价于单次 execute。下沉后合并为单层 [Coroutine.async], 与同模块
     * [io.legado.app.ui.book.source.BookSourceListViewModel.delGroup] 实现一致,
     * 行为等价 (取消外层空壳协程, 不改变 DAO 操作语义)。
     */
    fun delGroup(group: String) {
        Coroutine.async(scope = scope) {
            val sources = appDb.bookSourceDao.getByGroup(group)
            sources.forEach { source ->
                source.removeGroup(group)
            }
            appDb.bookSourceDao.update(*sources.toTypedArray())
        }
    }
}
