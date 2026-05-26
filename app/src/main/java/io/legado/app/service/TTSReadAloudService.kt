package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.tts.TextToSpeechEngine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService() {

    private var engine: TextToSpeechEngine? = null
    private var speakJob: Coroutine<*>? = null
    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        runCatching {
            initEngine()
        }.onFailure {
            AppLog.put("${getString(R.string.tts_init_failed)}\n$it", it, true)
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
        engine?.shutdown()
        val engineName = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine)
            .getOrNull()?.value
        LogUtils.d(TAG, "initEngine name:$engineName")
        engine = TextToSpeechEngine(engineName).apply {
            progressListener = TTSUtteranceListener()
            onInitFailed = { toastOnUi(R.string.tts_init_failed) }
        }
        engine?.ensureReady {
            upSpeechRate()
            play()
        }
    }

    @Synchronized
    override fun play() {
        val engine = engine ?: return
        if (!engine.isReady) return
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        speakJob?.cancel()
        speakJob = execute {
            LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
            LogUtils.d(TAG, "朗读页数 ${textChapter?.pageSize}")
            val list = contentList
            var firstSpoken = false
            for (i in nowSpeak until list.size) {
                ensureActive()
                var text = list[i]
                if (paragraphStartPos > 0 && i == nowSpeak) {
                    text = text.substring(paragraphStartPos)
                }
                if (text.matches(AppPattern.notReadAloudRegex)) continue
                val utteranceId = AppConst.APP_TAG + i
                val result = if (!firstSpoken) {
                    engine.speak(text, utteranceId)
                } else {
                    engine.enqueue(text, utteranceId)
                }
                if (result == TextToSpeech.ERROR) {
                    if (!firstSpoken) {
                        AppLog.put("tts出错 尝试重新初始化")
                        initEngine()
                        return@execute
                    } else {
                        AppLog.put("tts朗读出错:$text")
                    }
                }
                firstSpoken = true
            }
            LogUtils.d(TAG, "朗读内容添加完成")
            if (!firstSpoken) {
                playStop()
                delay(1000)
                nextChapter()
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

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        speakJob?.cancel()
        engine?.stop()
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
            val chapter = textChapter ?: return
            if (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex)) {
                nextParagraph()
            }
            if (pageIndex + 1 < chapter.pageSize
                && readAloudNumber + 1 > chapter.getReadLength(pageIndex + 1)
            ) {
                pageIndex++
                ReadBook.moveToNextPage()
            }
            upTtsProgress(readAloudNumber + 1)
        }

        override fun onDone(s: String) {
            LogUtils.d(TAG, "onDone utteranceId:$s")
            nextParagraph()
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            LogUtils.d(
                TAG,
                "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId " +
                    "start:$start end:$end frame:$frame"
            )
            val chapter = textChapter ?: return
            if (pageIndex + 1 < chapter.pageSize
                && readAloudNumber + start > chapter.getReadLength(pageIndex + 1)
            ) {
                pageIndex++
                ReadBook.moveToNextPage()
                upTtsProgress(readAloudNumber + start)
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            LogUtils.d(
                TAG,
                "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId " +
                    "errorCode:$errorCode"
            )
            nextParagraph()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
            nextParagraph()
        }

        /**
         * 跳过全标点段落,推进到下一段;若已到末尾则切下一章。
         */
        private fun nextParagraph() {
            do {
                readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
                paragraphStartPos = 0
                nowSpeak++
                if (nowSpeak >= contentList.size) {
                    nextChapter()
                    return
                }
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }
}
