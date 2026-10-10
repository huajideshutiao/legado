package io.legado.app.ui.book.read

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.AppDbProviders
import io.legado.app.help.media.RemoteMediaCommand
import io.legado.app.help.media.SystemMediaControl
import io.legado.app.model.ActiveReadAloudHostPorts
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.model.AudioPlayCommanders
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.ReadBookPlatforms
import io.legado.app.model.ReadBookShared
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.postEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * 朗读快捷方式 / 朗读媒体键的跨端分派与"无活动阅读页"时的朗读宿主。
 *
 * # 原版形态 (archive d0c42f3242)
 * 快捷方式 `ShortCuts.buildReadAloudShortCutInfo` 给 `AssociationActivity` 塞
 * `action = "readAloud"`, 由它调 `MediaButtonReceiver.readAloud(appCtx, false)`; 媒体键走同一个
 * `readAloud(context, isMediaKey = true)`。该函数的分支顺序 (逐字):
 * ```
 * BaseReadAloudService.isRun         -> 暂停 / 继续 (并连带 AudioPlay.pause/resume)
 * AudioPlayService.isRun             -> 暂停 / 继续
 * isMediaKey && !readAloudByMediaButton -> 不处理
 * 阅读页在 (ReadBookActivity)          -> postEvent(MEDIA_BUTTON, true)      // 交页面切换
 * 音频页在 (AudioPlayActivity)         -> postEvent(MEDIA_BUTTON, true)      // 交页面切换
 * 都不在                              -> ReadAloud.upReadAloudClass()
 *                                        ReadBook.book != null ? ReadBook.readAloud()
 *                                        : appDb.bookDao.lastReadBook?.let {
 *                                              ReadBook.initData(it); ReadBook.clearTextChapter()
 *                                              ReadBook.loadContent(false) { ReadBook.readAloud() } }
 * ```
 * 末段**不打开阅读页**: 只在后台把这本最近阅读的书装到可朗读 (目录 + 正文 + 排版), 再开读。
 *
 * # 本仓为什么缺这一段, 以及这里复用了什么
 * 朗读宿主的章节/位置端口来自**活动阅读实例** ([ActiveReadAloudHostPorts]: 章节数据、位置流、
 * 切章、播控都在那里), 由 [ReadBookViewModelShared] 在 init 挂、onCleared 摘, 而
 * [ReadBookViewModelShared] 一直只由阅读页 ([ReaderScreenModel]) 创建 —— 没有阅读页就没有
 * 任何实例挂端口, 冷启动/后台按朗读键自然什么都不发生。
 *
 * 因此本节不新造任何装载/排版通路, 直接复用阅读页用的同一套编排:
 * [ReadBookShared] (跨平台阅读状态) + [ReadBookViewModelShared] (目录链 → 正文缓存/回源 →
 * ContentProcessor 处理 → 排版 → 回填三章滑窗), 只是没有页面与组合树 —— 朗读取到的仍是
 * 同一条排版产物, 章节换章、位置回写、通知书名/书内 TTS 引擎设置也随实例一起到位。
 * 排版几何取 [ReadBookViewModelShared] 构造参数的默认值 (阅读页会按窗口视口覆盖它)。
 *
 * 会话收束时机: 装载失败 (下次按下重试)、注册表被别的实例接管 (阅读页打开)。
 * 不随朗读结束销毁 —— 与 app 端 `ReadBook` 单例同语义, 朗读停止后实例留在原地待命。
 */
object HeadlessReadAloud {

    /** 一次无头朗读会话: 只持有阅读状态与它的装载编排。 */
    private class Session(
        val scope: CoroutineScope,
        val readBook: ReadBookShared,
        val viewModel: ReadBookViewModelShared,
    )

    private val lock = SynchronizedObject()

    @Volatile
    private var session: Session? = null

