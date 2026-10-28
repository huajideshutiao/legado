package io.legado.app.ui.association

import io.legado.app.ui.association.LegadoDeepLink.parse
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * legado:// deep link 导入类型 (对照原版 `FileAssociationFragment` 的 `uri.path` 分支,
 * 一一对应)。
 */
enum class DeepLinkImportType {
    /** /bookSource 与 /rssSource 共用 (ImportBookSourceViewModelShared 兼容 OldRssSource) */
    BOOK_SOURCE,
    /** /replaceRule */
    REPLACE_RULE,
    /** 筛选规则 (无专属 path, 由自动识别进入) */
    SOURCE_FILTER_RULE,
    /** /textTocRule */
    TXT_TOC_RULE,
    /** /httpTTS */
    HTTP_TTS,
    /** /dictRule */
    DICT_RULE,
    /** /theme */
    THEME,
    /** /addToBookshelf (app 端 AddToBookshelfHelper.add) */
    ADD_TO_BOOKSHELF,
    /** /readConfig (app 端 getBytes + importReadConfig) */
    READ_CONFIG,
    /**
     * /read (书架直读): 传 src (书源书籍地址), 书已在书架 → 直接进阅读界面;
     * 不在书架 → 等同 addToBookshelf (抓详情进详情界面)。
     */
    READ_BOOK,
    /**
     * 未识别 path (app 端 else 分支 determineType): 自动识别链。先零网络按书源认领判这条地址
     * 是不是某源的书 (命中就转成 addToBookshelf 抓详情), 不认领才下载内容嗅探类型。
     */
    UNKNOWN,
}

private val nextRequestId = atomic(0L)

/** deep link 解析结果: 导入类型 + `src` 参数 (待导入内容的 URL 或纯 JSON 文本)。 */
data class DeepLinkImportRequest(
    val type: DeepLinkImportType,
    val src: String,
    val id: Long = nextRequestId.incrementAndGet(),
)

/**
 * legado://`/`yuedu://` deep link 解析器 (KMP commonMain, 各端共享)。
 *
 * # 背景
 *
 * app 端一键导入 (书源/替换规则/主题等) 走 AndroidManifest 的 AssociationActivity
 * intent-filter (scheme legado/yuedu) → 原版 `FileAssociationFragment` 按 `uri.path`
 * 分发到各 ImportXxxDialog。本类把该分发语义原样下沉 commonMain,
 * 各端 (desktop 启动参数 / iOS onOpenURL / 鸿蒙 Want.uri / Android 壳) 只做
 * "拿到 URL → 调 [parse]"。
 *
 * URL 形态 (与 app 端 Android Uri 解析行为一致):
 * - `legado://import/bookSource?src=<URL>` → host=import, path=/bookSource
 * - `legado://booksource/importonline?src=<URL>` → host=booksource, path=/importonline
 *   (老格式, 按 host 分流 booksource/rsssource→书源, replace→替换规则, 其余→UNKNOWN)
 * - `legado://import/read?src=<URL>` → [DeepLinkImportType.READ_BOOK]
 *   (书架直读: 已在书架直接阅读, 否则抓详情进详情页)
 *
 * # 与原版语义对照
 *
 * - scheme 精确匹配 "legado"/"yuedu" (原版 FileAssociationFragment 的相等判断);
 * - `src` 取查询串第一个匹配值, percent 解码 + '+'→空格 (Android
 *   `Uri.getQueryParameter` 的 UriCodec.decode(convertPlus=true) 行为);
 * - `#` 后内容按 fragment 截掉 (Android Uri 同);
 * - src 缺失/空 → 返回 null (原版该分支直接结束透明壳)。
 *
 * Android 深链同样经本类解析 (AssociationActivity/MainActivity 投递), 四端同一条链。
 */
object LegadoDeepLink {

    /** 是否为 legado 系 deep link (供各端在启动参数/openURL 回调里快速筛选)。 */
    fun isDeepLink(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("legado://") || trimmed.startsWith("yuedu://")
    }

