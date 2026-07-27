package io.legado.app.ui.association

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.utils.inputStream
import io.legado.app.utils.isUri
import kotlinx.coroutines.launch

/**
 * 导入书源 VM (Android 端, 组合委托)。
 *
 * # KMP 化重构说明
 *
 * 核心业务逻辑 (importSelect / importSource / importSourceUrl / importFromJson /
 * comparisonSource) 已下沉到 shared commonMain [ImportBookSourceViewModelShared],
 * 用 `MutableStateFlow` 替代 `MutableLiveData` (LiveData 不可 KMP)。
 *
 * 本类采用**组合委托**模式持有 [shared] 实例, 不通过继承 [ImportBookSourceViewModelShared]:
 * - 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 *   `viewModelScope`), Kotlin 单继承无法同时继承 [ImportBookSourceViewModelShared];
 * - 通过构造函数注入 [viewModelScope] 到 [shared], 全部业务方法转发到 [shared]。
 *
 * # Android 专属依赖移除
 *
 * - **Uri 读取留本类**: `mText.isUri()` + `mText.toUri().inputStream(context)` 是
 *   Android ContentResolver 专属, commonMain 不可用。本类 [importSource] 先判断 isUri,
 *   若是 Uri 则先读取文本再转发到 [shared.importSource]; 否则直接转发。
 *   URL/JSON 解析逻辑全部下沉到 [shared]。
 * - **errorLiveData / successLiveData**: 保留原 MutableLiveData, 在 init 块内
 *   `viewModelScope.launch { collect { postValue } }` 桥接 [shared.errorState] /
 *   [shared.successState], 调用方 `observe` 用法不变 (对照
 *   [io.legado.app.ui.book.explore.ExploreShowViewModel] 的 7 个 LiveData 桥接模式)。
 * - **allSources / checkSources / selectStatus 等 MutableList**: 直接 getter 转发到
 *   [shared] 同实例引用, app 端 `selectStatus[index] = checked` 与 shared 端读取一致;
 * - **isAddGroup / groupName**: 通过 var getter/setter 委托 [shared] (修改同步反映到 shared)。
 *
 * # 调用方兼容
 *
 * [ImportBookSourceDialog] 调用方式保持不变:
 * - `viewModel.errorLiveData.observe(...)` / `viewModel.successLiveData.observe(...)`;
 * - `viewModel.allSources[it]` / `viewModel.checkSources[it]` / `viewModel.selectStatus[it]`
 *   / `viewModel.newSourceStatus` / `viewModel.updateSourceStatus` (getter 转发);
 * - `viewModel.isSelectAll` / `viewModel.isSelectAllNew` / `viewModel.isSelectAllUpdate`
 *   / `viewModel.selectCount` (getter 转发);
 * - `viewModel.isAddGroup = ...` / `viewModel.groupName = ...` (setter 转发);
 * - `viewModel.importSource(source)` / `viewModel.importSelect { ... }` (方法转发)。
 */