    /**
     * 朗读快捷方式的共享入口 (对照原版 `MediaButtonReceiver.readAloud(context, isMediaKey = false)`)。
     *
     * 分支顺序与上列原版逐条对齐, 平台相关的两处门槛 (`isMediaKey` 开关与
     * `mediaButtonOnExit / activitySize()`) 只对媒体键成立, 快捷方式恒放行, 故不在此列。
     * 朗读在跑时的暂停/继续交 [SystemMediaControl.onRemoteCommand] (朗读优先且连带切换有声书,
     * 与系统播控卡片、桌面任务栏媒体键同一份); 有声书的暂停/继续落到跨平台出口 [AudioPlayShared];
     * 都没有时交 [start]。
     */
    fun startFromShortcut() {
        val platform = ReadBookPlatforms.get()
        val audioRunning = AudioPlayCommanders.getOrNull()?.isServiceRunning == true
        when {
            // 朗读在跑: 暂停 / 继续 (连带切换有声书)
            platform.isReadAloudRun ->
                SystemMediaControl.onRemoteCommand(RemoteMediaCommand.TogglePlayPause)

            // 有声书在跑: 暂停 / 继续 (停止态按停止前的会话重新拉流, 与系统播控同一分支)
            audioRunning -> when (AudioPlayShared.status) {
                Status.PLAY -> AudioPlayShared.pause()
                Status.PAUSE -> AudioPlayShared.resume()
                else -> AudioPlayShared.loadOrUpPlayUrl()
            }

            // 音频页在栈顶: 交页面切换 (AudioPlayScreenModel 订阅 EventBus.MEDIA_BUTTON)
            AppNavigatorProviders.getOrNull()?.currentRoute is AppRoute.AudioPlay ->
                postEvent(EventBus.MEDIA_BUTTON, true)

            // 阅读页在 / 都不在: 由 [start] 分流 (前者交页面, 后者无头起播)
            else -> start()
        }
    }

    /**
     * 无活动阅读页时的"直接朗读": 从最近阅读的书无头准备正文并起朗读。
     *
     * 活动阅读实例已挂接 (阅读页开着) 时把按键交给它, 与原版 `LifecycleHelp.isExistActivity
     * (ReadBookActivity)` 分支同义 —— 不再造第二个实例。本对象自己的无头会话不算活动实例,
     * 否则第一次用过之后快捷方式就会永远转给一个不存在的页面。
     *
     * 已有无头会话时按当前进度重开 (对照原版 `ReadBook.book != null ? ReadBook.readAloud()`)。
     */
    fun start() {
        val current = session
        val attached = ActiveReadBookRegistry.current
        if (attached != null && attached !== current?.readBook) {
            ReadBookEvents.postMediaButton(true)
            return
        }
        if (current != null) {
            current.readBook.readAloud()
            return
        }
        launchSession()
    }

    private fun launchSession() {
        val scope = screenModelScope("无头朗读")
        val readBook = ReadBookShared()
        // 构造即挂朗读端口 (ReadBookViewModelShared.init), 与阅读页同一入口
        val viewModel = ReadBookViewModelShared(readBook, scope)
        val created = Session(scope, readBook, viewModel)
        synchronized(lock) { session = created }
        // 供朗读侧读"当前阅读实例": 书内 TTS 引擎设置、通知书名、Android 起播页号都读它
        ActiveReadBookRegistry.attach(readBook)
        scope.launch {
            val book = AppDbProviders.get().bookDao.lastReadBook()
            if (book == null) {
                close(created)
                return@launch
            }
            readBook.loadBook(book)
            val chapterIndex = readBook.durChapterIndexValue
            // 与阅读页同一入口装载: 目录(内存/库/回源) → 正文(缓存/回源) → 处理 → 排版
            viewModel.loadChapter(chapterIndex, keepScrollOffset = false)
            // 排版就绪信号: 滑窗当前章被回填 (失败也回填, 内容是占位提示页)
            val chapter = readBook.curTextChapter.first { it?.chapterIndex == chapterIndex }
            val message = chapter?.pages?.firstOrNull()
            if (chapter == null || message == null || message.isMsgPage) {
                // 未就绪不强行开读 (对照原版 readAloud 对未完成章节直接返回),
                // 会话作废以便下次按下重试
                AppLog.put("直接朗读: ${book.name} 正文未就绪\n${message?.text.orEmpty()}")
                close(created)
                return@launch
            }
            readBook.readAloud()
        }
        scope.launch {
            // 阅读页接手 (注册表换成别的实例) 时收掉无头会话: 端口与状态都由页面提供
            ActiveReadBookRegistry.currentFlow.collect { current ->
                if (current != null && current !== readBook) close(created)
            }
        }
    }

    /**
     * 作废会话: 停预下载 + 摘注册表 + 停协程。
     *
     * 预下载跑在自己的作用域 ([ReadBookViewModelShared] 的下载 scope), 不随下面 [CoroutineScope.cancel]
     * 一起停, 必须显式取消。朗读端口与阅读状态留给下一个活动实例接管 (都由注册表按引用判等),
     * 不在此处调 `onCleared` —— 它会顺手删掉未入架的最近阅读书, 而这里可能正被阅读页接手。
     */
    private fun close(target: Session) {
        val owned = synchronized(lock) {
            if (session !== target) return@synchronized false
            session = null
            true
        }
        if (!owned) return
        target.viewModel.cancelPreDownloadTask()
        ActiveReadBookRegistry.detach(target.readBook)
        target.scope.cancel()
    }
}
