package io.legado.app.help.tts

import io.legado.app.constant.AppConst
import io.legado.app.utils.MD5Utils

/**
 * HttpTTS 请求的纯参数拼装/响应校验/缓存 key/熔断计数,从 HttpReadAloudService 平移。
 * 网络执行(AnalyzeUrl)、JS 求值与文件 IO 留在 Service。
 */
object HttpTtsRequest {

    /** 网络朗读源读取超时(毫秒) */
    const val READ_TIMEOUT_MS = 300 * 1000L

    /** 注入 url 模板 JS 作用域的变量表 */
    fun speakVariables(speakText: String, speakSpeed: Int): Map<AppConst.JsVarName, Any> {
        return mapOf(
            AppConst.JsVarName.SPEAK_TEXT to speakText,
            AppConst.JsVarName.SPEAK_SPEED to speakSpeed,
        )
    }

    /** Content-Type 校验结果 */
    enum class ContentTypeVerdict {
        OK,

        /** json/text 响应,响应体即错误信息 */
        ERROR_BODY,

        /** 与源配置的期望 Content-Type 正则不匹配 */
        ERROR_MISMATCH,
    }

    /**
     * 校验响应 Content-Type: json/text 一律视为错误体;源配置了期望正则时需匹配。
     */
    fun checkContentType(header: String?, expected: String?): ContentTypeVerdict {
        val contentType = header?.substringBefore(";") ?: return ContentTypeVerdict.OK
        if (contentType == "application/json" || contentType.startsWith("text/")) {
            return ContentTypeVerdict.ERROR_BODY
        }
        if (expected?.isNotBlank() == true && !contentType.matches(expected.toRegex())) {
            return ContentTypeVerdict.ERROR_MISMATCH
        }
        return ContentTypeVerdict.OK
    }

    /** 音频缓存文件名: md5(章节名)_md5(url-|-语速-|-内容) */
    fun speakFileName(title: String?, url: String?, speechRate: Int, content: String): String {
        return MD5Utils.md5Encode16(title ?: "") + "_" +
                MD5Utils.md5Encode16("$url-|-$speechRate-|-$content")
    }

    /**
     * 连续下载失败熔断计数: 成功清零,失败累计,超过阈值(5)放弃。
     */
    class DownloadErrorBreaker {

        private var count = 0

        fun reset() {
            count = 0
        }

        /** 记一次失败,返回是否已超过阈值 */
        fun record(): Boolean {
            count++
            return count > 5
        }
    }
}
