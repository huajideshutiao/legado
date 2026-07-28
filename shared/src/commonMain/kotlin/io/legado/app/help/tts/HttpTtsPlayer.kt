package io.legado.app.help.tts

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.printStackTraceOnDebug
import io.legado.app.help.http.KmpResponse
import io.legado.app.help.http.header
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.script.JsEngines
import io.legado.app.utils.InputStream
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isRetryableNetworkError
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive

/**
 * 在线 HttpTTS 播放器抽象接口（KMP 版）。
 *
 * 各平台 actual 实现:
 * - app actual: ExoPlayer + Media3 + SimpleCache（见 `app/.../service/HttpReadAloudService.kt`）
 * - desktop actual: `javax.sound.sampled` 直播 wav/pcm,或引入 jlayer/JavaFX 后扩展 mp3
 * - ios actual: AVPlayer
 * - ohos actual: 鸿蒙 AVPlayer
 *
 * 设计原则:
 * - 仅描述"播放一段在线音频 URL"所需的最小能力,不暴露平台特定类型
 * - URL + headers 由调用方通过 [setUrl] 注入,actual 内部决定如何拉取与解码
 * - 状态变化统一通过 [HttpTtsPlayerListener],避免平台 listener 类型泄漏
 */
interface HttpTtsPlayer {

    /** 是否正在播放。 */
    val isPlaying: Boolean

    /** 总时长（毫秒）,-1 表示未知。 */
    val duration: Long

    /** 当前播放位置（毫秒）。 */
    val currentPosition: Long

    /** 播放状态回调。 */
    var listener: HttpTtsPlayerListener?

    /** 设置播放源 URL 及请求头。 */
    fun setUrl(url: String, headers: Map<String, String>)

    /** 准备播放（缓冲首帧）,完成后触发 [HttpTtsPlayerListener.onReady]。 */
    fun prepare()

    /** 开始/恢复播放。 */
    fun play()

    /** 暂停播放。 */
    fun pause()

    /** 停止播放并清空状态。 */
    fun stop()

    /** 释放底层资源。 */
    fun release()

    /** 跳转到指定位置（毫秒）。 */
    fun seekTo(position: Long)
}

/**
 * HttpTTS 播放状态回调（KMP 版,对标 Media3 `Player.Listener`）。
 *
 * 各 actual 负责把平台 listener 适配为此接口回调。
 */
interface HttpTtsPlayerListener {

    /** 准备完成,可以开始 play。 */
    fun onReady()

    /** 当前媒体播放到末尾。 */
    fun onEndOfMedia()

    /** 播放或网络出错。 */
    fun onError(message: String)

    /** 缓冲进度更新（0-100）。 */
    fun onBufferingUpdate(percent: Int)
}

// region 下载调度核心 —— 从 app 端 HttpReadAloudService 下沉

/**
 * HttpTTS 缓存目录 + 文件操作 Provider（KMP 版）。
 *
 * 各平台 actual 提供:
 * - app: `cacheDir.absolutePath + "httpTTS/"` + `java.io.File` 文件操作
 * - desktop: 用户缓存目录 + `java.io.File`
 * - ios/ohos: 待实现
 *
 * 设计原则: commonMain 不能引用 `java.io.File`（iOS/鸿蒙 target 不可见）,
 * 故文件操作经此接口注入,各平台 actual 内部用平台 File API 实现。
 */
interface HttpTtsCacheDirProvider {

    /** TTS 缓存目录绝对路径,末尾带分隔符。 */
    fun ttsCacheDir(): String

    /** 当前系统时间戳（毫秒）,用于缓存清理判断 lastModified。 */
    fun currentTimeMillis(): Long

    /** 判断文件是否存在。 */
    fun exist(path: String): Boolean

    /** 创建文件（如不存在）,返回平台 File 句柄（Any 避免 commonMain 引用 java.io.File）。 */
    fun createFileIfNotExist(path: String): Any

    /** 列出目录下所有文件和子目录;目录不存在返回 null。 */
    fun listDirsAndFiles(dirPath: String): List<HttpTtsFileInfo>?

    /** 删除文件或目录。 */
    fun delete(path: String): Boolean

    /** 把输入流写入指定路径文件。 */
    fun writeInputStream(path: String, stream: InputStream): Boolean

    /** 把字节数组写入指定路径文件。 */
    fun writeBytes(path: String, bytes: ByteArray): Boolean

    /** 无声音频占位字节数据（各平台从资源加载,如 app 端 R.raw.silent_sound）。 */
    fun silentSoundBytes(): ByteArray
}

