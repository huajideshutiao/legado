package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.chapter.updateResourceUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * 音视频章节资源直链预解析器 (前后各一章)。
 *
 * # 为什么不复用小说那套 preDownload
 * 小说/漫画的预下载把正文落盘, 章数由 `AppConfig.preDownloadNum` 控制 (默认 10);
 * 音视频的章节正文是**播放直链**, 且大多带时效签名, 预取多了到播的时候早失效,
 * 所以窗口写死前后各一章 (中心章 ±1), 不读任何配置项、不新增设置项。
 *
 * # 落点
 * 解析结果写 [BookChapter.resourceUrl] —— 音视频播放侧本来就是
 * `chapter.resourceUrl ?: getContentAwait(...)` 的复用口径 (原版 AudioPlayService.loadPlayUrl /
 * VideoViewModel.initChapter 均如此), 预解析只是把这一步提前。在架书同步单列 PATCH 落库,
 * 与已播章的落库行为一致。
 *
 * # 过期直链
 * 不做 TTL: 过期链由播放器报错触发的重解析自愈 (音频 `AudioPlaySession.onPlayerError` 首错静默
 * `refreshChapter`, 视频 `retryOnPlayError` → `refreshChapter`), 与原版语义一致。
 *
 * # 并发
 * 每次调 [preload] 先作废上一轮 (对标小说 `preDownloadTask?.cancel()`)。调用方须在**开始解析当前章之前**
 * 调 [cancel], 避免预解析与真实加载抢同一个书源、或抢同一章重复请求。
 *
 * @param scope 宿主作用域 (音频 = Service 生命周期, 视频 = 播放页 UI scope)
 */
class ResourceUrlPreloader(private val scope: CoroutineScope) {

    /** 当前预解析轮次, 每轮只跑前后两章。 */
    private var job: Job? = null

    /**
     * 预解析 [centerIndex] 前后各一章的资源直链。
     *
     * 跳过条件: 本地书 (无书源可解析)、越界 (首/末章)、卷名章、已有 [BookChapter.resourceUrl] 的章。
     * 先后章再前章 (后章更可能被用到), 顺序跑不并发, 免得为两条预取额外压书源。
     *
     * 单章失败只记日志不中断本轮: 预解析是提前量, 真正播到那一章时调用方还会自己解析一次,
     * 失败在 AppLog 里可见, 不静默。写库失败一并归在这里: 不拦的话异常会顺着宿主 scope
     * 冒上去 (音频 = Service 生命周期), 把不相干的播放会话一起拖死。
     *
     * @param chapters 内存目录 (可为 null / 缺章, 在架书缺章时按 index 回查 DAO)
     * @param inBookshelf 在架书才落库 (对齐播放侧已播章的落库条件)
     * @param fetch 章节内容解析 (音频不传 nextChapterUrl, 视频要传, 故由调用方给)
     */
    fun preload(
        book: Book,
        chapters: List<BookChapter>?,
        centerIndex: Int,
        inBookshelf: Boolean,
        fetch: suspend (BookChapter) -> String,
    ) {
        cancel()
        if (book.isLocal) return
        job = scope.launch(IoDispatcher) {
            for (index in intArrayOf(centerIndex + 1, centerIndex - 1)) {
                if (index < 0) continue
                currentCoroutineContext().ensureActive()
                val chapter = (
                    if (chapters != null) {
                        chapters.getOrNull(index)
                    } else if (inBookshelf) {
                        // 内存目录未就绪时只有在架书值得回查库: 非在架书不落库,
                        // 写在临时对象上的直链无处可留, 白跑一趟书源
                        AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, index)
                    } else {
                        null
                    }
                    ) ?: continue
                if (chapter.isVolume) continue
                if (chapter.resourceUrl != null) continue
                runCatching {
                    val content = fetch(chapter)
                    if (content.isEmpty()) return@runCatching
                    currentCoroutineContext().ensureActive()
                    // 直链回写 + 在架书 PATCH 落库 (与音视频播放侧共用 updateResourceUrl)
                    chapter.updateResourceUrl(content, inBookshelf)
                }.onFailure {
                    // evalJS 的 "JS执行出错" RuntimeException 包装会吞掉 CancellationException 身份,
                    // 仅靠类型判断拦不住被换壳的取消: 补查协程状态, 已取消的轮次按取消路径
                    // 静默退出, 不把切章/停播时的正常作废记成错误日志
                    if (it is CancellationException) throw it
                    currentCoroutineContext().ensureActive()
                    AppLog.put("预解析资源链接出错: ${chapter.title}\n$it", it)
                }
            }
        }
    }

    /** 作废当前轮次 (换章 / 停播 / 离开播放页)。 */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
