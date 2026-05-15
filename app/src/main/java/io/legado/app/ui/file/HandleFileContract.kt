package io.legado.app.ui.file

import android.net.Uri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import io.legado.app.lib.dialogs.SelectItem

class HandleFileContract {

    companion object {
        const val DIR = 0
        const val FILE = 1
        const val DIR_SYS = 2
        const val EXPORT = 3
        const val IMAGE = 4
    }

    @Suppress("ArrayInDataClass")
    data class HandleFileParam(
        var mode: Int = DIR,
        var title: String? = null,
        var allowExtensions: Array<String> = arrayOf(),
        var otherActions: ArrayList<SelectItem<Int>>? = null,
        var fileData: FileData? = null,
        var requestCode: Int = 0,
        var value: String? = null
    )

    data class Result(
        val uri: Uri?,
        val requestCode: Int,
        val value: String?
    )

    data class FileData(
        val name: String,
        val data: Any,
        val type: String
    ) : java.io.Serializable

}

fun FragmentActivity.registerHandleFile(callback: (HandleFileContract.Result) -> Unit): HandleFileLauncher {
    return HandleFileLauncher(this, supportFragmentManager, callback)
}

fun Fragment.registerHandleFile(callback: (HandleFileContract.Result) -> Unit): HandleFileLauncher {
    return HandleFileLauncher(this, childFragmentManager, callback)
}

class HandleFileLauncher(
    private val lifecycleOwner: LifecycleOwner,
    private val fragmentManager: FragmentManager,
    private val callback: (HandleFileContract.Result) -> Unit
) {
    fun launch(input: (HandleFileContract.HandleFileParam.() -> Unit)? = null) {
        val param = HandleFileContract.HandleFileParam()
        input?.invoke(param)
        if (param.mode == HandleFileContract.IMAGE && param.allowExtensions.isEmpty()) {
            param.allowExtensions = arrayOf("jpg", "png", "bmp", "webp")
        }
        fragmentManager.setFragmentResultListener("handleFile", lifecycleOwner) { _, bundle ->
            val result = HandleFileContract.Result(
                bundle.getParcelable("result"),
                bundle.getInt("requestCode"),
                bundle.getString("value")
            )
            callback(result)
        }
        HandleFileDialog.show(
            fragmentManager,
            param.mode,
            param.title,
            param.allowExtensions,
            param.otherActions,
            param.fileData,
            param.value,
            param.requestCode
        )
    }
}
