package io.legado.app.ui.file

import android.net.Uri
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.entities.Book
import io.legado.app.lib.dialogs.SelectItem

class HandleFileContract {

    companion object {
        const val DIR = 0
        const val FILE = 1
        const val DIR_SYS = 2
        const val IMAGE = 4
    }

    @Suppress("ArrayInDataClass")
    data class HandleFileParam(
        var mode: Int = DIR,
        var title: String? = null,
        var allowExtensions: Array<String> = arrayOf(),
        var otherActions: ArrayList<SelectItem<Int>>? = null,
        var requestCode: Int = 0,
        var value: String? = null,
        /** 发起时绑定的书籍 (图片保存等需要后续 IO 的操作按发起时快照处理)。 */
        var book: Book? = null,
    )

    data class Result(
        val uri: Uri?,
        val requestCode: Int,
        val value: String?,
        val book: Book? = null,
        /** 发起时绑定书籍的地址 (重建后按它取回同一本书的快照)。 */
        val bookUrl: String? = null,
        /** 非取消的真实失败 (权限启动/系统授权持久化失败); null 表示正常选择/用户取消。 */
        val error: Throwable? = null,
    )

}

/**
 * 注册一个文件/目录选择入口: 返回的入口只发起请求, 每次启动都是一个独立请求
 * (参数/弹窗/回调归属该次), 取消经 [HandleFileLauncher.cancel]。
 *
 * 会话由宿主在 onCreate 内 (attach 后、STARTED 前) 随宿主建立, 系统选择器通道随之登记,
 * 注册不再创建会话, 可在任意时机调用。重建后仍有效的入口凭稳定 key 在新宿主重新绑定原请求
 * (请求资格/业务终态/暂存回执的权威在宿主 ViewModel 的纯状态上)。
 */
fun BaseComposeActivity.registerHandleFile(
    callback: (HandleFileContract.Result) -> Unit,
    key: String? = null,
    request: HandleFileRequestHost.RequestRecord? = null,
): HandleFileLauncher {
    val launcher = HandleFileLauncher(handleFileSession, callback, key, request)
    // 重建后仍有效的入口 (同一宿主上的回调) 凭稳定 key 重新绑定原请求
    if (key != null) handleFileSession.bindKey(key, launcher)
    return launcher
}

class HandleFileLauncher(
    private val session: HandleFileSession,
    internal val callback: (HandleFileContract.Result) -> Unit,
    /** 重建后仍有效的入口用稳定 key 重绑原请求; 页面级入口传 null (重建即作废)。 */
    val key: String? = null,
    /** 发起时已建立的业务请求记录: launch 只认领这份资格, 参数以记录为准 (不再接受输入)。 */
    internal val attachedRecord: HandleFileRequestHost.RequestRecord? = null,
) {

    fun launch(input: (HandleFileContract.HandleFileParam.() -> Unit)? = null) {
        val param = HandleFileContract.HandleFileParam()
        input?.invoke(param)
        if (param.mode == HandleFileContract.IMAGE && param.allowExtensions.isEmpty()) {
            param.allowExtensions = arrayOf("jpg", "png", "bmp", "webp")
        }
        session.begin(this, param, callback)
    }

    /**
     * 同步终结本入口名下请求的消费资格 (任意线程; 幂等)。
     *
     * 资格终结写在宿主 ViewModel 的请求记录上 (入口与宿主都会随重建更换, 记录不随它们更换):
     * 之后系统回传不再取持久授权、不再交付, 持有该请求的每个会话经资格收集自行关闭UI。
     * 已起跑的系统调用占用不因此提前释放, 仍保持到真实回传。
     */
    fun revoke() {
        session.revokeConsumption(this)
    }

    /** 取消该入口发起的未完成请求: 撤消费资格 + 关弹窗弃回调 (主线程调用)。 */
    fun cancel() {
        revoke()
        session.cancel(this)
    }
}
