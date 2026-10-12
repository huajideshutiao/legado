package io.legado.app.ui.association

import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.storage.RuleSubAutoUpdateHook
import io.legado.app.help.storage.RuleSubAutoUpdateHooks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * [RuleSubAutoUpdateHook] 的 :ui 实现: 复用与手动打开订阅**同一条**导入链。
 *
 * 每类订阅都调对应 `Import*ViewModelShared` 的 `preImportAwait` (手动导入 `importSource`
 * 内部走的就是它), 因而解析口径 (旧订阅源转换 / 顶层键判定 / `ReplaceAnalyzer` 的旧字段映射与
 * `isValid()` 过滤 / 非空校验) 与"需要导入"判据 (`defaultChecked`, 与列表里"新增/更新/已有"
 * 标记同源) 都与手动链一致, 不存在第二份实现。
 *
 * 载荷由 [io.legado.app.help.storage.RuleSubAutoUpdate] 下载后原样传入 (地址已按
 * `#requestWithoutUA` 口径处理), 故这里只做解析与入库。
 */
class UiRuleSubAutoUpdateHook(
    private val scope: CoroutineScope,
) : RuleSubAutoUpdateHook {

    /** 不传作用域时自建 (注册期无现成作用域可用, 同各端 XxxCheckSource 的做法)。 */
    constructor() : this(CoroutineScope(SupervisorJob() + IoDispatcher))

    override suspend fun importByType(type: Int, text: String): Int {
        val vm = createImportVm(type) ?: throw IllegalArgumentException("未知订阅类型: $type")
        // 解析失败的原因由 VM 的 errorState 出口记录, 这里只需让调用方知道本轮失败
        vm.parseAwait(text)
        val checked = vm.defaultChecked
        if (checked.none { it }) return 0
        // 等写库完成再返回, 否则调用方会在入库还在跑时就结束本轮
        val done = CompletableDeferred<Unit>()
        vm.importSelect(checked) { done.complete(Unit) }
        done.await()
        return checked.count { it }
    }

    /** 订阅类型 → 对应的导入链 (与手动打开订阅同一映射)。 */
    private fun createImportVm(type: Int): RuleSubImportVm? = when (type) {
        0, 1 -> BookSourceImportVm(ImportBookSourceViewModelShared(scope))
        2 -> ReplaceRuleImportVm(ImportReplaceRuleViewModelShared(scope))
        3 -> TxtTocRuleImportVm(ImportTxtTocRuleViewModelShared(scope))
        4 -> DictRuleImportVm(ImportDictRuleViewModelShared(scope))
        5 -> HttpTtsImportVm(ImportHttpTtsViewModelShared(scope))
        else -> null
    }
}

/** 一条订阅的导入链句柄: 解析与比对 + 勾选入库。 */
private interface RuleSubImportVm {

    /** 解析载荷并与本地库比对, 算好 [defaultChecked] 并投递成功/失败信号。 */
    suspend fun parseAwait(text: String)

    /** 与列表里"新增/更新/已有"标记同源的默认勾选。 */
    val defaultChecked: List<Boolean>

    fun importSelect(checked: List<Boolean>, finally: () -> Unit)
}

/** 书源链 (含旧订阅源转换与 `{"sourceUrls":[...]}` 多地址载荷)。 */
private class BookSourceImportVm(
    private val vm: ImportBookSourceViewModelShared,
) : RuleSubImportVm {

    override suspend fun parseAwait(text: String) = vm.preImportAwait(text)

    override val defaultChecked: List<Boolean> get() = vm.defaultChecked

    override fun importSelect(checked: List<Boolean>, finally: () -> Unit) =
        vm.importSelect(checked, finally)
}

/** 替换规则链 (含 `ReplaceAnalyzer` 的旧字段映射与 `isValid()` 过滤)。 */
private class ReplaceRuleImportVm(
    private val vm: ImportReplaceRuleViewModelShared,
) : RuleSubImportVm {

    override suspend fun parseAwait(text: String) = vm.preImportAwait(text)

    override val defaultChecked: List<Boolean> get() = vm.defaultChecked

    override fun importSelect(checked: List<Boolean>, finally: () -> Unit) =
        vm.importSelect(checked, finally)
}

/** TXT 目录规则链。 */
private class TxtTocRuleImportVm(
    private val vm: ImportTxtTocRuleViewModelShared,
) : RuleSubImportVm {

    override suspend fun parseAwait(text: String) = vm.preImportAwait(text)

    override val defaultChecked: List<Boolean> get() = vm.defaultChecked

    override fun importSelect(checked: List<Boolean>, finally: () -> Unit) =
        vm.importSelect(checked, finally)
}

/** 字典规则链。 */
private class DictRuleImportVm(
    private val vm: ImportDictRuleViewModelShared,
) : RuleSubImportVm {

    override suspend fun parseAwait(text: String) = vm.preImportAwait(text)

    override val defaultChecked: List<Boolean> get() = vm.defaultChecked

    override fun importSelect(checked: List<Boolean>, finally: () -> Unit) =
        vm.importSelect(checked, finally)
}

/** 语音源链。 */
private class HttpTtsImportVm(
    private val vm: ImportHttpTtsViewModelShared,
) : RuleSubImportVm {

    override suspend fun parseAwait(text: String) = vm.preImportAwait(text)

    override val defaultChecked: List<Boolean> get() = vm.defaultChecked

    override fun importSelect(checked: List<Boolean>, finally: () -> Unit) =
        vm.importSelect(checked, finally)
}

/** 注册本实现 (各端宿主启动早期调用一次, 须早于任何自动更新触发)。 */
fun registerUiRuleSubAutoUpdateHook() {
    RuleSubAutoUpdateHooks.register(UiRuleSubAutoUpdateHook())
}

/**
 * 订阅类型 → deep link 导入类型, 未知类型返回 null。
 *
 * 与手动打开订阅 ([io.legado.app.ui.route.RuleSubRoute]) 同一映射, 避免两处各写一份。
 */
internal fun Int.toDeepLinkImportTypeOrNull(): DeepLinkImportType? = when (this) {
    0, 1 -> DeepLinkImportType.BOOK_SOURCE
    2 -> DeepLinkImportType.REPLACE_RULE
    3 -> DeepLinkImportType.TXT_TOC_RULE
    4 -> DeepLinkImportType.DICT_RULE
    5 -> DeepLinkImportType.HTTP_TTS
    else -> null
}
