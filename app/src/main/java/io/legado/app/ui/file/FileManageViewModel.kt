package io.legado.app.ui.file

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.utils.toastOnUi
import java.io.File

class FileManageViewModel(application: Application) : BaseViewModel(application) {

    var sort = 0
    val rootDoc = context.getExternalFilesDir(null)?.parentFile
    val subDocs = mutableListOf<File>()
    val filesLiveData = MutableLiveData<List<File>>()

    val lastDir: File? get() = subDocs.lastOrNull() ?: rootDoc

    fun upFiles(parentFile: File?) {
        execute {
            parentFile ?: return@execute emptyList()
            val comparator = when (sort) {
                1 -> compareBy { it.length() }
                2 -> compareBy<File> { it.lastModified() }
                else -> compareBy { it.name.lowercase() }
            }
            if (parentFile == rootDoc) {
                parentFile.listFiles()?.sortedWith(
                    compareBy<File> { it.isFile }.then(comparator)
                ) ?: emptyList()
            } else {
                val list = arrayListOf(parentFile)
                parentFile.listFiles()?.sortedWith(
                    compareBy<File> { it.isFile }.then(comparator)
                )?.let {
                    list.addAll(it)
                }
                list
            }
        }.onSuccess {
            filesLiveData.postValue(it)
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }
    }

    fun delFiles(files: List<File>, onSuccess: () -> Unit) {
        execute {
            files.forEach { it.delete() }
        }.onSuccess {
            onSuccess.invoke()
            upFiles(lastDir)
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }
    }

}