/** 缓存文件信息（跨平台,不暴露 java.io.File）。 */
data class HttpTtsFileInfo(
    val name: String,
    val length: Long,
    val lastModified: Long,
)

/**
 * AnalyzeUrl 工厂（KMP 版）。
 *
 * app 端创建带 JsExtensions 的 [io.legado.app.model.analyzeRule.AnalyzeUrl]（app 子类）,
 * commonMain 只依赖 [AnalyzeUrlCore]。工厂返回 [AnalyzeUrlCore],实际实例由 app 端决定,
 * 保证 JS bindings 中 `java` 变量可见 JsExtensions 方法。
 */
fun interface HttpTtsAnalyzeUrlFactory {

    fun create(
        url: String,
        source: BaseSource?,
        readTimeout: Long?,
        coroutineContext: CoroutineContext,
        variables: Map<AppConst.JsVarName, Any>?,
    ): AnalyzeUrlCore
}

/**
 * 下载调度回调。调用方（app 端 HttpReadAloudService）实现,桥接到 ExoPlayer。
 *
 * 设计原则: 调度器不持有平台播放器引用,文件就绪后通过本回调通知调用方,
 * 调用方负责把文件转成平台 MediaItem/MediaSource 加到播放器。
 */
interface HttpTtsDownloadCallback {

    /** 调度开始前清空播放器媒体列表。 */
    fun onClearMediaItems()

    /** 一段音频文件已就绪,调用方将其加入播放器。[filePath] 为 mp3 绝对路径。 */
    fun onSpeakFileReady(fileName: String, filePath: String)

    /** 下载过程出错,调用方暂停朗读。 */
    fun onPauseReadAloud()
}

/**
 * HttpTTS 下载调度核心（KMP 版）。
 *
 * 从 `app/.../service/HttpReadAloudService.kt` 平移:
 * - 文件路径管理（md5SpeakFileName / hasSpeakFile / getSpeakFilePath / createSpeakFile / removeCacheFile）
 * - 在线音频下载（getSpeakStream —— AnalyzeUrlCore + HttpTtsRequest 校验）
 * - 下载调度（downloadAndPlayAudios / preDownloadAudios）的纯逻辑部分
 *
 * ExoPlayer 集成（addMediaItem / addMediaSource / clearMediaItems）通过 [HttpTtsDownloadCallback]
 * 回调注入,app 端 HttpReadAloudService 仅保留 ExoPlayer 集成与 MediaSource 构造。
 *
 * @param cacheDirProvider 缓存目录路径 + 文件操作注入
 * @param analyzeUrlFactory AnalyzeUrl 实例工厂（app 端创建带 JsExtensions 的子类）
 */