    /**
     * 解析 deep link, 非 legado/yuedu scheme 或缺 `src` 参数返回 null。
     *
     * path→类型映射与原版 `FileAssociationFragment` 的 when 分支一致
     * (未识别 path 不返回 null 而是 [DeepLinkImportType.UNKNOWN],
     * 对应原版 else 分支 determineType 下载嗅探)。
     */
    /**
     * 解析 deep link, 非 legado/yuedu scheme (或显式允许的 app scheme) 或缺 `src` 参数返回 null。
     *
     * @param allowAppScheme 仅 Android 透明壳的外部入口传 true: 原版 `app://` 在线导入
     *   (带匹配文件类型/路径与非空 src) 由同一 path 映射分发; 其他端保持仅 legado/yuedu。
     */
    fun parse(url: String, allowAppScheme: Boolean = false): DeepLinkImportRequest? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd)
        if (scheme != "legado" && scheme != "yuedu" && !(allowAppScheme && scheme == "app")) return null
        // fragment 截掉 (Android Uri 的 query 不含 fragment)
        val rest = trimmed.substring(schemeEnd + 3).substringBefore('#')
        val queryStart = rest.indexOf('?')
        val hostPath = if (queryStart >= 0) rest.substring(0, queryStart) else rest
        val query = if (queryStart >= 0) rest.substring(queryStart + 1) else return null
        val slash = hostPath.indexOf('/')
        val host = if (slash >= 0) hostPath.substring(0, slash) else hostPath
        val path = if (slash >= 0) hostPath.substring(slash) else ""
        val src = getQueryParameter(query, "src")
        // src 缺失/空: 与原版透明壳的 isNullOrEmpty→结束壳 一致
        if (src.isNullOrEmpty()) return null
        val type = when (path) {
            "/bookSource", "/rssSource" -> DeepLinkImportType.BOOK_SOURCE
            "/replaceRule" -> DeepLinkImportType.REPLACE_RULE
            "/textTocRule" -> DeepLinkImportType.TXT_TOC_RULE
            "/httpTTS" -> DeepLinkImportType.HTTP_TTS
            "/dictRule" -> DeepLinkImportType.DICT_RULE
            "/theme" -> DeepLinkImportType.THEME
            "/addToBookshelf" -> DeepLinkImportType.ADD_TO_BOOKSHELF
            "/readConfig" -> DeepLinkImportType.READ_CONFIG
            "/read" -> DeepLinkImportType.READ_BOOK
            "/importonline" -> when (host) {
                "booksource", "rsssource" -> DeepLinkImportType.BOOK_SOURCE
                "replace" -> DeepLinkImportType.REPLACE_RULE
                else -> DeepLinkImportType.UNKNOWN
            }
            else -> DeepLinkImportType.UNKNOWN
        }
        return DeepLinkImportRequest(type, src)
    }

    /** 取查询串第一个匹配 key 的值并解码 (对照 Android Uri.getQueryParameter: 首个匹配 + 解码)。 */
    private fun getQueryParameter(query: String, key: String): String? {
        query.split('&').forEach { pair ->
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            if (k == key) {
                return if (eq >= 0) decodeQueryValue(pair.substring(eq + 1)) else ""
            }
        }
        return null
    }

    /**
     * percent 解码 + '+'→空格 (对照 Android UriCodec.decode(convertPlus=true) 的 UTF-8 语义)。
     * 非法 %XX 转义原样保留 (宽松处理, 用户手敲 URL 常见)。
     */
    private fun decodeQueryValue(encoded: String): String {
        val sb = StringBuilder(encoded.length)
        val byteBuf = ArrayList<Byte>()
        fun flushBytes() {
            if (byteBuf.isNotEmpty()) {
                sb.append(byteBuf.toByteArray().decodeToString())
                byteBuf.clear()
            }
        }
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            when {
                c == '%' && i + 2 < encoded.length -> {
                    val hi = encoded[i + 1].digitToIntOrNull(16)
                    val lo = encoded[i + 2].digitToIntOrNull(16)
                    if (hi != null && lo != null) {
                        byteBuf.add(((hi shl 4) or lo).toByte())
                        i += 3
                        continue
                    }
                    flushBytes()
                    sb.append(c)
                    i++
                }
                c == '+' -> {
                    flushBytes()
                    sb.append(' ')
                    i++
                }
                else -> {
                    flushBytes()
                    sb.append(c)
                    i++
                }
            }
        }
        flushBytes()
        return sb.toString()
    }
}

