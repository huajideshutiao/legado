package io.legado.app.model.analyzeRule

import androidx.annotation.Keep
import androidx.media3.common.MediaItem
import com.bumptech.glide.load.model.GlideUrl
import com.script.jsdispatch.JsApi
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.JsExtensions
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.glide.GlideHeaders
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * AnalyzeUrl app 端实现: 继承 shared 的 [AnalyzeUrlCore] 并实现 [JsExtensions],
 * 添加 android-only 方法 (getGlideUrl/getMediaItem)。
 *
 * KSP @JsApi 分派表由本类生成, 通过 getAllFunctions() 继承链
 * 自动包含 AnalyzeUrlCore 的 public 方法, 方法名集合与原 AnalyzeUrl 完全一致 (零 diff)。
 *
 * Created by GKF on 2018/1/24.
 * 搜索URL规则解析
 */
@Keep
@JsApi
class AnalyzeUrl(
    rawUrl: String,
    baseUrl: String = "",
    source: BaseSource? = null,
    ruleData: RuleDataInterface? = null,
    chapter: BookChapter? = null,
    readTimeout: Long? = null,
    callTimeout: Long? = null,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    headerMapF: Map<String, String>? = null,
    hasLoginHeader: Boolean = true,
    selectedOptions: Map<String, String>? = null,
    variables: Map<AppConst.JsVarName, Any>? = null
) : AnalyzeUrlCore(
    rawUrl, baseUrl, source, ruleData, chapter, readTimeout, callTimeout,
    coroutineContext, headerMapF, hasLoginHeader, selectedOptions, variables
), JsExtensions {

    /**
     * 获取处理过阅读定义的urlOption和cookie的GlideUrl
     */
    fun getGlideUrl(): GlideUrl {
        setCookie()
        return GlideUrl(url, GlideHeaders(headerMap))
    }

    companion object {
        fun AnalyzeUrl.getMediaItem(): MediaItem {
            setCookie()
            return ExoPlayerHelper.createMediaItem(url, headerMap)
        }
    }
}
