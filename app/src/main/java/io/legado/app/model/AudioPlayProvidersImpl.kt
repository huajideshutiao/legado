package io.legado.app.model

import android.content.Intent
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.save
import io.legado.app.help.book.saveRead
import io.legado.app.service.AudioPlayService
import io.legado.app.utils.startService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * AudioPlay 平台 provider 的 Android 实现。
 *
 * 包含:
 * - [AndroidAudioPlayCommander]: 实现 [AudioPlayCommander], 内部走
 *   `appCtx.startService<AudioPlayService>` + IntentAction + extras,
 *   与原 app 端 `AudioPlay.sendAction` 完全等价
 * - [AndroidAudioPlayBookBridge]: 实现 [AudioPlayBookBridge], 委托
 *   `book.saveRead()` / `book.save()` 扩展 (app 端 BookExtensions.kt); getBookSource 直接查 DAO
 *
 * 注册时机: App.onCreate, 经 [registerAndroidAudioPlayProviders]。
 *
 * 模式参考 `WebBookProvidersImpl` / `registerAndroidWebBookProviders`。
 */
object AudioPlayProvidersImpl : AudioPlayCommander, AudioPlayBookBridge {

    // ---------- AudioPlayCommander ----------

    override val isServiceRunning: Boolean
        get() = AudioPlayService.isRun

    override var pendingTimerMinute: Int
        get() = AudioPlayService.pendingTimerMinute
        set(value) {
            AudioPlayService.pendingTimerMinute = value
        }

    override fun play() = sendAction(IntentAction.play, requireRunning = false)

    override fun playNew() = sendAction(IntentAction.playNew, requireRunning = false)

    override fun stop() = sendAction(IntentAction.stop)

    override fun stopPlay() = sendAction(IntentAction.stopPlay)

    override fun pause() = sendAction(IntentAction.pause)

    override fun resume() = sendAction(IntentAction.resume)

    override fun adjustSpeed(adjust: Float) =
        sendAction(IntentAction.adjustSpeed) { putExtra("adjust", adjust) }

    override fun adjustProgress(position: Int) =
        sendAction(IntentAction.adjustProgress) { putExtra("position", position) }

    override fun setTimer(minute: Int) =
        sendAction(IntentAction.setTimer) { putExtra("minute", minute) }

    override fun addTimer() = sendAction(IntentAction.addTimer, requireRunning = false)

    override fun loadPlayUrl() = sendAction(IntentAction.loadPlayUrl, requireRunning = false)

    /**
     * 向 [AudioPlayService] 发送命令 (原 app 端 `AudioPlay.sendAction` 等价实现)。
     *
     * @param requireRunning 仅在服务运行中才派发(默认 true)。
     *                       播放/加载类命令需要设置为 false 来启动服务。
     * @param extras Intent extra 写入块 (putExtra("adjust", ...) 等)
     */
    private inline fun sendAction(
        action: String,
        requireRunning: Boolean = true,
        extras: Intent.() -> Unit = {}
    ) {
        if (requireRunning && !AudioPlayService.isRun) return
        appCtx.startService<AudioPlayService> {
            this.action = action
            extras()
        }
    }

    // ---------- AudioPlayBookBridge ----------

    override fun saveRead(book: Book) {
        book.saveRead()
    }

    override fun save(book: Book) {
        book.save()
    }

    // 不走 book.getBookSource() 扩展: 那个扩展是 runBlocking, 调用链可能在主线程协程上
    override suspend fun getBookSource(book: Book): BookSource? = withContext(Dispatchers.IO) {
        appDb.bookSourceDao.getBookSource(book.origin)
    }
}

/**
 * 安卓宿主启动早期注册 AudioPlay 平台 provider。
 *
 * 调用时机: App.onCreate, 在 `registerAndroidWebBookProviders()` 之后
 * (AudioPlay 依赖 AppDbProviders 已注册, 虽然 AudioPlayCommander/BookBridge 不直接用 appDb,
 * 但 AudioPlayShared 内部通过 AppDbProviders.get().bookChapterDao 访问 DB)。
 *
 * 模式参考 `registerAndroidWebBookProviders` / `registerAndroidServiceLauncher`。
 */
fun registerAndroidAudioPlayProviders() {
    val impl = AudioPlayProvidersImpl
    AudioPlayCommanders.register(impl)
    AudioPlayBookBridges.register(impl)
}
