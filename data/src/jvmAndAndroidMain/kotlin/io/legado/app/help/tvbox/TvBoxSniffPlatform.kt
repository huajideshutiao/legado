package io.legado.app.help.tvbox

import kotlin.concurrent.Volatile

/**
 * TVBox 网页嗅探平台钩子 (jvmAndAndroidMain 共用契约)。
 *
 * 嗅探要加载解析页并拦截其子资源请求找真实媒体地址: Android 走系统 WebView
 * (app 模块 AndroidTvBoxSniffer), 桌面端走 DesktopWebViewEngine (desktop 模块
 * DesktopTvBoxSniffer, 引擎全不可用时如实报错), 未注册平台的 [TvBoxSniffer.sniff]
 * 如实报错 (不影响直链取播与 type=1 json 解析)。
 */
fun interface TvBoxSniffPlatform {

    /** 加载播放页并嗅探真实媒体地址 (语义见 [TvBoxSniffer.sniff] KDoc)。 */
    suspend fun sniff(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        videoChecker: TvBoxVideoPredicate,
        depth: Int,
    ): TvBoxSniffResult
}

/** [TvBoxSniffPlatform] 容器 (provider 注入模式)。 */
object TvBoxSniffPlatforms {

    @Volatile
    private var impl: TvBoxSniffPlatform? = null

    /** 宿主启动早期注册一次 (取播链路首次嗅探之前)。 */
    fun register(impl: TvBoxSniffPlatform) {
        this.impl = impl
    }

    /** 未注册端 (桌面等) 返回 null, 嗅探入口据此报错。 */
    fun getOrNull(): TvBoxSniffPlatform? = impl

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
