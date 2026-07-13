package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.utils.inputStream
import io.legado.app.utils.parseJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

abstract class BaseAssociationViewModel(application: Application) : BaseViewModel(application) {

    val successLive = MutableLiveData<Pair<String, String>>()
    val errorLive = MutableLiveData<String>()

    fun importJson(uri: Uri) {
        //只读取一次流, 避免旧版二次打开流的浪费
        val text = uri.inputStream(context).getOrThrow().use {
            it.bufferedReader().readText()
        }
        val jsonElement = try {
            parseJsonElement(text)
        } catch (e: Exception) {
            errorLive.postValue("格式不对")
            return
        }
        //先尝试数组格式取首个元素, 失败则当作对象处理 (模拟旧版 jayway SUPPRESS_EXCEPTIONS 行为)
        val map = (jsonElement as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?: jsonElement as? JsonObject
            ?: run {
                errorLive.postValue("格式不对")
                return
            }

        when {
            map.containsKey("bookSourceUrl") ->
                successLive.postValue("bookSource" to uri.toString())

            map.containsKey("sourceUrl") ->
                successLive.postValue("rssSource" to uri.toString())

            map.containsKey("pattern") ->
                successLive.postValue("replaceRule" to uri.toString())

            map.containsKey("themeName") ->
                successLive.postValue("theme" to uri.toString())

            map.containsKey("showRule") ->
                successLive.postValue("dictRule" to uri.toString())

            //TxtTocRule 含 name+rule 字段
            map.containsKey("name") && map.containsKey("rule") ->
                successLive.postValue("txtRule" to uri.toString())

            //HttpTTS 含 name+url 字段
            map.containsKey("name") && map.containsKey("url") ->
                successLive.postValue("httpTts" to uri.toString())

            else -> errorLive.postValue("格式不对")
        }
    }

}
