package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.utils.inputStream

abstract class BaseAssociationViewModel(application: Application) : BaseViewModel(application) {

    val successLive = MutableLiveData<Pair<String, String>>()
    val errorLive = MutableLiveData<String>()

    fun importJson(uri: Uri) {
        //只读取一次流, 避免旧版二次打开流的浪费
        val text = uri.inputStream(context).getOrThrow().use {
            it.bufferedReader().readText()
        }
        //JSON 类型判断已下沉至 commonMain (JsonTypeDetector.kt 的 detectJsonType),
        //此处仅做平台专属的 LiveData 推送, 逻辑未变。
        val typeStr = when (detectJsonType(text)) {
            JsonType.BOOK_SOURCE -> "bookSource"
            JsonType.RSS_SOURCE -> "rssSource"
            JsonType.REPLACE_RULE -> "replaceRule"
            JsonType.THEME -> "theme"
            JsonType.DICT_RULE -> "dictRule"
            JsonType.TXT_RULE -> "txtRule"
            JsonType.HTTP_TTS -> "httpTts"
            null -> {
                errorLive.postValue("格式不对")
                return
            }
        }
        successLive.postValue(typeStr to uri.toString())
    }

}
