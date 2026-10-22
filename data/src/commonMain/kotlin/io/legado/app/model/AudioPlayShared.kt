package io.legado.app.model

import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.model.AudioPlayShared.bookSourceOf
import io.legado.app.model.AudioPlayShared.durLrc
import io.legado.app.model.AudioPlayShared.resetData
import io.legado.app.model.audio.AudioSpectrum
import io.legado.app.model.chapter.ChapterProgressStore
import io.legado.app.model.chapter.resolveChapter
import io.legado.app.utils.postEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * 音频播放跨组件共享状态 + Service 派发包装 (KMP shared/commonMain 版本)。
 *
 * 原 app 端 `io.legado.app.model.AudioPlay` object 整体下沉, app 端通过
 * `typealias AudioPlay = AudioPlayShared` 保持 100+ 调用点零改动。
 *
 * # 平台差异处理
 * - **Service 派发**: 原 `sendAction(action) { putExtra(...) }` 走
 *   `appCtx.startService<AudioPlayService>`, 依赖 Android Context/Intent/splitties,
 *   抽象为 [AudioPlayCommander] 接口, app 端注册 `AudioPlayProvidersImpl` 实现
 *   (内部仍走 startService<AudioPlayService> + IntentAction + extras, 行为完全等价)。
 * - **Book 操作**: `book.saveRead()` / `book.save()` 已是 [Book] 的 commonMain 成员, 四端直接调;
 *   书源查询走 [bookSourceOf] (suspend DAO, 不用 runBlocking 的 `Book.getBookSource()` 扩展)。
 * - **PlayMode.iconRes**: R.drawable.* 是 Android 资源 ID, enum 构造参数无法跨平台。
 *   下沉后 PlayMode 不含 iconRes, app 端通过扩展属性 `PlayMode.iconRes` 提供
 *   (见 app 端 AudioPlay.kt), 调用方 `playMode.iconRes` 语法不变 (Kotlin 扩展属性与
 *   构造属性访问语法一致)。
 * - **appDb**: 改为 `AppDbProviders.get().bookChapterDao` (已下沉 provider)。
 * - **进度落库**: 走 [io.legado.app.model.chapter.ChapterProgressStore] (四模式共用,
 *   内部经 provider 访问 DAO 与标题替换规则)。
 *
 * # 保留不动的逻辑 (与 app 端完全一致)
 * - 跨 Activity / Service 的可见状态 (book, chapter, durPlayUrl, lrc 等)
 * - 与 state 强耦合的写入操作 (saveRead, saveDurChapter, upDurChapter, playPositionChanged)
 * - 章节切换核心逻辑 (prev/next/skipTo/setProgress, playMode 决定 newIndex)
 *
 * # 不在这里 (与 app 端一致)
 * - 歌词解析: 见 [LrcParser]
 * - 加载播放 URL / 封面 / 歌词: 见 [io.legado.app.model.audio.AudioPlayManager]
 * - 播放会话状态机 (起播/暂停/倍速/定时/引擎回调): 见
 *   [io.legado.app.model.audio.AudioPlaySession], 平台接入见 `AudioPlaySessionHost`
 *
 * UI 更新一律通过 EventBus 推送 (AUDIO_COVER / AUDIO_LOADING / ...),
 * 不持有 Activity 引用以避免内存泄漏。歌词是例外: 见 [durLrc]。
 *
 * UI 命令面应通过 AudioPlayViewModel, 而不是直接调本对象。
 */
object AudioPlayShared {
    /** 可设倍速区间 (UI 滑块同区间; 0.1X ~ 3.0X)。 */
    const val SPEED_MIN = 0.1f
    const val SPEED_MAX = 3f
    const val SPEED_DEFAULT = 1f

    /**
     * 播放模式 (跨平台版本, 不含 iconRes)。
     *
     * app 端通过扩展属性 `val PlayMode.iconRes: Int` 提供图标资源 ID
     * (R.drawable.ic_play_mode_*), 调用方 `playMode.iconRes` 语法不变。
     */
    enum class PlayMode {
        LIST_END_STOP,
        SINGLE_LOOP,
        RANDOM,
        LIST_LOOP;