class ImportBookSourceViewModel(app: Application) : BaseViewModel(app) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope] 供 shared 内部协程使用。
     *
     * - shared 承担状态托管 (StateFlow) + 全部业务方法;
     * - 本类仅作 LiveData 桥接 + Uri 分支预处理。
     */
    private val shared: ImportBookSourceViewModelShared = ImportBookSourceViewModelShared(
        scope = viewModelScope,
    )

    // region 2 个 LiveData 桥接 (订阅 shared 的 StateFlow, 转发到 MutableLiveData)

    /** 导入错误信息 (订阅 [shared.errorState], 对照原 `errorLiveData: MutableLiveData<String>`)。 */
    val errorLiveData = MutableLiveData<String>()

    /** 导入成功信号 (订阅 [shared.successState], 对照原 `successLiveData: MutableLiveData<Int>`)。 */
    val successLiveData = MutableLiveData<Int>()

    init {
        // 订阅 shared 的 2 个 StateFlow, 把变化推到对应 LiveData
        // 一次性订阅, viewModelScope cancel 时自动结束
        // 过滤 null 避免初始假触发 (StateFlow 是 hot flow, collect 时立即收到当前值)
        viewModelScope.launch {
            shared.errorState.collect { value ->
                value?.let { errorLiveData.postValue(it) }
            }
        }
        viewModelScope.launch {
            shared.successState.collect { value ->
                value?.let { successLiveData.postValue(it) }
            }
        }
    }
    // endregion

    // region 字段 getter/setter 转发 (供 Dialog 直接读写, 同实例引用保证修改同步)

    /** 是否将分组追加到原有分组 (false=覆盖), 转发到 [shared.isAddGroup]。 */
    var isAddGroup: Boolean
        get() = shared.isAddGroup
        set(value) {
            shared.isAddGroup = value
        }

    /** 自定义分组名 (null/空 表示不修改分组), 转发到 [shared.groupName]。 */
    var groupName: String?
        get() = shared.groupName
        set(value) {
            shared.groupName = value
        }

    /** 解析出的待导入书源列表, 转发到 [shared.allSources] (同实例引用)。 */
    val allSources: ArrayList<BookSource> get() = shared.allSources

    /** 已有书源的部分字段, 转发到 [shared.checkSources] (同实例引用)。 */
    val checkSources: ArrayList<BookSourcePart?> get() = shared.checkSources

    /** 每个 allSources 元素是否被选中导入, 转发到 [shared.selectStatus] (同实例引用)。 */
    val selectStatus: ArrayList<Boolean> get() = shared.selectStatus

    /** 标记对应位置书源是否为新增, 转发到 [shared.newSourceStatus] (同实例引用)。 */
    val newSourceStatus: ArrayList<Boolean> get() = shared.newSourceStatus

    /** 标记对应位置书源是否为更新, 转发到 [shared.updateSourceStatus] (同实例引用)。 */
    val updateSourceStatus: ArrayList<Boolean> get() = shared.updateSourceStatus

    /** 是否全部选中, 转发到 [shared.isSelectAll]。 */
    val isSelectAll: Boolean get() = shared.isSelectAll

    /** 新增项是否全部选中, 转发到 [shared.isSelectAllNew]。 */
    val isSelectAllNew: Boolean get() = shared.isSelectAllNew

    /** 更新项是否全部选中, 转发到 [shared.isSelectAllUpdate]。 */
    val isSelectAllUpdate: Boolean get() = shared.isSelectAllUpdate

    /** 选中数量, 转发到 [shared.selectCount]。 */
    val selectCount: Int get() = shared.selectCount
    // endregion

    /**
     * 导入选中的书源, 对应原 `importSelect(finally)`, 直接转发到 [shared.importSelect]。
     *
     * @param finally 导入完成回调 (无论成功/失败均触发)
     */
    fun importSelect(finally: () -> Unit) {
        shared.importSelect(finally)
    }

    /**
     * 导入书源, 对应原 `importSource(text)`。
     *
     * # Uri 分支预处理 (Android 专属)
     *
     * 原 `importSource(text)` 内的 `mText.isUri()` + `mText.toUri().inputStream(context)`
     * 是 Android ContentResolver 专属, commonMain 不可用。本方法先检查 isUri, 若是 Uri
     * 则先读取文本再转发到 [shared.importSource]; 否则直接转发到 [shared.importSource]。
     *
     * - isUri: 用 `execute { ... }` (IO 协程) 读 Uri 文本,
     *   `mText.toUri().inputStream(context).use { it.readBytes().toString(Charsets.UTF_8) }`
     *   读文件后调 [shared.importSource] (与原 `importFromJson(it.readBytes().toString(Charsets.UTF_8))` 等价,
     *   但走 shared 的完整分支判断, 行为一致);
     *   Uri 读取错误走 `onError { errorLiveData.postValue(...) + AppLog.put(...) }`
     *   (与 shared 内部错误处理路径一致, 调用方 observe 行为不变);
     * - 非 Uri: 直接 `shared.importSource(text)`, shared 内部走 isAbsUrl / isJsonObject /
     *   isJsonArray / sourceUrls 等分支。
     *
     * @param text 输入文本 (URL / JSON / Uri / sourceUrls JSON)
     */
    fun importSource(text: String) {
        val mText = text.trim()
        if (mText.isUri()) {
            // Uri 分支: 在 IO 协程读取文本 (Uri.inputStream 是 IO 操作, 不能在主线程),
            // 读完后转发到 shared.importSource (shared 内部会再启动协程处理 JSON 解析)
            execute {
                mText.toUri().inputStream(context).getOrThrow().use {
                    shared.importSource(it.readBytes().toString(Charsets.UTF_8))
                }
            }.onError {
                // Uri 读取错误直接推送到 errorLiveData (与 shared 内部错误路径一致)
                errorLiveData.postValue("ImportError:${it.localizedMessage}")
                AppLog.put("ImportError:${it.localizedMessage}", it)
            }
        } else {
            // 非 Uri 分支: 直接转发, shared 内部走 isAbsUrl / isJsonObject / isJsonArray 等
            shared.importSource(text)
        }
    }
}
