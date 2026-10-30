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
 * 注册不再创建会话, 可在任意时机调用。
 */
fun BaseComposeActivity.registerHandleFile(
    callback: (HandleFileContract.Result) -> Unit,
    key: String? = null,
    /**
     * 该入口的未完请求是否要跨进程恢复。
     *
     * 绑定在业务请求 (Job) 上的入口 (如选书籍目录: 等待方是页面请求协程) 必须传 false ——
     * 进程被杀后那个 Job 已不存在, 恢复出来的请求没有消费者, 回执只能一直挂着;
     * 宿主级稳定入口 (主界面的导入/图片选择等) 保持默认 true。
     */
    persistAcrossProcess: Boolean = key != null,
): HandleFileLauncher {
    val launcher = HandleFileLauncher(handleFileSession, callback, key, persistAcrossProcess)
    // 重建后仍有效的入口 (同一宿主上的回调) 凭稳定 key 重新绑定原请求
    if (key != null) handleFileSession.bindKey(key, launcher)
    return launcher
}

class HandleFileLauncher(
    private val session: HandleFileSession,
    internal val callback: (HandleFileContract.Result) -> Unit,
    /** 重建后仍有效的入口用稳定 key 重绑原请求; 页面级入口传 null (重建即作废)。 */
    val key: String? = null,
    /** 未完请求是否跨进程恢复 (见 registerHandleFile 同名参数)。 */
    internal val persistAcrossProcess: Boolean = key != null,
) {

    /**
     * 消费资格已撤 (任意线程可置): 之后系统回传不再取持久授权、不再交付回执。
     *
     * 撤销必须**同步**到这里 —— 只把 UI 清理排到主线程是不够的: SDK 回执可能已排在
     * 清理之前, 那时仍会 takePersistableUriPermission 并把结果交给已经不要它的等待者。
     * 已起跑的系统调用占用不因此提前释放, 仍保持到真实回传。
     */
    @Volatile
    var revoked = false
        private set

    fun launch(input: (HandleFileContract.HandleFileParam.() -> Unit)? = null) {
        val param = HandleFileContract.HandleFileParam()
        input?.invoke(param)
        if (param.mode == HandleFileContract.IMAGE && param.allowExtensions.isEmpty()) {
            param.allowExtensions = arrayOf("jpg", "png", "bmp", "webp")
        }
        session.begin(this, param, callback)
    }

    /** 同步撤消费资格 (任意线程; 幂等)。 */
    fun revoke() {
        revoked = true
    }

    /** 取消该入口发起的未完成请求: 撤消费资格 + 关弹窗弃回调 (主线程调用)。 */
    fun cancel() {
        revoke()
        session.cancel(this)
    }
}