        fun next(): PlayMode = when (this) {
            LIST_END_STOP -> SINGLE_LOOP
            SINGLE_LOOP -> RANDOM
            RANDOM -> LIST_LOOP
            LIST_LOOP -> LIST_END_STOP
        }
    }

    var playMode = PlayMode.LIST_END_STOP
    var status = Status.STOP

    /**
     * 播放速率 (由 [io.legado.app.model.audio.AudioPlaySession] 写入)。
     *
     * 是共享状态而不是事件, 两个原因: [io.legado.app.model.audio.LyricPublisher] 要按它把
     * "到下一行还有多少歌曲时间"换算成墙上时间, 而会话起始沿用上一轮倍速时并不会 post
     * AUDIO_SPEED; 且 Android 的会话随 Service 重建, 倍速要靠这里跨会话存活。
     */
    var playSpeed: Float = SPEED_DEFAULT
        /**
         * 无效倍速一律拦在写入处: 播控引擎把 "speed > 0" 当硬约束 (Media3 的
         * PlaybackParameters 直接抛 IllegalArgumentException), 且这个值是跨会话存活的,
         * 一旦写进 0 就会在之后每次"引擎就绪重设倍速"时把已终结的调用链炸掉。
         */
        set(value) {
            field = if (value.isNaN()) SPEED_DEFAULT else value.coerceIn(SPEED_MIN, SPEED_MAX)
        }
    var book: Book? = null
    var chapterSize = 0
    var simulatedChapterSize = 0
    var chapterList: List<BookChapter>? = null
    var durChapterIndex = 0
    var durChapterPos = 0
    var durChapter: BookChapter? = null
    var durPlayUrl = ""
    var durCoverUrl: String? = null

    /**
     * 一帧音频可视信号: 整体能量 + 各频段幅度。
     *
     * 派发方必须保证每帧都是新实例, 不能改旧实例的字段: 订阅方把"收到一帧"当作
     * "管道还在供数"的依据 (相同数值也必须照常通知), 并用 [bands] 换数组建立重绘依赖。
     *
     * @param energy 整体能量 0~1: 供歌词辉光
     * @param bands 各频段幅度 0~1 (长度 [AudioSpectrum.BAND_COUNT]): 供页面边缘光效按段驱动
     */
    class AudioVisualFrame(
        val energy: Float,
        val bands: FloatArray,
    )

    /**
     * 底层播放器实时音频可视信号。
     *
     * 由底层播放器真实音频物理 PCM 驱动: [AudioVisualFrame.energy] 用于实时调制歌词微弱柔光与
     * 背景流光的呼吸收放, [AudioVisualFrame.bands] 供页面边缘光效按频段分别驱动 (低频、人声、
     * 高频各占不同位置), 两者同源但口径不同。
     *
     * 无频谱能力的端 (桌面走播放器峰值电平、鸿蒙只给整体幅度) 用整体电平填满各段 ——
     * 如实退化, 不伪造频谱。
     *
     * 只在播放器真的产出 PCM 时更新; 暂停后值停在最后一帧, 所以**显示侧自己按播放状态
     * 收到 0**(见 AudioEnergyEnvelope 的两个使用方), 不依赖谁去清零。
     */
    val audioVisual = MutableStateFlow(
        AudioVisualFrame(0f, FloatArray(AudioSpectrum.BAND_COUNT))
    )

    /**
     * 当前章节歌词 (null = 无歌词/未就绪)。
     *
     * 用 StateFlow 而不是 sticky 事件: 歌词是状态不是通知, 订阅即拿到当前值, 换章置 null、
     * 到货置新值, 没有"空列表"这种中间态。当前高亮行不在此处发布 —— 它是
     * (歌词, 播放位置) 的派生量, 由消费方按需求值 (见 [Lrc.indexAt])。
     */
    val durLrc = MutableStateFlow<Lrc?>(null)

