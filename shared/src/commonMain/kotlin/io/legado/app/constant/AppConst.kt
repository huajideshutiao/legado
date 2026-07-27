package io.legado.app.constant

/**
 * 跨端纯常量区。androidId/appInfo/authority/时间格式等安卓面见 app 侧 AppConstAndroid.kt
 * (同包 AppConst 扩展属性, 调用处 AppConst.xxx 写法不变)。
 */
@Suppress("ConstPropertyName")
object AppConst {

    const val APP_TAG = "Legado"

    const val channelIdDownload = "channel_download"
    const val channelIdReadAloud = "channel_read_aloud"
    const val channelIdWeb = "channel_web"

    const val UA_NAME = "User-Agent"

    /** 注入所有 JS eval 作用域的 platform 变量值，KMP 各端取 android/ios/ohos/jvm。 */
    const val JS_PLATFORM = "android"

    const val MAX_THREAD = 9

    const val DEFAULT_WEBDAV_ID = -1L

    const val imagePathKey = "imagePath"

    val charsets =
        arrayListOf("UTF-8", "GB2312", "GB18030", "GBK", "Unicode", "UTF-16", "UTF-16LE", "ASCII")

    const val timeLimit = 15000L

    /**
     * 注入到 evalJS 作用域的变量名白名单。
     * 通过 [io.legado.app.model.analyzeRule.AnalyzeUrl] / [io.legado.app.model.analyzeRule.AnalyzeRule] 的 `variables` 参数注入时，仅允许使用此处声明的键，
     * 防止散落字符串导致拼写错误或难以追踪。
     */
    enum class JsVarName(val key: String) {
        KEY("key"),
        PAGE("page"),
        SPEAK_TEXT("speakText"),
        SPEAK_SPEED("speakSpeed"),
        PARAGRAPH_INDEX("paragraphIndex"),
        SORT("sort"),
        REVIEW_ID("reviewId"),
        SELECTED("selected"),
    }

}
