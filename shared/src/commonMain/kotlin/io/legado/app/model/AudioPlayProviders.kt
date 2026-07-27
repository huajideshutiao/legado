package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource

/**
 * 音频播放 Service 派发抽象 (shared commonMain)。
 *
 * # 背景
 * app 端 [AudioPlayShared] (原 `io.legado.app.model.AudioPlay` object) 通过
 * `appCtx.startService<AudioPlayService>` 派发命令到 Android Service, 依赖
 * `android.content.Context` / `Intent` / `splitties.init.appCtx`, 无法直接下沉。
 *
 * # 设计
 * - 接口方法与 app 端 `AudioPlay.sendAction(...)` 调用一一对应, 语义化命名
 *   (play/stop/pause/resume/...), 而非暴露 `sendAction(action, extras)` 原始形式,
 *   避免 commonMain 泄漏 Intent/extra 概念
 * - `requireRunning` 语义内化: 各方法实现内部判断是否需要 Service 运行
 *   (与 app 端 `sendAction(action, requireRunning = ...)` 行为对齐):
 *   - play/playNew/addTimer/loadPlayUrl: Service 未运行也会启动 (requireRunning=false)
 *   - stop/stopPlay/pause/resume/adjustSpeed/adjustProgress/setTimer: 仅 Service 运行时派发
 * - Android 实现见 `app/src/main/java/.../AudioPlayProvidersImpl.kt` 的 `AndroidAudioPlayCommander`,
 *   内部走 `appCtx.startService<AudioPlayService>` + IntentAction + extras, 与原 `sendAction` 完全等价
 * - 桌面端实现可直接 launch 协程执行等价任务 (无 Service 概念)
 *
 * 模式参考 [io.legado.app.help.service.ServiceLauncher]。
 */
interface AudioPlayCommander {

    /** AudioPlayService 是否运行 (对应 app 端 `AudioPlayService.isRun`) */
    val isServiceRunning: Boolean

    /**
     * Service 未启动时 setTimer 暂存目标分钟数 (对应 app 端 `AudioPlayService.pendingTimerMinute`)。
     *
     * Service onCreate 时读取此值装入 SleepTimer (见 AudioPlayService.onCreate)。
     */
    var pendingTimerMinute: Int

    /** 派发 play 命令 (requireRunning=false, Service 未运行时启动) */
    fun play()

    /** 派发 playNew 命令 (requireRunning=false, Service 未运行时启动) */
    fun playNew()

    /** 派发 stop 命令 (requireRunning=true, 仅 Service 运行时派发) */
    fun stop()

    /** 派发 stopPlay 命令 (requireRunning=true) */
    fun stopPlay()

    /** 派发 pause 命令 (requireRunning=true) */
    fun pause()

    /** 派发 resume 命令 (requireRunning=true) */
    fun resume()

    /** 派发 adjustSpeed 命令, 携带 Float extra (requireRunning=true) */
    fun adjustSpeed(adjust: Float)

    /** 派发 adjustProgress 命令, 携带 Int extra (requireRunning=true) */
    fun adjustProgress(position: Int)

    /** 派发 setTimer 命令, 携带 Int extra (requireRunning=true) */
    fun setTimer(minute: Int)

    /** 派发 addTimer 命令 (requireRunning=false, Service 未运行时启动) */
    fun addTimer()

    /** 派发 loadPlayUrl 命令 (requireRunning=false, Service 未运行时启动) */
    fun loadPlayUrl()
}

/**
 * [AudioPlayCommander] provider 容器 (与 [io.legado.app.data.AppDbProviders] 同模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate / desktop main), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 [IllegalStateException]。
 *
 * 安卓端调用 `registerAndroidAudioPlayCommander()` (见 app 模块 AudioPlayProvidersImpl.kt)。
 */
object AudioPlayCommanders {

    @Volatile
    private var impl: AudioPlayCommander? = null

    /** 宿主启动早期注册一次 (任何 AudioPlay 命令派发之前)。 */
    fun register(impl: AudioPlayCommander) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): AudioPlayCommander =
        impl ?: error("AudioPlayCommanders not registered; call registerAndroidAudioPlayCommander() first")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}

/**
 * AudioPlay 相关 Book 平台操作桥接 (shared commonMain)。
 *
 * # 背景
 * app 端 `AudioPlay.saveRead()` 调用 `book.saveRead()` / `AudioPlayService.onDestroy`
 * 调用 `AudioPlay.book?.save()` / `AudioPlay.resetData()` 调用 `book.getBookSource()`。
 * 这三个扩展位于 app 端 `BookExtensions.kt`, 依赖 `appDb` + `runBlocking` +
 * `System.currentTimeMillis()` (saveRead) / `appDb.bookSourceDao` (getBookSource)。
 *
 * 虽然底层依赖 (AppDbProviders / systemCurrentTimeMillis) 已下沉, 但 [Book.saveRead] /
 * [Book.save] / [Book.getBookSource] 扩展本身仍在 app 端, 下沉它们超出 AudioPlay 任务范围。
 * 故用本接口桥接, app 端实现委托现有扩展, 行为完全一致。
 *
 * 模式参考 [io.legado.app.help.book.BookHelpAccessor]。
 */
interface AudioPlayBookBridge {

    /**
     * 保存书籍阅读进度 (对应 app 端 `Book.saveRead()` 扩展)。
     *
     * PATCH 进度字段 (durChapterIndex/durChapterPos/durChapterTime/durChapterTitle) 到 bookDao,
     * 并 flush ReadTimeRecorder。
     */
    fun saveRead(book: Book)

    /**
     * 保存书籍整行 (对应 app 端 `Book.save()` 扩展)。
     *
     * removeType(notShelf) + insert/update bookDao。AudioPlayService.onDestroy 用。
     */
    fun save(book: Book)

    /**
     * 获取书籍对应 BookSource (对应 app 端 `Book.getBookSource()` 扩展)。
     *
     * runBlocking 查 bookSourceDao.getBookSource(origin)。
     */
    fun getBookSource(book: Book): BookSource?
}

/**
 * [AudioPlayBookBridge] provider 容器。
 *
 * 宿主启动早期注册一次, shared 内通过 [get] 获取。
 */
object AudioPlayBookBridges {

    @Volatile
    private var impl: AudioPlayBookBridge? = null

    /** 宿主启动早期注册一次 (任何 AudioPlay Book 操作之前)。 */
    fun register(impl: AudioPlayBookBridge) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): AudioPlayBookBridge =
        impl ?: error("AudioPlayBookBridges not registered; call registerAndroidAudioPlayBookBridge() first")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
