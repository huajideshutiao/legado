package io.legado.app.ui.file

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.help.DirectLinkUpload
import io.legado.app.utils.FileUtils
import io.legado.app.utils.printOnDebug

class HandleFileViewModel(application: Application) : BaseViewModel(application) {

    val errorLiveData = MutableLiveData<String>()

    fun upload(
        fileName: String,
        file: Any,
        contentType: String,
        success: (url: String) -> Unit
    ) {
        execute {
            DirectLinkUpload.upLoad(fileName, file, contentType)
        }.onSuccess {
            success.invoke(it)
        }.onError {
            AppLog.put("上传文件失败\n${it.localizedMessage}", it)
            it.printOnDebug()
            errorLiveData.postValue(it.localizedMessage)
        }
    }

    fun saveToLocal(uri: Uri, fileName: String, data: Any, success: (uri: Uri) -> Unit) {
        execute {
            FileUtils.exportFile(uri, fileName, data)
        }.onError {
            it.printOnDebug()
            errorLiveData.postValue(it.localizedMessage)
        }.onSuccess {
            success.invoke(it)
        }
    }

}