package io.legado.app.ui.root

/**
 * 导入链上的一次用户选择阶段 (下载项候选 / 压缩包内条目 / 不支持格式确认)。
 *
 * 阶段对象归**发起请求的页面模型**所有 ([ImportStageOwner] 由模型持有), 不归窗口或 Activity:
 * 宿主重建时组合只解绑重绑, 请求与阶段都还在, 新组合读到同一阶段就重显; 请求真撤时阶段随请求
 * 收掉, 已结算过的阶段不会再出现。与 `FileAssociationImport.ImportUiStage` 的 attemptId 归属同一套
 * 思路 (哪次尝试发起的, 结果与收口就归哪次), 但呈现对象放在共享页面模型上, 四端共用同一套 Compose
 * 对话框, 不再各端各弹一套原生框。
 */
sealed class ImportStage {
    /** 本阶段唯一编号: 只有当前编号的结算有效, 旧界面/迟到回执一律不作数。 */
    abstract val stageId: Long

    /** 候选列表 (下载项 / 压缩包条目): [items] 为可选文案。 */
    data class Choice(
        override val stageId: Long,
        val title: String,
        val items: List<String>,
    ) : ImportStage()

    /**
     * 确认框: [neutralText] 非空时多一个"中性"出口 (不支持格式的"仍要打开");
     * 否时只有确定/取消。
     */
    data class Confirm(
        override val stageId: Long,
        val title: String?,
        val message: String,
        val neutralText: String?,
        val okText: String? = null,
    ) : ImportStage()
}

/** 确认框的三种终态 (对照 alert 的 okButton / neutralButton / 取消)。 */
enum class ImportConfirmAction { Ok, Neutral, Cancel }

/**
 * 请求侧取用"用户选择阶段"的入口 (页面模型实现)。
 *
 * 两个方法都挂起到真回执: 返回即拿到结果 (取消/返回 = null 或 [ImportConfirmAction.Cancel]),
 * 请求据此决定继续还是收口 —— 不靠"界面是否还在"推断, 也不发一次就算完 (那正是原先
 * 目录请求 replay=0 丢事件、框一关请求就没人管的来源)。
 */
interface ImportStageHost {

    /** 候选列表; 返回选中下标, null = 用户取消/返回。 */
    suspend fun choose(title: String, items: List<String>): Int?

    /** 确认框; [neutralText] 非空时多一个中性出口。 */
    suspend fun confirm(
        title: String?,
        message: String,
        neutralText: String? = null,
        okText: String? = null,
    ): ImportConfirmAction
}
/**
 * 未知文件确认 (对照各端 `confirmUnknownFileImport`): 是否按书籍文件尝试导入。
 *
 * 确认 = true; 取消/关闭 = false。文案由调用端给 (各端字符串表不同, 共享层不猜资源键)。
 */
suspend fun ImportStageHost.confirmUnknownFile(
    title: String,
    message: String,
    okText: String,
): Boolean = confirm(title = title, message = message, okText = okText) == ImportConfirmAction.Ok
