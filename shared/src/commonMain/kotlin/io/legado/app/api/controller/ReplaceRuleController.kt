package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.GSON
import io.legado.app.utils.RegexReplacers
import io.legado.app.utils.decodeAnyMapOrNull
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toJson
import io.legado.app.utils.toJsonElement

object ReplaceRuleController {

    suspend fun allRules(): ReturnData {
        val rules = AppDbProviders.get().replaceRuleDao.all()
        val returnData = ReturnData()
        returnData.setData(GSON.toJson(rules))
        return returnData
    }


    suspend fun saveRule(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val rule = GSON.fromJsonObject<ReplaceRule>(postData).getOrNull()
        if (rule == null) {
            returnData.setErrorMsg("格式不对")
        } else {
            if (rule.order == Int.MIN_VALUE) {
                rule.order = AppDbProviders.get().replaceRuleDao.maxOrder() + 1
            }
            AppDbProviders.get().replaceRuleDao.insert(rule)
        }
        return returnData
    }


    suspend fun delete(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val rule = GSON.fromJsonObject<ReplaceRule>(postData).getOrNull()
        if (rule == null) {
            returnData.setErrorMsg("格式不对")
        } else {
            AppDbProviders.get().replaceRuleDao.delete(rule)
        }
        return returnData
    }

    /**
     * 传入测试数据格式
     * {
     *  rule: Replace,
     *  text: "xxx"
     * }
     */
    fun testRule(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        // Phase D: GSON.fromJsonObject<Map<String, *>>(postData) → decodeAnyMapOrNull (AnyMapSerializer 处理通配符值类型)
        val map = decodeAnyMapOrNull(postData)
        if (map == null) {
            returnData.setErrorMsg("格式不对")
        } else {
            val rule = map["rule"]?.let {
                if (it is String) {
                    GSON.fromJsonObject<ReplaceRule>(it).getOrNull()
                } else {
                    // Phase D: GSON.toJson(it) 反射序列化 Any → toJsonElement().toString() 处理任意类型
                    GSON.fromJsonObject<ReplaceRule>(it.toJsonElement().toString()).getOrNull()
                }
            }
            if (rule == null) {
                returnData.setErrorMsg("格式不对")
                return returnData
            }
            if (rule.pattern.isEmpty()) {
                returnData.setErrorMsg("替换规则不能为空")
            }
            val text = map["text"] as String
            val content = try {
                if (rule.isRegex) {
                    // CharSequence.replace(Regex, String, Long) 下沉为 RegexReplacers.get().replace(...)
                    // (app 端扩展依赖 Coroutine.async/JsEngines 无法直接下沉 commonMain)
                    RegexReplacers.get().replace(
                        text,
                        rule.pattern.toRegex(),
                        rule.replacement,
                        rule.getValidTimeoutMillisecond()
                    )
                } else {
                    text.replace(rule.pattern, rule.replacement)
                }
            } catch (e: Exception) {
                e.stackTraceStr
            }
            returnData.setData(content)
        }
        return returnData
    }

}
