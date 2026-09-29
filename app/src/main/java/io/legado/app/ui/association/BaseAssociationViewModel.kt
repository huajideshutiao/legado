package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.utils.inputStream

abstract class BaseAssociationViewModel(application: Application) : BaseViewModel(application) {

    /** 导入成功信号: 已识别的导入类型 + 已读出的源文本 (JSON 全文, 供导入 VM 直接解析)。 */
    val successLive = MutableLiveData<Pair<DeepLinkImportType, String>>()
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
        // 源文本直接随信号传递: 导入 VM 只接受 URL/JSON 文本, 再传 Uri 会被当纯文本判成格式不对
        successLive.postValue(type to text)
    }

}
