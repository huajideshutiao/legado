package io.legado.app.help.exoplayer

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import io.legado.app.App
import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.externalCache
import io.legado.app.utils.isAbsUrl
import okhttp3.CacheControl
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@SuppressLint("UnsafeOptInUsageError")
object ExoPlayerHelper {

    private const val SPLIT_TAG = "\uD83D\uDEA7"

    /** [VideoHeaderResolver] 里"源 → 请求头"的最大条数: 超了就清 (一个播放器不会真连几十上百个源)。 */
    private const val MAX_HEADER_ORIGINS = 32

    fun createMediaItem(url: String, headers: Map<String, String>): MediaItem {
        val realUri = url.toUri()
        val contentType = Util.inferContentType(realUri)
        // header 仍靠 URI 尾巴上的 SPLIT_TAG 运到 DataSource 侧 (media3 1.10.1 的 MediaItem
        // 没有请求头字段), 到了 Resolver 再换成逐请求携带, 见 [VideoHeaderResolver]。
        // **本地 URI 不得拼**: file/content 没有请求头概念, 拼上去会让 FileDataSource 把
        // "\uD83D\uDEA7{}" 当路径的一部分而直接打不开文件。
        val formatUrl = if (url.isAbsUrl()) {
            url + SPLIT_TAG + KS_JSON.encodeToString(headers)
        } else {
            url
        }
        val builder = MediaItem.Builder().setUri(formatUrl)
        when (contentType) {
            C.CONTENT_TYPE_HLS -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            C.CONTENT_TYPE_DASH -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            C.CONTENT_TYPE_SS -> builder.setMimeType(MimeTypes.APPLICATION_SS)
            else -> {}
        }
        return builder.build()
    }

