package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.tts.TextToSpeechEngine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ActiveReadAloudHostPorts
import io.legado.app.model.ReadAloud
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService() {

    private var engine: TextToSpeechEngine? = null
    private var speakJob: Coroutine<*>? = null

    /** 引擎初始化代次: 写侧在 @Synchronized 的 initEngine/play 内, 读侧在 TTS 进度回调的 binder
     *  线程 (拦旧引擎的迟到回调), 故需要可见性。 */
    @Volatile
    private var engineGeneration = 0L

    private var initializedEngineName: String? = null
    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        runCatching {
            initEngine()
        }.onFailure {
            AppLog.put("${androidAppString("tts_init_failed")}\n$it", it, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.shutdown()
        engine = null
    }

    /**
     * 用当前 [ReadAloud.ttsEngine] 配置创建一个新的引擎。
     * 引擎名变化(切换系统 TTS / 切换 HTTP TTS)时需要重新调用。
     */
    @Synchronized
    private fun initEngine() {
        val engineName = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine)
            .getOrNull()?.value
        val generation = ++engineGeneration
        speakJob?.cancel()
        speakJob = null
        engine?.stop()
        engine?.shutdown()
        LogUtils.d(TAG, "initEngine name:$engineName")
        val created = TextToSpeechEngine(engineName).apply {
            progressListener = TTSUtteranceListener(generation)
            onInitFailed = { toastOnUi(androidAppString("tts_init_failed")) }
        }
        engine = created
        initializedEngineName = engineName
        created.ensureReady(
            block = {
                if (engine !== created || generation != engineGeneration) return@ensureReady
                upSpeechRate()
                if (readAloudController.state.value == ReadAloudControllerShared.ReadAloudState.PLAYING) play()
            },
            onError = {
                if (engine === created && generation == engineGeneration) {
                    readAloudController.reportError(androidAppString("tts_init_failed"))
                }
            },
        )
    }

    @Synchronized
    override fun play() {
        val configuredName = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine)
            .getOrNull()?.value
        if (engine == null || configuredName != initializedEngineName) {
            initEngine()
            return
        }
        val engine = engine ?: return
        if (!engine.isReady) {
            engine.ensureReady(block = { if (this.engine === engine) play() })
            return
        }
        if (!requestFocus()) return
        if (currentPlaybackText.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            readBook?.readAloud()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        speakJob?.cancel()
        speakJob = execute {
            LogUtils.d(TAG, "朗读列表大小 ${readAloudController.playbackQueue.snapshot.contentList.size}")
            LogUtils.d(TAG, "朗读页数 ${textChapter?.pageSize}")
            val utteranceId = AppConst.APP_TAG + currentPlaybackToken
            val result = engine.speak(currentPlaybackText, utteranceId)
            if (result == TextToSpeech.ERROR) {
                // 失败先停旧播放并作废本轮；重新初始化完成后 controller 会重放当前段。
                engine.stop()
                initEngine()
            }
        }.onError {
            AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    override fun playStop() {
        engine?.stop()
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) initEngine()
        } else {
            val rate = (AppConfig.ttsSpeechRate + 5) / 10f
            engine?.setSpeechRate(rate)
        }
    }

    private inner class TTSUtteranceListener(
        private val generation: Long,
    ) : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            if (generation != engineGeneration || s != AppConst.APP_TAG + currentPlaybackToken) return
            // 一次取快照: 段下标/段文本/已读位置必须同源 (本回调在 TTS binder 线程, 切章在调用方线程)
            val playback = readAloudController.playbackQueue.snapshot
            LogUtils.d(
                TAG,
                "onStart nowSpeak:${playback.nowSpeak} pageIndex:${readBook?.durPageIndexValue} utteranceId:$s"
            )
            textChapter ?: return
            val paragraph = playback.currentParagraph() ?: return
            if (paragraph.matches(AppPattern.notReadAloudRegex)) {
                nextParagraph(s.removePrefix(AppConst.APP_TAG).toLongOrNull() ?: return)
            }
            ActiveReadAloudHostPorts.advanceReadAloudPage(
                readAloudController.chapterIndex.value,
                playback.readAloudNumber + 1
            )
            upTtsProgress(playback.readAloudNumber + 1)
        }

        override fun onDone(s: String) {
            if (generation != engineGeneration || s != AppConst.APP_TAG + currentPlaybackToken) return
            LogUtils.d(TAG, "onDone utteranceId:$s")
            nextParagraph(s.removePrefix(AppConst.APP_TAG).toLongOrNull() ?: return)
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            if (generation != engineGeneration || utteranceId != AppConst.APP_TAG + currentPlaybackToken) return
            super.onRangeStart(utteranceId, start, end, frame)
            val playback = readAloudController.playbackQueue.snapshot
            LogUtils.d(
                TAG,
                "onRangeStart nowSpeak:${playback.nowSpeak} pageIndex:${readBook?.durPageIndexValue} utteranceId:$utteranceId " +
                    "start:$start end:$end frame:$frame"
            )
            textChapter ?: return
            // 上报与翻页联动各走一路: 位置上报不因翻页判定结果被抑制
            // (宿主端 syncReadPosition 与 onStart 同口径)
            val readingPos = playback.readAloudNumber + start
            ActiveReadAloudHostPorts.advanceReadAloudPage(
                readAloudController.chapterIndex.value, readingPos
            )
            upTtsProgress(readingPos)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (generation != engineGeneration || utteranceId != AppConst.APP_TAG + currentPlaybackToken) return
            LogUtils.d(
                TAG,
                "onError nowSpeak:${readAloudController.playbackQueue.snapshot.nowSpeak} pageIndex:${readBook?.durPageIndexValue} utteranceId:$utteranceId " +
                    "errorCode:$errorCode"
            )
            nextParagraph(utteranceId.removePrefix(AppConst.APP_TAG).toLongOrNull() ?: return)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            if (generation != engineGeneration || s != AppConst.APP_TAG + currentPlaybackToken) return
            LogUtils.d(
                TAG,
                "onError nowSpeak:${readAloudController.playbackQueue.snapshot.nowSpeak} pageIndex:${readBook?.durPageIndexValue} s:$s"
            )
            nextParagraph(s.removePrefix(AppConst.APP_TAG).toLongOrNull() ?: return)
        }

        /**
         * 跳过全标点段落,推进到下一段;若已到末尾则切下一章。
         */
        private fun nextParagraph(token: Long) {
            readAloudController.paragraphCompleted(token)
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }
}
