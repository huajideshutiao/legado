package io.legado.app.help.tts

import io.legado.app.constant.AppConst
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.http.KmpResponse
import io.legado.app.help.http.header
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.RuleRegexes
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/**
 * HttpTTS 请求的纯参数拼装/响应校验/缓存 key/熔断计数,从 HttpReadAloudService 平移。
 * 网络执行(AnalyzeUrl)、JS 求值与文件 IO 留在 Service。
 */
object HttpTtsRequest {

    /** 网络朗读源读取超时(毫秒) */
    const val READ_TIMEOUT_MS = 300 * 1000L

    /** 期望 Content-Type 正则的单次匹配上限 (毫秒)。 */
    private const val CONTENT_TYPE_TIMEOUT = 3000L

    /** 注入 url 模板 JS 作用域的变量表 */
    fun speakVariables(speakText: String, speakSpeed: Int): Map<AppConst.JsVarName, Any> {
        return mapOf(
            AppConst.JsVarName.SPEAK_TEXT to speakText,
            AppConst.JsVarName.SPEAK_SPEED to speakSpeed,
        )
    }

    /** Android 和 iOS 共用的音频请求：执行完整规则，而不是把原始 URL 交给播放器。 */
    suspend fun audioResponse(
        config: HttpTTS,
        text: String,
        speechRate: Int,
        context: CoroutineContext,
        factory: HttpTtsAnalyzeUrlFactory = HttpTtsAnalyzeUrlFactory { url, source, timeout, ctx, variables ->
            AnalyzeUrlFactories.create(
                url, source = source, readTimeout = timeout,
                coroutineContext = ctx, variables = variables,
            )
        },
    ): KmpResponse {
        val request = factory.create(
            config.url, config, READ_TIMEOUT_MS, context, speakVariables(text, speechRate),
        )
        var response = request.getResponseAwait()
        try {
            context.ensureActive()
            config.loginCheckJs?.takeIf { it.isNotBlank() }?.let {
                val checked = request.evalJS(it, response) as KmpResponse
                response = checked
            }
            if (response.code !in 200..299) {
                throw NoStackTraceException(
                    "TTS服务器返回 HTTP ${response.code}：${response.body.string().take(500)}",
                )
            }
            when (checkContentType(response.header("Content-Type"), config.contentType)) {
                ContentTypeVerdict.ERROR_BODY -> throw NoStackTraceException(response.body.string())
                ContentTypeVerdict.ERROR_MISMATCH ->
                    throw NoStackTraceException("TTS服务器返回错误：" + response.body.string())
                ContentTypeVerdict.OK -> Unit
            }
            context.ensureActive()
            return response
        } catch (error: Throwable) {
            response.close()
            throw error
        }
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
        if (expected?.isNotBlank() == true) {
            // 期望正则来自 HttpTTS 源配置 (用户填写), native 端 `kotlin.text.Regex` 的编译与
            // 匹配都会递归, 畸形规则可把线程栈写穿闪退; 匹配交给 [RuleRegexes] 的平台实现
            val matched = try {
                RuleRegexes.get()
                    .matchesEntire(contentType, expected, multiline = false, timeout = CONTENT_TYPE_TIMEOUT)
            } catch (_: RegexTimeoutException) {
                false
            }
            if (!matched) return ContentTypeVerdict.ERROR_MISMATCH
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