    /**
     * @param audioOnly 纯音频场景(AudioPlayService)传 true,开启 DSP audio offload,
     *                  绕开 c2.android.mp3.decoder 软解;视频场景必须为 false,
     *                  否则会触发 A/V 同步问题。
     */
    fun createHttpExoPlayer(context: Context, audioOnly: Boolean = false): ExoPlayer {
        // 视频走硬件 MediaCodec 默认已开,加 fallback 让冷门 codec / 怪文件硬解失败时降级到软解
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val builder = ExoPlayer.Builder(context, renderersFactory).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS / 10,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / 10
            ).build()

        ).setMediaSourceFactory(
            DefaultMediaSourceFactory(
                context,
                DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
            ).setDataSourceFactory(createResolvingDataSourceFactory(context))
                .setLiveTargetOffsetMs(5000)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(2))
        )

        if (audioOnly) {
            // offload 依赖 AudioAttributes;焦点交给 AudioFocusController,这里传 false
            builder.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
        }

        val player = builder.build()

        if (audioOnly) {
            // DSP offload 消除 PipelineWatcher pipelineFull;变速场景要求硬件支持,
            // 否则自动回退到 MediaCodec 软解(行为不变)
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(
                    AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                        .setIsSpeedChangeSupportRequired(true)
                        .build()
                )
                .build()
        }

        return player
    }


    /**
     * 单个播放器的 DataSource 链 (**每个播放器一份**, 见 [createHttpExoPlayer]):
     *
     * ```
     * ResolvingDataSource   剥 SPLIT_TAG + 把 header 挂到 DataSpec.httpRequestHeaders
     *   └─ DefaultDataSource   按 scheme 分流 (media3 自带实现)
     *        ├─ file / content / asset / android.resource → FileDataSource / ContentDataSource
     *        └─ http / https → 基座 = CacheDataSource → OkHttpDataSource
     * ```
     *
     * 本地文件因此**不进 HTTP 缓存层**: 否则播一遍 mp4 就等于把它另抄一份到
     * `externalCache/exoplayer` (视频本来就是一次性顺序读, 缓存它只会挤掉真正要缓存的东西)。
     * 旧的 `ResolvingDataSource.Factory(cacheDataSourceFactory)` 把上游写死成 OkHttp,
     * `file://` / `content://` 根本开不了。
     */
    private fun createResolvingDataSourceFactory(context: Context): ResolvingDataSource.Factory =
        ResolvingDataSource.Factory(
            DefaultDataSource.Factory(context, cacheDataSourceFactory),
            VideoHeaderResolver(),
        )

    /**
     * 逐请求携带 header 的解析器 (一个播放器实例一份)。
     *
     * # 为什么不全靠 URI 携带
     * HLS 的分片与二级清单是 media3 自己按播放列表拼出来的新 [DataSpec], URI 里没有
     * [SPLIT_TAG] 可用 —— media3-exoplayer-hls 1.10.1 sources 全仓 `httpRequestHeaders`
     * 零引用 (即清单请求的头不会自动带给分片)。所以本解析器在剥标头时顺手记一份
     * "源(site) → 请求头", 同源的后续请求 (分片/二级清单) 自动继承。作用域是**单个播放器**,
     * 两个播放器同时播不同站点各记各的。
     *
     * # 查证到的 API 依据 (media3 1.10.1 sources jar, 不凭记忆)
     * - `OkHttpDataSource` 建请求时合并 `dataSpec.httpRequestHeaders`：
     *   `media3-datasource-okhttp-1.10.1-sources.jar!androidx/media3/datasource/okhttp/OkHttpDataSource.java:394`
     *   `headers.putAll(dataSpec.httpRequestHeaders);` (在 `defaultRequestProperties` 之后合并,
     *   所以下面不再需要 `setDefaultRequestProperties` —— 那是对所有播放器可见的全局副作用)
     * - `ResolvingDataSource.Resolver.resolveDataSpec` 可以返回**改写后**的 DataSpec：
     *   `media3-datasource-1.10.1-sources.jar!androidx/media3/datasource/ResolvingDataSource.java:45`
     *   (签名 `DataSpec resolveDataSpec(DataSpec dataSpec)`), 且 `open` 里就是
     *   `upstreamDataSource.open(resolver.resolveDataSpec(dataSpec))` (同文件 108-110 行);
     *   注释明确"called for every new connection" —— 所以分片请求也会过这里
     * - `DataSpec.withUri` / `withRequestHeaders` / `buildUpon` 保留 `httpRequestHeaders`：
     *   同包 `DataSpec.java:552` / `:573` / `:506`, `Builder(DataSpec)` 拷 header 见 `:75-80`;
     *   `CacheDataSource` 开上游用的是 `dataSpec.buildUpon().setKey(key).build()`
     *   (`cache/CacheDataSource.java:585`) —— 中间的缓存层不会把头掉
     */
    private class VideoHeaderResolver : ResolvingDataSource.Resolver {

        /** 已学成的"源 → 请求头"; 只在本播放器实例内共享 ( ConcurrentHashMap 防加载线程抢 )。 */
        private val headersByOrigin = ConcurrentHashMap<String, Map<String, String>>()

        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val raw = dataSpec.uri.toString()
            val tagAt = raw.indexOf(SPLIT_TAG)
            if (tagAt < 0) {
                // 子请求 (分片 / 二级清单 / 缓存未命中重开): 按源继承已学成的头
                val origin = raw.originOf() ?: return dataSpec
                val inherited = headersByOrigin[origin] ?: return dataSpec
                return dataSpec.withRequestHeaders(inherited)
            }
            val url = raw.substring(0, tagAt)
            // 标头是我们自己用 KS_JSON 拼上去的, 解不开就是程序错 (不是坏数据),
            // 不得静默当成"没有头"继续播 —— 那会把一个真 bug 变成难查的 403。
            val headers: Map<String, String> = runCatching {
                KS_JSON.decodeFromString<Map<String, String>>(raw.substring(tagAt + SPLIT_TAG.length))
            }.onFailure {
                AppLog.put("视频请求头解析失败, 本次装载不带请求头\n${it.message}", it)
            }.getOrDefault(emptyMap())
            url.originOf()?.let { origin ->
                if (headersByOrigin.size > MAX_HEADER_ORIGINS) headersByOrigin.clear()
                headersByOrigin[origin] = headers
            }
            val resolved = dataSpec.withUri(url.toUri())
            // 空 header 不写: 得把上游可能已带的头原样留着
            return if (headers.isEmpty()) resolved else resolved.withRequestHeaders(headers)
        }
    }

    /** `scheme://authority` (剥掉 query/fragment 与尾上的 header 标头), 用作 header 继承的 key。 */
    private fun String.originOf(): String? {
        val schemeEnd = indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
        return "$scheme://$authority"
    }


    /**
     * 支持缓存的DataSource.Factory
     */
    private val cacheDataSourceFactory by lazy {
        //使用自定义的CacheDataSource以支持设置UA
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(okhttpDataFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
    }

    /**
     * Okhttp DataSource.Factory
     *
     * 注：这里**从未**调过 `setDefaultRequestProperties` —— 那是全局副作用 (一个播放器设的头
     * 会被所有播放器及其后续请求吃到, 包括本地 URI), 请求头一律见 [VideoHeaderResolver]
     * 逐请求挂在 `DataSpec.httpRequestHeaders` 上。
     */
    val okhttpDataFactory by lazy {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        OkHttpDataSource.Factory(client)
            .setCacheControl(CacheControl.Builder().maxAge(1, TimeUnit.DAYS).build())
    }

    /**
     * Exoplayer 内置的缓存
     */
    private val cache: Cache by lazy {
        val databaseProvider = StandaloneDatabaseProvider(App.instance)
        return@lazy SimpleCache(
            //Exoplayer的缓存路径
            File(App.instance.externalCache, "exoplayer"),
            //100M的缓存
            LeastRecentlyUsedCacheEvictor((100 * 1024 * 1024).toLong()),
            //记录缓存的数据库
            databaseProvider
        )
    }
}