package io.legado.app.ui.file

import android.net.Uri
import io.legado.app.base.BaseComposeActivity
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
        var value: String? = null
    )

    data class Result(
        val uri: Uri?,
        val requestCode: Int,
        val value: String?
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
): HandleFileLauncher {
    return HandleFileLauncher(handleFileSession, callback)
}

class HandleFileLauncher(
    private val session: HandleFileSession,
    private val callback: (HandleFileContract.Result) -> Unit,
) {

    fun launch(input: (HandleFileContract.HandleFileParam.() -> Unit)? = null) {
        val param = HandleFileContract.HandleFileParam()
        input?.invoke(param)
        if (param.mode == HandleFileContract.IMAGE && param.allowExtensions.isEmpty()) {
            param.allowExtensions = arrayOf("jpg", "png", "bmp", "webp")
        }
        session.begin(this, param, callback)
    }

    /** 取消该入口发起的未完成请求: 关弹窗、弃回调; 在途系统调用的通道占用保持到真实回传。 */
    fun cancel() {
        session.cancel(this)
    }
}