class HttpTtsDownloadScheduler(
    private val cacheDirProvider: HttpTtsCacheDirProvider,
    private val analyzeUrlFactory: HttpTtsAnalyzeUrlFactory,
) {

    private val downloadErrorBreaker = HttpTtsRequest.DownloadErrorBreaker()

    /** 当前语速（由调用方更新,影响文件名 hash）。 */
    var speechRate: Int = 0

    // region 文件路径管理

    /** 计算缓存文件名（不含扩展名,不含目录）。 */
    fun md5SpeakFileName(content: String, chapterTitle: String?, ttsUrl: String?): String =
        HttpTtsRequest.speakFileName(chapterTitle, ttsUrl, speechRate, content)

    /** 缓存文件是否存在。 */
    fun hasSpeakFile(name: String): Boolean =
        cacheDirProvider.exist(ttsCacheDir() + name + ".mp3")

    /** 获取缓存文件绝对路径。 */
    fun getSpeakFilePath(name: String): String =
        ttsCacheDir() + name + ".mp3"

    /** 创建空缓存文件,返回平台 File 句柄。 */
    fun createSpeakFile(name: String): Any =
        cacheDirProvider.createFileIfNotExist(ttsCacheDir() + name + ".mp3")

    /** 从输入流创建缓存文件。 */
    fun createSpeakFile(name: String, inputStream: InputStream) {
        cacheDirProvider.writeInputStream(ttsCacheDir() + name + ".mp3", inputStream)
    }

    /** 写入无声音频占位。 */
    fun createSilentSound(name: String) {
        cacheDirProvider.writeBytes(ttsCacheDir() + name + ".mp3", cacheDirProvider.silentSoundBytes())
    }

    /** 移除过期缓存文件:非当前章节且超过 10 分钟的文件 + 所有无声音频占位。 */
    fun removeCacheFile(chapterTitle: String?) {
        val titleMd5 = MD5Utils.md5Encode16(chapterTitle ?: "")
        val dirPath = ttsCacheDir()
        val now = cacheDirProvider.currentTimeMillis()
        cacheDirProvider.listDirsAndFiles(dirPath)?.forEach { info ->
            val isSilentSound = info.length == 2160L
            if ((!info.name.startsWith(titleMd5) && now - info.lastModified > 600000L) || isSilentSound) {
                cacheDirProvider.delete(dirPath + info.name)
            }
        }
    }

    private fun ttsCacheDir(): String = cacheDirProvider.ttsCacheDir()

    // endregion

    // region 在线音频下载

    /**
     * 拉取一段文本的音频流。
     *
     * - 返回非 null: 音频流就绪,调用方写入缓存文件
     * - 返回 null: 下载出错但未超阈值,用无声音频代替
     * - 抛异常: CancellationException / ScriptException / 连续 5 次错误,调用方暂停朗读
     *
     * @param httpTts TTS 源配置
     * @param speakText 朗读文本（已过滤不可读字符）
     * @param coroutineContext 当前协程上下文（传入避免 commonMain 调 currentCoroutineContext）
     */
    suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        coroutineContext: CoroutineContext,
    ): InputStream? {
        while (true) {
            try {
                val analyzeUrl = analyzeUrlFactory.create(
                    httpTts.url,
                    httpTts,
                    HttpTtsRequest.READ_TIMEOUT_MS,
                    coroutineContext,
                    HttpTtsRequest.speakVariables(speakText, speechRate),
                )
                var response = analyzeUrl.getResponseAwait()
                coroutineContext.ensureActive()
                val checkJs = httpTts.loginCheckJs
                if (checkJs?.isNotBlank() == true) {
                    response = analyzeUrl.evalJS(checkJs, response) as KmpResponse
                }
                when (HttpTtsRequest.checkContentType(
                    response.header("Content-Type"),
                    httpTts.contentType,
                )) {
                    HttpTtsRequest.ContentTypeVerdict.ERROR_BODY ->
                        throw NoStackTraceException(response.body.string())

                    HttpTtsRequest.ContentTypeVerdict.ERROR_MISMATCH ->
                        throw NoStackTraceException("TTS服务器返回错误：" + response.body.string())

                    HttpTtsRequest.ContentTypeVerdict.OK -> Unit
                }
                coroutineContext.ensureActive()
                response.body.byteStream().let { stream ->
                    downloadErrorBreaker.reset()
                    return stream
                }
            } catch (e: Exception) {
                when {
                    e is CancellationException -> throw e
                    JsEngines.isJsException(e) -> {
                        AppLog.put("js错误\n${e.message}", e, true)
                        e.printStackTraceOnDebug()
                        throw e
                    }
                    isRetryableNetworkError(e) -> {
                        if (downloadErrorBreaker.record()) {
                            AppLog.put("tts超时或连接错误超过5次\n${e.message}", e, true)
                            throw e
                        }
                    }
                    else -> {
                        val exceeded = downloadErrorBreaker.record()
                        AppLog.put("tts下载错误\n${e.message}", e)
                        e.printStackTraceOnDebug()
                        if (exceeded) {
                            AppLog.put("TTS服务器连续5次错误，已暂停阅读。", e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    /** 重置下载错误熔断器。 */
    fun resetErrorBreaker() = downloadErrorBreaker.reset()

    // endregion

    // region 下载调度

    /**
     * 下载并播放（非流式）: 下载每段音频到缓存文件,通过回调通知播放器加入。
     *
     * 对标 app 端原 `downloadAndPlayAudios` 的纯逻辑部分:
     * - exoPlayer.clearMediaItems() → [HttpTtsDownloadCallback.onClearMediaItems]
     * - exoPlayer.addMediaItem(...) → [HttpTtsDownloadCallback.onSpeakFileReady]
     * - pauseReadAloud() → [HttpTtsDownloadCallback.onPauseReadAloud]
     *
     * 调用方负责: downloadTask 管理、downloadTaskActiveLock、execute/onError 包装。
     *
     * @param httpTts TTS 源配置
     * @param contentList 当前章节段落列表
     * @param nowSpeak 当前朗读段落下标
     * @param paragraphStartPos 当前段落起始偏移
     * @param chapterTitle 章节标题（用于文件名 hash 前缀）
     * @param ttsUrl TTS 源 URL（用于文件名 hash）
     * @param callback 播放器桥接回调
     * @param coroutineContext 当前协程上下文
     * @param preDownloadContents 下一章预下载段落序列,null 跳过预下载
     */
    suspend fun downloadAndPlayAudios(
        httpTts: HttpTTS,
        contentList: List<String>,
        nowSpeak: Int,
        paragraphStartPos: Int,
        chapterTitle: String?,
        ttsUrl: String?,
        callback: HttpTtsDownloadCallback,
        coroutineContext: CoroutineContext,
        preDownloadContents: Sequence<String>? = null,
    ) {
        callback.onClearMediaItems()
        contentList.forEachIndexed { index, content ->
            coroutineContext.ensureActive()
            if (index < nowSpeak) return@forEachIndexed
            var text = content
            if (paragraphStartPos > 0 && index == nowSpeak) {
                text = text.substring(paragraphStartPos)
            }
            val fileName = md5SpeakFileName(text, chapterTitle, ttsUrl)
            val speakText = text.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                runCatching {
                    val inputStream = getSpeakStream(httpTts, speakText, coroutineContext)
                    if (inputStream != null) {
                        createSpeakFile(fileName, inputStream)
                    } else {
                        createSilentSound(fileName)
                    }
                }.onFailure {
                    when (it) {
                        is CancellationException -> Unit
                        else -> callback.onPauseReadAloud()
                    }
                    return
                }
            }
            callback.onSpeakFileReady(fileName, getSpeakFilePath(fileName))
        }
        preDownloadContents?.let { preDownloadAudios(httpTts, it, chapterTitle, ttsUrl, coroutineContext) }
    }

    /**
     * 预下载下一段音频到缓存（不阻塞播放）。
     *
     * 对标 app 端原 `preDownloadAudios`: 取前 10 段,逐段下载到缓存文件。
     * 调用方负责提供下一章的段落序列（原 app 端从 ReadBook.nextTextChapter 获取）。
     *
     * @param httpTts TTS 源配置
     * @param contents 下一章段落序列（取前 10 段）
     * @param chapterTitle 章节标题（与 downloadAndPlayAudios 一致,用当前章节标题）
     * @param ttsUrl TTS 源 URL
     * @param coroutineContext 当前协程上下文
     */
    suspend fun preDownloadAudios(
        httpTts: HttpTTS,
        contents: Sequence<String>,
        chapterTitle: String?,
        ttsUrl: String?,
        coroutineContext: CoroutineContext,
    ) {
        contents.take(10).forEach { content ->
            coroutineContext.ensureActive()
            val fileName = md5SpeakFileName(content, chapterTitle, ttsUrl)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                runCatching {
                    val inputStream = getSpeakStream(httpTts, speakText, coroutineContext)
                    if (inputStream != null) {
                        createSpeakFile(fileName, inputStream)
                    } else {
                        createSilentSound(fileName)
                    }
                }
            }
        }
    }

    /**
     * 流式下载场景的段落迭代（纯逻辑骨架）。
     *
     * 对标 app 端 `downloadAndPlayAudiosStream` 的循环部分:
     * - 跳过 index < nowSpeak 的段落
     * - 对当前段落应用 paragraphStartPos 偏移
     * - 计算 speakText（过滤不可读字符）和 fileName（MD5）
     * - 空文本记录日志
     *
     * 调用方在 [onParagraph] 回调中执行平台特定逻辑
     * （如 ExoPlayer 创建 DataSource/MediaSource 并加入播放器）。
     * 与 [downloadAndPlayAudios] 的区别: 不下载到文件,不调用 onSpeakFileReady,
     * 由调用方决定如何处理每段。
     *
     * 作为 [CoroutineScope] 成员扩展,[onParagraph] 回调接收者即本扩展接收者,
     * 调用方可在回调内直接 `launch` 启动子协程,取消语义与原 app 端一致。
     *
     * @param contentList 段落列表
     * @param nowSpeak 当前朗读段落下标
     * @param paragraphStartPos 当前段落起始偏移
     * @param chapterTitle 章节标题（用于文件名 hash 前缀）
     * @param ttsUrl TTS 源 URL
     * @param onParagraph 每段回调（接收者为本扩展的 CoroutineScope）
     */
    suspend fun CoroutineScope.forEachSpeakParagraphForStreaming(
        contentList: List<String>,
        nowSpeak: Int,
        paragraphStartPos: Int,
        chapterTitle: String?,
        ttsUrl: String?,
        onParagraph: CoroutineScope.(text: String, speakText: String, fileName: String) -> Unit,
    ) {
        contentList.forEachIndexed { index, content ->
            coroutineContext.ensureActive()
            if (index < nowSpeak) return@forEachIndexed
            var text = content
            if (paragraphStartPos > 0 && index == nowSpeak) {
                text = text.substring(paragraphStartPos)
            }
            val speakText = text.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
            }
            val fileName = md5SpeakFileName(text, chapterTitle, ttsUrl)
            onParagraph(text, speakText, fileName)
        }
    }

    // endregion
}

// endregion
