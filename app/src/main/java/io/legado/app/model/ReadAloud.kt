package io.legado.app.model

import android.content.Context
import android.content.Intent
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfig
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.HttpReadAloudService
import io.legado.app.service.TTSReadAloudService
import io.legado.app.utils.LogUtils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

object ReadAloud {
    private var aloudClass: Class<*> = getReadAloudClass()
    val ttsEngine get() = ReadBook.book?.getTtsEngine() ?: AppConfig.ttsEngine
    var httpTTS: HttpTTS? = null

    private fun getReadAloudClass(): Class<*> {
        val ttsEngine = ttsEngine
        if (ttsEngine.isNullOrBlank()) return TTSReadAloudService::class.java
        if (StringUtils.isNumeric(ttsEngine)) {
            httpTTS = appDb.httpTTSDao.get(ttsEngine.toLong())
            if (httpTTS != null) return HttpReadAloudService::class.java
        }
        return TTSReadAloudService::class.java
    }

    fun upReadAloudClass() {
        stop(appCtx)
        aloudClass = getReadAloudClass()
    }

    fun play(
        context: Context,
        play: Boolean = true,
        pageIndex: Int = ReadBook.durPageIndex,
        startPos: Int = 0
    ) {
        val intent = Intent(context, aloudClass).apply {
            action = IntentAction.play
            putExtra("play", play)
            putExtra("pageIndex", pageIndex)
            putExtra("startPos", startPos)
        }
        LogUtils.d("ReadAloud", intent.toString())
        try {
            context.startForegroundServiceCompat(intent)
        } catch (e: Exception) {
            val msg = "启动朗读服务出错\n${e.localizedMessage}"
            AppLog.put(msg, e)
            context.toastOnUi(msg)
        }
    }

    private inline fun sendAction(
        context: Context,
        action: String,
        extras: Intent.() -> Unit = {}
    ) {
        if (!BaseReadAloudService.isRun) return
        val intent = Intent(context, aloudClass).apply {
            this.action = action
            extras()
        }
        context.startForegroundServiceCompat(intent)
    }

    fun pause(context: Context) = sendAction(context, IntentAction.pause)

    fun resume(context: Context) = sendAction(context, IntentAction.resume)

    fun stop(context: Context) = sendAction(context, IntentAction.stop)

    fun prevParagraph(context: Context) = sendAction(context, IntentAction.prevParagraph)

    fun nextParagraph(context: Context) = sendAction(context, IntentAction.nextParagraph)

    fun prevChapter(context: Context) = sendAction(context, IntentAction.prev)

    fun nextChapter(context: Context) = sendAction(context, IntentAction.next)

    fun upTtsSpeechRate(context: Context) = sendAction(context, IntentAction.upTtsSpeechRate)

    fun setTimer(context: Context, minute: Int) =
        sendAction(context, IntentAction.setTimer) { putExtra("minute", minute) }
}