    /**
     * 界面当前歌词所属的书与章。
     *
     * 同一章被重复装载 (播放出错重试等) 时据此免去重复清空: 清空再发布同一份歌词会让界面
     * 把歌词行表与高亮索引整体重建 (表现为歌词闪一下)。
     */
    private var publishedLrcKey: Pair<String, Int>? = null

    /**
     * 跳转信号: [epoch] 每次跳转自增 (驱动歌词调度与车载发布循环重算),
     * [targetMs] 是本次跳转的目标位置 (null = 无跳转目标)。
     *
     * 目标位置与信号同源发布: 播放侧的位置真源要等跳转命令送达才带上目标值, 歌词调度据此在
     * 跳转当帧就按目标定位 (见 [SeekTargetTracker])。
     */
    data class SeekSignal(val epoch: Int, val targetMs: Int? = null)

    val seekSignal = MutableStateFlow(SeekSignal(0))

    /**
     * 跳转目标追踪器 (歌词调度用): 本次重算由新跳转引起时给出目标位置, 其余情况给出 null
     * (调用方回读位置真源)。
     *
     * 纪元与上次相同即该次重算与跳转无关 (暂停/变速/换章/歌词到货); 首次见到信号只记纪元
     * (那是页面/会话打开前留下的目标位置)。
     */
    class SeekTargetTracker {
        private var epoch: Int? = null

        /** [signal] 是新跳转时返回其目标位置, 否则 null。 */
        fun targetOf(signal: SeekSignal): Int? {
            val previous = epoch
            epoch = signal.epoch
            return signal.targetMs.takeIf { previous != null && previous != signal.epoch }
        }
    }

    /** 跳转未落地期间的重算间隔 (毫秒): 位置真源被跳转目标钉住时的唤醒上限。 */
    const val SEEK_POLL_MS = 250L

    /**
     * 歌词调度的下一次唤醒时长 (毫秒): [remainMs] 为到下一行的墙上等待。
     *
     * 跳转尚未被位置真源采纳 ([truthMs] 仍停在 [seekTargetMs]) 时封顶为 [SEEK_POLL_MS] ——
     * 真源抖动或落地判定走超时兜底都能在一个短间隔内被发现; 真源反映引擎真实位置后按行精准睡眠。
     */
    fun lyricWakeUpMs(remainMs: Long, truthMs: Int, seekTargetMs: Int?): Long =
        if (seekTargetMs != null && truthMs == seekTargetMs) {
            remainMs.coerceAtMost(SEEK_POLL_MS)
        } else {
            remainMs
        }

    var durAudioSize = 0
    var inBookshelf = false
    var bookSource: BookSource? = null

    // ---------- Service 派发 (经 AudioPlayCommander 抽象, 平台差异见接口注释) ----------

    fun play() = AudioPlayCommanders.get().play()

    fun stop() {
        AudioPlayCommanders.get().stop()
    }

    fun stopPlay() {
        AudioPlayCommanders.get().stopPlay()
    }

    fun pause() {
        saveRead()
        AudioPlayCommanders.get().pause()
    }

    fun resume() = AudioPlayCommanders.get().resume()

    fun adjustSpeed(adjust: Float) =
        AudioPlayCommanders.get().adjustSpeed(adjust)

    fun adjustProgress(position: Int) {
        durChapterPos = position
        seekSignal.update { SeekSignal(it.epoch + 1, position) }
        AudioPlayCommanders.get().adjustProgress(position)
    }

    fun setTimer(minute: Int) {
        val commander = AudioPlayCommanders.get()
        if (commander.isServiceRunning) {
            commander.setTimer(minute)
        } else {
            commander.pendingTimerMinute = minute
            postEvent(EventBus.AUDIO_DS, minute)
        }
    }

    fun addTimer() = AudioPlayCommanders.get().addTimer()