/**
 * deep link 待处理请求容器 (各端"投递侧"与"消费侧"的桥)。
 *
 * - 投递侧: desktop `main(args)`/macOS OpenURIHandler、iOS `handleLegadoDeepLink`
 *   (SwiftUI onOpenURL 转发)、鸿蒙 EntryAbility onCreate/onNewWant (napi handleDeepLink)
 *   拿到 URL 后调 [handle]; 文件关联分发调 [enqueue];
 * - 消费侧: 各端窗口的 DeepLinkImportHost 空闲时 [adopt] 接纳一条请求, 接纳后
 *   请求归该窗口私有 (正文/VM/工作/关闭回调都在窗口局部), 完成/取消时调 [consume]
 *   只结算自己的请求。
 *
 * 全局容器只保留**尚未被任何窗口接纳**的请求: [pending] 是首投位, [queue] 是排队;
 * 请求一经接纳即离开全局容器。这样每个请求唯一归属产生它的投递与接纳它的窗口,
 * 多窗口/多宿主不会互相吞掉对方的请求, 宿主销毁也不会清掉别人的或未投递的请求。
 *
 * 底层线程安全: 单项立即送达 UI, 批量关联文件或连续 deep link 依次排队,
 * 避免单槽 StateFlow 覆盖导致的"只导入最后一个文件"问题。
 */
object LegadoDeepLinkHandler {

    private val lock = SynchronizedObject()

    /** 尚未被接纳的排队请求。 */
    private val queue = ArrayDeque<DeepLinkImportRequest>()

    /** 尚未被接纳的首投请求 (null=无), 各端窗口空闲时据此接纳。 */
    private val _pending = MutableStateFlow<DeepLinkImportRequest?>(null)

    /** 待处理导入请求 (null=无), 各端窗口空闲时据此 [adopt] 接纳。 */
    val pending: StateFlow<DeepLinkImportRequest?> = _pending.asStateFlow()

    /**
     * 移交方登记的接纳回执 (持续状态): id → 是否已被任意宿主接纳。
     * 从 [registerHandoff] 登记起保留, 直到移交方自己观察到并 [releaseHandoff] 释放 ——
     * 因此 adopt→consume 快速连发也丢不了 (StateFlow 合并中间值也无所谓, 回执本体是状态)。
     */
    private val handoffAdopted = LinkedHashMap<Long, Boolean>()

    /** 接纳回执变化信号 (含初始值); 只用于唤醒, 判定一律读 [isHandoffAdopted] 的持续状态。 */
    private val _adoptionTick = MutableStateFlow(0L)
    val adoptionTick: StateFlow<Long> = _adoptionTick.asStateFlow()

    /**
     * 移交方在入队被接纳后登记本投递的接纳回执; 结清自己的账目后须 [releaseHandoff]。
     */
    fun registerHandoff(requestId: Long) {
        synchronized(lock) { handoffAdopted[requestId] = false }
    }

    /** 本投递是否已被任意宿主接纳 (持续状态, 不随 consume 丢失)。 */
    fun isHandoffAdopted(requestId: Long): Boolean =
        synchronized(lock) { handoffAdopted[requestId] ?: false }

    /** 移交方观察到接纳回执并结清账目后释放; 不释放会累积。 */
    fun releaseHandoff(requestId: Long) {
        synchronized(lock) { handoffAdopted.remove(requestId) }
    }

    /** 已被窗口接纳、进行中的请求 (id → 请求)。接纳后不在 pending/queue 里,
     *  但载荷仍参与 [enqueue] 去重: 进行中的同一载荷双通道再投一次不弹第二个框。 */
    private val adopted = LinkedHashMap<Long, DeepLinkImportRequest>()

    /** 解析并入队 deep link; 返回 false 表示非 legado 系 URL 或缺 src 参数 (未记录)。 */
    fun handle(url: String): Boolean {
        val request = LegadoDeepLink.parse(url) ?: return false
        enqueue(request)
        return true
    }

