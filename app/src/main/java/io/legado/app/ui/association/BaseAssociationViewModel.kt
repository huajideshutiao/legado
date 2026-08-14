package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.utils.inputStream

abstract class BaseAssociationViewModel(application: Application) : BaseViewModel(application) {

    /** 导入成功信号: 已识别的导入类型 + 源 (Uri 或纯 JSON 文本)。 */
    val successLive = MutableLiveData<Pair<DeepLinkImportType, Uri>>()
    val errorLive = MutableLiveData<String>()

    fun importJson(uri: Uri) {
        //只读取一次流, 避免旧版二次打开流的浪费
        val text = uri.inputStream(context).getOrThrow().use {
            it.bufferedReader().readText()
        }
        //JSON 类型判断已下沉至 commonMain (JsonTypeDetector.kt 的 detectJsonType),
        //JsonType→DeepLinkImportType 映射同样复用 shared (SchemeImportOps.toDeepLinkImportType),
        //不再在本端维护第二份 when 映射 (原 handleSuccess 的 String 映射一并删除)。
        val type = detectJsonType(text)?.toDeepLinkImportType()
        if (type == null) {
            errorLive.postValue("格式不对")
            return
        }
        successLive.postValue(type to uri)
    }

}