    /**
     * 发布一帧音频可视信号 (能量 + 各频段)。
     *
     * [bands] 会被复制一份, 调用方可以直接传自己复用的数组 (不要依赖“必须拷贝”的口头约定);
     * 传 null 表示该帧没有新的频段值, 沿用上一帧的。
     */
    fun publishVisual(energy: Float, bands: FloatArray?) {
        val previous = audioVisual.value
        audioVisual.value = AudioVisualFrame(energy, bands?.copyOf() ?: previous.bands)
    }

    /**
     * 无频谱能力的端 (桌面 / 鸿蒙): 用整体电平填满各段 —— 如实退化, 不伪造频谱。
     *
     * 也是“没有信号了”(停止/拆链/flush) 的清零入口。
     */
    fun publishLevelOnly(energy: Float) {
        audioVisual.value = AudioVisualFrame(energy, FloatArray(AudioSpectrum.BAND_COUNT) { energy })
    }

    /**
     * 按两个辉光开关同步底层 offload 策略 (开关切换后调用, 无需重起播)。
     *
     * 采样旁路常挂, offload 是运行时参数: 都关时省电, 任一开启时取 PCM 供光效使用。
     */
    fun syncAudioOffload() = AudioPlayCommanders.get().syncAudioOffload()

    /**
     * 两个辉光开关任一开启即需要 PCM 采样。
     *
     * 这是“要不要关解码省电”的唯一判定处: 播放服务、界面开关都只看这里, 不再各自拼条件。
     */
    fun needsAudioPcm(): Boolean {
        val prefs = PreferenceProviders.get()
        return prefs.getBoolean(PreferKey.audioEnergyGlow, PreferKey.audioGlowDefault) ||
            prefs.getBoolean(PreferKey.audioEdgeGlow, PreferKey.audioGlowDefault)
    }

    /**
     * 触发 Service 加载当前章节的播放 URL / 封面 / 歌词。
     * Service 未运行时此调用会启动 Service。
     */
    fun loadOrUpPlayUrl() {
        if (durPlayUrl.isEmpty()) {
            AudioPlayCommanders.get().loadPlayUrl()
        } else {
            play()
        }
    }

    // ---------- 状态变更 ----------

    fun changePlayMode() {
        playMode = playMode.next()
        postEvent(EventBus.PLAY_MODE_CHANGED, playMode)
    }

    suspend fun upData(book: Book, source: BookSource?) {
        if (durChapterIndex != book.durChapterIndex || this.book?.bookUrl != book.bookUrl) {
            resetData(book, source)
            return
        }
        // 重进同书不走 resetData (它会 stop() 打断播放), 但书籍快照要换成新的。
        // 原版 upData 也不写 this.book —— 这里刻意加, 因为目录弹窗现在收
        // AudioPlayShared.book 作"现行书籍"(换源后 route 快照的 bookUrl 已失效),
        // 而目录的显示上界读 book.totalChapterNum: 单例里的书一陈旧, 新增章节就被截掉
        this.book = book
        // 目录已就位时重算章节计数, 否则 simulatedChapterSize 停留在
        // 旧值(首次进入目录未就绪时为 0), 播放完 next() 静默 return → 不切下一首 (对照 origin resetData)
        if (!chapterList.isNullOrEmpty()) {
            chapterSize = chapterList!!.size
            simulatedChapterSize = if (book.readSimulating()) {
                book.simulatedTotalChapterNum()
            } else {
                chapterList!!.size
            }
        }
        durCoverUrl?.let { postEvent(EventBus.AUDIO_COVER, it) }
        postEvent(EventBus.AUDIO_PROGRESS, durChapterPos)
    }

    /**
     * @param source 书籍对应书源, 由调用方从书籍装载流程取得
     */
    suspend fun resetData(book: Book, source: BookSource?) {
        // 换书/换源只停当前播放, 服务保持存活接着装新书: 停服务会让平台在销毁回调与下一条命令
        // 之间复用同一个服务实例, 而播放器已随销毁释放, 后续装载与起播会全部落在已释放的播放器上
        stopPlay()
        status = Status.STOP
        this.book = book
        ReadTimeRecorder.setBook(ReadTimeRecorder.Source.AUDIO, book.name)
        if (chapterList?.firstOrNull()?.bookUrl != book.bookUrl) {
            chapterList = null
        }
        chapterSize = chapterList?.size ?: withContext(IoDispatcher) {
            AppDbProviders.get().bookChapterDao.getChapterCount(book.bookUrl)
        }
        simulatedChapterSize =
            if (book.readSimulating()) book.simulatedTotalChapterNum() else chapterSize
        bookSource = source
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        durPlayUrl = ""
        durAudioSize = 0
        upDurChapter()
        postEvent(EventBus.AUDIO_BUFFER_PROGRESS, 0)
    }

