package io.legado.app.ui.file

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
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
 * 注册一个文件/目录选择入口。[key] 必须跨界面重建稳定 (同一注册点重建前后传同一 key),
 * 系统选择器的待回传结果由注册表按 key 重投, 重建后只要在 Activity 构造期重新注册,
 * 重建前拉起的系统选择器结果就不会丢。
 */
fun AppCompatActivity.registerHandleFile(
    key: String,
    callback: (HandleFileContract.Result) -> Unit,
): HandleFileLauncher {
    // 立即 bind (非惰性): 界面重建后调用方在构造期重新注册, 借此驱动会话重挂/弹窗恢复
    val session = HandleFileSession.of(this).also { it.bind(this, key, callback) }
    return HandleFileLauncher(this, session, key)
}

class HandleFileLauncher(
    private val activity: AppCompatActivity,
    private val session: HandleFileSession,
    private val key: String,
) {

    fun launch(input: (HandleFileContract.HandleFileParam.() -> Unit)? = null) {
        val param = HandleFileContract.HandleFileParam()
        input?.invoke(param)
        if (param.mode == HandleFileContract.IMAGE && param.allowExtensions.isEmpty()) {
            param.allowExtensions = arrayOf("jpg", "png", "bmp", "webp")
        }
        session.begin(activity, key, param)
    }
}
