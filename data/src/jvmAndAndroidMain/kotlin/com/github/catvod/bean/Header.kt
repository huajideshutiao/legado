package com.github.catvod.bean

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.util.Collections

/** TVBox 壳防挂头模型 (签名对齐 FongMi catvod 模块 bean.Header)。 */
class Header {

    @field:SerializedName("host")
    private var host: String? = null

    @field:SerializedName("header")
    private var header: JsonElement? = null

    fun getHost(): String = host ?: ""

    fun getHeader(): JsonElement? = header

    companion object {

        @JvmStatic
        fun arrayFrom(element: JsonElement?): List<Header> = try {
            val listType: Type = TypeToken.getParameterized(List::class.java, Header::class.java).type
            val items = Gson().fromJson<List<Header>>(element, listType)
            items ?: Collections.emptyList()
        } catch (e: Exception) {
            Collections.emptyList()
        }
    }
}