    /**
     * 目录到货后同步章节计数 (对齐 [resetData] 语义, 参照 ReadBook.updateChapterList)。
     *
     * 进入播放页时若 DB 无目录, [resetData] 只能把 simulatedChapterSize 算到 0,
     * 切章按钮可用性与 skipTo 边界会永久失效; 异步回源成功后必须调用本函数重算。
     */
    fun updateChapterList(list: List<BookChapter>) {
        val cur = book ?: return
        // 换书竞态防护: 目录与当前书不匹配时忽略 (对齐 resetData 的 bookUrl 校验),
        // 防旧书回源协程晚到污染新书的计数/目录
        if (list.firstOrNull()?.bookUrl != cur.bookUrl) return
        chapterList = list
        chapterSize = list.size
        // 模拟追读按日解锁章节数 (与 resetData 一致)
        simulatedChapterSize = if (cur.readSimulating()) {
            cur.simulatedTotalChapterNum()
        } else {
            list.size
        }
    }

    suspend fun upDurChapter() {
        val book = book ?: return
        // 内存目录优先, 库兜底 (实现已收敛至 resolveChapter, 四模式共用; 原 chapterList?.get()
        // 在目录滞后于 durChapterIndex 时会抛 IndexOutOfBounds)
        durChapter = withContext(IoDispatcher) {
            resolveChapter(book, durChapterIndex, chapterList)
        }
        durAudioSize = durChapter?.end?.toInt() ?: 0
        val lrcKey = book.bookUrl to durChapterIndex
        if (publishedLrcKey != lrcKey) {
            publishedLrcKey = lrcKey
            durLrc.value = null
        }
        val title = durChapter?.title ?: appString(AppStringKey.data_loading)
        postEvent(EventBus.AUDIO_SUB_TITLE, title)
        postEvent(EventBus.AUDIO_SIZE, durAudioSize)
        postEvent(EventBus.AUDIO_PROGRESS, durChapterPos)
    }

    /**
     * 应用云端进度: 章节变化则 skipTo 并保留新的 pos, 同章节仅调 adjustProgress
     */
    fun setProgress(progress: BookProgress) {
        if (progress.durChapterIndex >= simulatedChapterSize) return
        if (durChapterIndex == progress.durChapterIndex
            && durChapterPos == progress.durChapterPos
        ) return
        if (durChapterIndex != progress.durChapterIndex) {
            // 切章 + 起始位置一次到位: 分两步会把章头位置先落库/落书 (saveRead 与后续赋值竞态),
            // 云端进度下来后从章头起播
            skipTo(progress.durChapterIndex, progress.durChapterPos)
        } else {
            adjustProgress(progress.durChapterPos)
        }
    }

    fun skipTo(index: Int, startPos: Int = 0) {
        if (index !in 0..<simulatedChapterSize) return
        ReadTimeRecorder.flushAll()
        stopPlay()
        durChapterIndex = index
        durChapterPos = startPos
        // 同步落到 book, 否则 Service 读 book.durChapterPos 会拿到旧值(saveRead 是 async 落库)
        book?.durChapterPos = startPos
        book?.durChapterIndex = index
        durPlayUrl = ""
        saveRead()
        AudioPlayCommanders.get().loadPlayUrl()
    }

