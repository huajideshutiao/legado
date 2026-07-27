package io.legado.app.ui.association

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.isUri
import io.legado.app.utils.readText
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * 导入替换规则 VM (Android 端, 组合委托)。
 *
 * # KMP 化重构说明
 *
 * 核心业务逻辑 (importSelect / import / importAwait / importUrl / comparisonSource)
 * 已下沉到 shared commonMain [ImportReplaceRuleViewModelShared], 用
 * `MutableStateFlow` 替代 `MutableLiveData` (LiveData 不可 KMP)。
 *
 * 本类采用**组合委托**模式持有 [shared] 实例, 不通过继承 [ImportReplaceRuleViewModelShared]:
 * - 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 *   `viewModelScope`), Kotlin 单继承无法同时继承 [ImportReplaceRuleViewModelShared];
 * - 通过构造函数注入 [viewModelScope] 到 [shared], 全部业务方法转发到 [shared]。
 *
 * # Android 专属依赖移除
 *
 * - **Uri 读取留本类**: `text.isUri()` + `text.toUri().readText(appCtx)` 是
 *   Android ContentResolver 专属, commonMain 不可用。本类 [import] 先判断 isUri,
 *   若是 Uri 则先读取文本再转发到 [shared.import]; 否则直接转发。
 *   URL/JSON 解析逻辑全部下沉到 [shared]。
 * - **errorLiveData / successLiveData**: 保留原 MutableLiveData, 在 init 块内
 *   `viewModelScope.launch { collect { postValue } }` 桥接 [shared.errorState] /
 *   [shared.successState], 调用方 `observe` 用法不变 (对照
 *   [ImportBookSourceViewModel] 的 LiveData 桥接模式)。
 * - **allRules / checkRules / selectStatus 等 MutableList**: 直接 getter 转发到
 *   [shared] 同实例引用, app 端 `selectStatus[index] = checked` 与 shared 端读取一致;
 * - **isAddGroup / groupName**: 通过 var getter/setter 委托 [shared] (修改同步反映到 shared)。
 *
 * # 调用方兼容
 *
 * 调用方式保持不变:
 * - `viewModel.errorLiveData.observe(...)` / `viewModel.successLiveData.observe(...)`;
 * - `viewModel.allRules[it]` / `viewModel.checkRules[it]` / `viewModel.selectStatus[it]`
 *   (getter 转发);
 * - `viewModel.isSelectAll` / `viewModel.selectCount` (getter 转发);
 * - `viewModel.isAddGroup = ...` / `viewModel.groupName = ...` (setter 转发);
 * - `viewModel.import(text)` / `viewModel.importSelect { ... }` (方法转发, 注意入口方法名是 `import`)。
 */
class ImportReplaceRuleViewModel(app: Application) : BaseViewModel(app) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope] 供 shared 内部协程使用。
     *
     * - shared 承担状态托管 (StateFlow) + 全部业务方法;
     * - 本类仅作 LiveData 桥接 + Uri 分支预处理。
     */
    private val shared: ImportReplaceRuleViewModelShared = ImportReplaceRuleViewModelShared(
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

    /** 解析出的待导入替换规则列表, 转发到 [shared.allRules] (同实例引用)。 */
    val allRules: ArrayList<ReplaceRule> get() = shared.allRules

    /** 已有替换规则, 转发到 [shared.checkRules] (同实例引用)。 */
    val checkRules: ArrayList<ReplaceRule?> get() = shared.checkRules

    /** 每个 allRules 元素是否被选中导入, 转发到 [shared.selectStatus] (同实例引用)。 */
    val selectStatus: ArrayList<Boolean> get() = shared.selectStatus

    /** 是否全部选中, 转发到 [shared.isSelectAll]。 */
    val isSelectAll: Boolean get() = shared.isSelectAll

    /** 选中数量, 转发到 [shared.selectCount]。 */
    val selectCount: Int get() = shared.selectCount
    // endregion

    /**
     * 导入选中的替换规则, 对应原 `importSelect(finally)`, 直接转发到 [shared.importSelect]。
     *
     * @param finally 导入完成回调 (无论成功/失败均触发)
     */
    fun importSelect(finally: () -> Unit) {
        shared.importSelect(finally)
    }

    /**
     * 导入替换规则, 对应原 `import(text)` (注意方法名是 `import` 不是 `importSource`)。
     *
     * # Uri 分支预处理 (Android 专属)
     *
     * 原 `importAwait(text)` 内的 `text.isUri()` + `text.toUri().readText(appCtx)`
     * 是 Android ContentResolver 专属, commonMain 不可用。本方法先检查 isUri, 若是 Uri
     * 则先读取文本再转发到 [shared.import]; 否则直接转发到 [shared.import]。
     *
     * - isUri: 用 `execute { ... }` (IO 协程) 读 Uri 文本,
     *   `text.toUri().readText(appCtx)` 读文件后调 [shared.import]
     *   (shared 内部会再启动协程处理 JSON 解析);
     *   Uri 读取错误走 `onError { errorLiveData.postValue(...) + AppLog.put(...) }`
     *   (与 shared 内部错误处理路径一致, 调用方 observe 行为不变);
     * - 非 Uri: 直接 `shared.import(text)`, shared 内部走 isAbsUrl / isJsonObject /
     *   isJsonArray 等分支。
     *
     * @param text 输入文本 (URL / JSON / Uri)
     */
    fun import(text: String) {
        val mText = text.trim()
        if (mText.isUri()) {
            // Uri 分支: 在 IO 协程读取文本 (Uri.readText 是 IO 操作, 不能在主线程),
            // 读完后转发到 shared.import (shared 内部会再启动协程处理 JSON 解析)
            execute {
                shared.import(mText.toUri().readText(appCtx))
            }.onError {
                // Uri 读取错误直接推送到 errorLiveData (与 shared 内部错误路径一致)
                errorLiveData.postValue("ImportError:${it.localizedMessage}")
                AppLog.put("ImportError:${it.localizedMessage}", it)
            }
        } else {
            // 非 Uri 分支: 直接转发, shared 内部走 isAbsUrl / isJsonObject / isJsonArray 等
            shared.import(text)
        }
    }
}