    /**
     * 将导入请求入队; 若当前无待处理请求则立即暴露给 UI, 否则排入队列等待接纳。
     * 线程安全, 支持批量文件或 deep link 连续投递逐个被接纳。
     *
     * 载荷去重 (类型 + src 结构相等): iOS 上同一份文档打开可能双通道各投一次 (见
     * iOSApp 外部 URI 闸门), 不拦就是连弹两个相同导入框; 待处理、排队与进行中
     * ([adopted]) 三处都参与比对, 消费完再审同一载荷, 属用户再次导入, 照常放行
     * (判据是确定性的比对, 不设时间窗)。
     *
     * @return [EnqueueResult.ACCEPTED] 请求已进入待处理容器, 调用方须等它被接纳并结算;
     *   [EnqueueResult.DUPLICATE] 同一载荷已有请求在处理, 本次投递无需再等结果
     */
    fun enqueue(request: DeepLinkImportRequest): EnqueueResult = synchronized(lock) {
        enqueueLocked(request)
    }

    /** 供 [enqueue] 复用的入队主体, 调用方必须已持 [lock]。 */
    private fun enqueueLocked(request: DeepLinkImportRequest): EnqueueResult {
        if (_pending.value?.samePayloadAs(request) == true) return EnqueueResult.DUPLICATE
        if (queue.any { it.samePayloadAs(request) }) return EnqueueResult.DUPLICATE
        if (adopted.values.any { it.samePayloadAs(request) }) return EnqueueResult.DUPLICATE
        if (_pending.value == null) {
            _pending.value = request
        } else {
            queue.addLast(request)
        }
        return EnqueueResult.ACCEPTED
    }

    /** 同一导入载荷: 类型与 src (待导入内容的 URL 或纯 JSON 文本) 均一致。 */
    private fun DeepLinkImportRequest.samePayloadAs(other: DeepLinkImportRequest): Boolean =
        type == other.type && src == other.src

    /**
     * 窗口空闲时接纳 [requestId] 对应的首投请求: 成功即把它移出全局容器
     * (pending 换成队首下一项或置 null) 并登记进行中载荷。只有窗口空闲时才调;
     * 多窗口并发抢同一条请求时恰好一个窗口返回 true。
     *
     * @return true 该请求已归调用窗口; false 无此请求/已被别的窗口接纳, 调用方不得渲染它
     */
    fun adopt(requestId: Long): Boolean {
        synchronized(lock) {
            val current = _pending.value ?: return false
            if (current.id != requestId) return false
            adopted[current.id] = current
            // 已登记回执的移交投递: 置"已接纳"并唤醒观察方 (回执本体保留到移交方释放)
            if (handoffAdopted.containsKey(current.id)) {
                handoffAdopted[current.id] = true
                _adoptionTick.value = _adoptionTick.value + 1
            }
            _pending.value = queue.removeFirstOrNull()
            return true
        }
    }

    /**
     * 结算自己接纳的请求 (导入完成/取消/流程收尾): 只移除 [requestId] 对应的进行中登记,
     * 不触碰 pending 与队列 —— 不匹配时不得吞掉其他窗口的请求, 未接纳的冷启动请求也不动。
     * 未知 id 为 no-op (该请求归属别的窗口或已被升级为新请求, 与本窗口无关)。
     */
    fun consume(id: Long) {
        synchronized(lock) {
            adopted.remove(id)
        }
    }

    /**
     * 丢弃本窗口投递但**尚未被任何宿主接纳**的请求 (真实退出): 清掉 pending 与排队项,
     * pending 被丢弃时把队首提升为新的首投。
     * 已接纳项不动: 归属已转移到接纳它的宿主, 只能由真实拥有者 [consume] 结算。
     */
    fun discard(requestId: Long) {
        synchronized(lock) {
            if (_pending.value?.id == requestId) {
                _pending.value = queue.removeFirstOrNull()
            } else {
                queue.removeAll { it.id == requestId }
            }
        }
    }

    /** 当前已接纳请求快照 (宿主重建时用于恢复本窗口会话, 不参与 pending/queue)。 */
    fun adoptedRequests(): List<DeepLinkImportRequest> = synchronized(lock) {
        adopted.values.toList()
    }

    fun adoptedRequest(id: Long): DeepLinkImportRequest? = synchronized(lock) {
        adopted[id]
    }

}

/** [LegadoDeepLinkHandler.enqueue] 的投递结果。 */
enum class EnqueueResult {
    /** 请求已进入待处理容器, 投递方须等它被接纳并结算。 */
    ACCEPTED,

    /** 同一载荷已有请求在处理, 本次投递无需再等结果。 */
    DUPLICATE,
}