    fun prev() {
        if (durChapterIndex <= 0) return
        ReadTimeRecorder.flushAll()
        stopPlay()
        durChapterIndex -= 1
        durChapterPos = 0
        durPlayUrl = ""
        // 同步落到 book (saveRead 是 async 落库): doLoadPlayUrl 的章节作废校验
        // (chapter.index != book.durChapterIndex) 读 book 即时值, 不同步会误判新章
        // 作废 → LOADING 残留无限转圈 (对齐 skipTo 的同步写法)
        book?.durChapterIndex = durChapterIndex
        book?.durChapterPos = durChapterPos
        saveRead()
        AudioPlayCommanders.get().loadPlayUrl()
    }

    fun next(): Boolean {
        // 目录未就绪/为空时 RANDOM 分支 `(0 until 0).random()` 会抛异常, 防御
        if (simulatedChapterSize <= 0) return false
        val newIndex = when (playMode) {
            PlayMode.LIST_END_STOP ->
                if (durChapterIndex + 1 < simulatedChapterSize) durChapterIndex + 1 else return false
            PlayMode.SINGLE_LOOP -> durChapterIndex
            PlayMode.RANDOM -> (0 until simulatedChapterSize).random()
            PlayMode.LIST_LOOP -> (durChapterIndex + 1) % simulatedChapterSize
        }
        ReadTimeRecorder.flushAll()
        stopPlay()
        durChapterIndex = newIndex
        durChapterPos = 0
        durPlayUrl = ""
        // 同步落到 book (saveRead 是 async 落库): 同 prev(), 防 doLoadPlayUrl 的
        // 章节作废校验误判 → LOADING 残留无限转圈 (对齐 skipTo 的同步写法)
        book?.durChapterIndex = durChapterIndex
        book?.durChapterPos = durChapterPos
        saveRead()
        AudioPlayCommanders.get().loadPlayUrl()
        return true
    }

    fun saveRead() {
        Coroutine.async { saveReadAwait() }
    }

    /**
     * [saveRead] 的可等待形式: 落库完成才返回。
     *
     * 供退出流程在结束进程前确保当前位置已写回库 (异步版会在进程结束前被截断)。
     */
    suspend fun saveReadAwait() {
        val book = book ?: return
        // 落库 + 回写内存 book 实体 + 通知书架重查 (实现已收敛至 [ChapterProgressStore], 四模式共用)。
        // 章名以当前 durChapter 为准: next/prev/skipTo 已预同步 book.durChapterIndex,
        // 若按 "book.durChapterIndex != durChapterIndex" 判断章节是否变化则恒为 false,
        // durChapterTitle 会永远停在旧章标题 (回归 2026-08)。
        ChapterProgressStore.save(
            book = book,
            durChapterIndex = durChapterIndex,
            durChapterPos = durChapterPos,
            chapter = durChapter,
        )
        postEvent(EventBus.UP_BOOKSHELF, book.bookUrl)
    }

    /**
     * 保存章节长度
     */
    fun saveDurChapter(audioSize: Long) {
        val chapter = durChapter ?: return
        Coroutine.async {
            durAudioSize = audioSize.toInt()
            chapter.end = audioSize
            // 只 PATCH end 列; 整行 update 会冲掉正文解析等并发写入的章节字段
            if (!book!!.isNotShelf) {
                AppDbProviders.get().bookChapterDao.upEnd(chapter.bookUrl, chapter.url, chapter.end)
            }
        }
    }

    fun playPositionChanged(position: Int) {
        durChapterPos = position
    }

    /**
     * 书籍对应书源。
     *
     * 不用 `Book.getBookSource()`: 那个是 runBlocking 扩展, 而调用方可能从主线程协程进入。
     */
    suspend fun bookSourceOf(book: Book): BookSource? = withContext(IoDispatcher) {
        AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
    }

}

/**
 * 音频页封面兜底值: 书籍封面 (空白视为无)。
 *
 * 封面地址由装载流程产生, 进入页面首帧拿不到; 界面层与页面状态层都以本值为兜底,
 * 取值只此一处, 两处不会走偏。
 */
fun Book.audioCoverFallback(): String? = getDisplayCover()?.takeIf { it.isNotBlank() }
