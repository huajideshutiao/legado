package io.legado.app.ui.file

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.AppLog
import io.legado.app.utils.SelectImageContract
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 一个宿主 Activity 内的文件选择会话: 承载「选择方式弹窗」([HandleFileDialog]) 与三个系统
 * 选择器通道, 由宿主 Activity 的字段持有, 随宿主存亡。
 *
 * 每次 [launch][HandleFileLauncher.launch] 是一个独立请求: 参数、弹窗与结果回调都归属该次请求,
 * 并发请求的结果互不串。同一系统通道一次只有一个在途系统调用, 新请求排队等旧调用真实回传
 * (Mutex 在宿主主线程上锁定/启动/释放)。
 *
 * # 配置重建
 * - 带稳定 [key] 的请求 (发起方在重建后仍是同一个宿主, 如主界面的导入/图片目录选择)
 *   写入宿主状态并在新宿主重建后**重新绑定**: 未启动的弹窗重新展示, 已启动的系统结果交给
 *   同 key 的新回调;
 * - 无 key 的请求发起方已随页面/协程消亡, 重建后只作废: 系统旧结果只排空, 不交给新请求。
 * - 不会释放一个当前调用未持有的锁。
 */
class HandleFileSession(private val activity: AppCompatActivity) {

    /** 所属宿主: 会话随宿主存亡, 构造后不再更换。 */
    val hostActivity: AppCompatActivity get() = activity

    /** 本宿主进行中的文件请求: 每次 launch 一个, 收口即移除。 */
    private val requests = mutableSetOf<Request>()

    /** 重建后等待同 key 入口重新绑定的请求 (同一 key 可对应多个独立未完请求)。 */
    private val keyedRequests = mutableMapOf<String, MutableList<Request>>()

    // 同通道一次只有一个在途系统调用: held 与 Mutex 成对, 真实回传才释放
    private val treeMutex = Mutex()
    private val docMutex = Mutex()
    private val imageMutex = Mutex()
    private var treeHeld = false
    private var docHeld = false
    private var imageHeld = false

    // 无 key 旧请求的重建排空标记: 旧结果到达时只消费, 不交付
    private var drainTree = false
    private var drainDoc = false
    private var drainImage = false

    private var treeInFlight: Request? = null
    private var docInFlight: Request? = null
    private var imageInFlight: Request? = null

    // 必须走带 lifecycleOwner 的重载: ON_START 才补投待回传结果, ON_DESTROY 自动注销,
    // 注册时刻因此不必依赖 Activity Result 的实例状态还原进度
    private val selectDocTree: ActivityResultLauncher<Uri?> =
        activity.activityResultRegistry.register(
            SYS_TREE_KEY, activity, ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            val request = treeInFlight
            treeInFlight = null
            releaseTree()
            if (drainTree) {
                drainTree = false
                return@register
            }
            if (request != null && request.isActive) {
                if (uri != null && uri.isContentScheme()) {
                    val modeFlags =
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    try {
                        activity.contentResolver.takePersistableUriPermission(uri, modeFlags)
                    } catch (e: Exception) {
                        // 提供方未给可持久 grant/系统撤权: 本请求真实失败终结, 不是用户取消
                        request.holdOrDeliverError(e)
                        return@register
                    }
                }
                request.holdOrDeliver(uri)
            }
        }

    private val selectDoc: ActivityResultLauncher<Array<String>> =
        activity.activityResultRegistry.register(
            SYS_DOC_KEY, activity, ActivityResultContracts.OpenDocument()
        ) { uri ->
            val request = docInFlight
            docInFlight = null
            releaseDoc()
            if (drainDoc) {
                drainDoc = false
                return@register
            }
            if (request != null && request.isActive) {
                if (uri != null && uri.isContentScheme()) {
                    val modeFlags =
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    try {
                        activity.contentResolver.takePersistableUriPermission(uri, modeFlags)
                    } catch (e: Exception) {
                        // 提供方未给可持久 grant/系统撤权: 本请求真实失败终结, 不是用户取消
                        request.holdOrDeliverError(e)
                        return@register
                    }
                }
                request.holdOrDeliver(uri)
            }
        }

    private val selectImage: ActivityResultLauncher<Int?> =
        activity.activityResultRegistry.register(
            SYS_IMAGE_KEY, activity, SelectImageContract()
        ) { result ->
            val request = imageInFlight
            imageInFlight = null
            releaseImage()
            if (drainImage) {
                drainImage = false
                return@register
            }
            if (request != null && request.isActive) {
                request.holdOrDeliver(result.uri)
            }
        }

    init {
        // 宿主销毁: 取消全部等待 (等待协程挂在宿主 lifecycleScope, ON_DESTROY 自行取消),
        // 关闭本宿主创建的全部文件请求弹窗; 正常后台/系统选择器覆盖期间不关
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            // 配置重建不是真退出: 请求与暂存回执随 Bundle/进程级登记交给新宿主,
            // 这里不能 finish (否则"跨重建保留"的回执会被自己删掉)
            if (event == Lifecycle.Event.ON_DESTROY && !activity.isChangingConfigurations) {
                requests.toList().forEach { it.finish() }
            }
        })
    }

    /**
     * 恢复重建前带 key 的请求: 未启动的重新展示弹窗, 已启动的系统结果交给同 key 回调;
     * 无 key 的旧通道占用只排空 (原发起方已随旧宿主消亡)。
     *
     * 由宿主在 onCreate 内、系统结果补投 (ON_START) 之前调用。
     */
    fun restoreFrom(state: Bundle?) {
        if (state == null) return
        val count = state.getInt(KEY_REQ_COUNT, 0)
        for (i in 0 until count) {
            val key = state.getString(reqKey(KEY_REQ_KEY, i)) ?: continue
            val param = HandleFileContract.HandleFileParam(
                mode = state.getInt(reqKey(KEY_REQ_MODE, i), HandleFileContract.DIR),
                title = state.getString(reqKey(KEY_REQ_TITLE, i)),
                allowExtensions = state.getStringArray(reqKey(KEY_REQ_EXT, i)) ?: arrayOf(),
                requestCode = state.getInt(reqKey(KEY_REQ_CODE, i), 0),
                value = state.getString(reqKey(KEY_REQ_VALUE, i)),
            )
            val bookUrl = state.getString(reqKey(KEY_REQ_BOOK, i))
            val awaiting = state.getBoolean(reqKey(KEY_REQ_AWAITING, i), false)
            val channel = state.getString(reqKey(KEY_REQ_CHANNEL, i))
            val placeholder = HandleFileLauncher(this, {}, key)
            val request = Request(
                launcher = placeholder,
                param = param,
                callback = {},
                key = key,
                bookUrl = bookUrl,
            )
            request.awaitingChannel = channel
            // 重建必须沿用原请求 id: 否则暂存回执按新 id 找不到, 等于丢失
            state.getLong(reqKey(KEY_REQ_ID, i), 0L).takeIf { it > 0 }?.let { request.requestId = it }
            // 占位 callback 是空的: 真实回执 (ON_START 补投早于组合) 先到必须暂存,
            // 留到同 key 有效入口 bindKey 时交付, 否则原等待协程永挂
            request.awaitingRebind = true
            // 上一次重建期间已暂存的真实回执: 原样取回 (原异常对象/取消终态都保真)
            request.pendingResult = heldResults[request.requestId]
            requests.add(request)
            keyedRequests.getOrPut(key) { mutableListOf() }.add(request)
            if (awaiting) {
                // 系统调用仍在途: 结果交给本请求, 同通道新请求继续排队; 不标排空
                request.awaitingSys = true
                when (channel) {
                    CHANNEL_TREE -> {
                        treeInFlight = request
                        treeHeld = treeMutex.tryLock()
                    }

                    CHANNEL_DOC -> {
                        docInFlight = request
                        docHeld = docMutex.tryLock()
                    }

                    CHANNEL_IMAGE -> {
                        imageInFlight = request
                        imageHeld = imageMutex.tryLock()
                    }
                }
            } else if (request.pendingResult == null) {
                // 只弹了选择方式弹窗: 重建后继续展示, 用户无需重来;
                // 已有真实回执的请求不再显示, 只等有效入口 bindKey 交付
                HandleFileDialog(activity, request).show()
            }
        }
        // 未被带 key 请求认领的旧通道占用 (原发起方已消亡): 只排空
        if (state.getBoolean(KEY_TREE_HELD) && !treeHeld) {
            drainTree = true
            treeHeld = treeMutex.tryLock()
        }
        if (state.getBoolean(KEY_DOC_HELD) && !docHeld) {
            drainDoc = true
            docHeld = docMutex.tryLock()
        }
        if (state.getBoolean(KEY_IMAGE_HELD) && !imageHeld) {
            drainImage = true
            imageHeld = imageMutex.tryLock()
        }
    }

    /** 保存带 key 的未完成请求与各通道在途占用, 供重建后重绑/排空。 */
    fun saveTo(state: Bundle) {
        val pending = requests.filter { it.key != null && !it.finished }
        state.putInt(KEY_REQ_COUNT, pending.size)
        pending.forEachIndexed { i, request ->
            state.putString(reqKey(KEY_REQ_KEY, i), request.key)
            state.putLong(reqKey(KEY_REQ_ID, i), request.requestId)
            state.putInt(reqKey(KEY_REQ_MODE, i), request.param.mode)
            state.putString(reqKey(KEY_REQ_TITLE, i), request.param.title)
            state.putStringArray(reqKey(KEY_REQ_EXT, i), request.param.allowExtensions)
            state.putInt(reqKey(KEY_REQ_CODE, i), request.param.requestCode)
            state.putString(reqKey(KEY_REQ_VALUE, i), request.param.value)
            state.putString(reqKey(KEY_REQ_BOOK, i), request.bookUrl)
            state.putBoolean(reqKey(KEY_REQ_AWAITING, i), request.awaitingSys)
            state.putString(reqKey(KEY_REQ_CHANNEL, i), request.awaitingChannel)
        }
        state.putBoolean(KEY_TREE_HELD, treeHeld)
        state.putBoolean(KEY_DOC_HELD, docHeld)
        state.putBoolean(KEY_IMAGE_HELD, imageHeld)
    }

    /** 同 key 入口在新宿主注册: 重新绑定回调, 结果交给新入口。 */
    internal fun bindKey(key: String, launcher: HandleFileLauncher) {
        // 同一稳定 key 可对应多个独立未完请求 (每次 launch 一个): 全部重绑, 不能单槽覆盖
        // 快照遍历: 补交 pendingResult → deliverResult → finish 会从本 list 移除,
        // 回调也可能合法再发请求加入本 list —— 边遍历边结构修改不可依赖
        keyedRequests[key]?.toList()?.forEach { request ->
            if (request.finished) return@forEach
            request.launcher = launcher
            request.boundCallback = launcher.callback
            // 有效入口已绑定: 重建期间暂存的真实回执现在交付
            request.awaitingRebind = false
            request.pendingResult?.let { result ->
                request.pendingResult = null
                request.deliverResult(result)
            }
        }
    }

    /** 开始一次请求: 建请求并弹出选择方式弹窗。显示失败则收口本请求并回原异常。 */
    fun begin(
        launcher: HandleFileLauncher,
        param: HandleFileContract.HandleFileParam,
        callback: (HandleFileContract.Result) -> Unit,
    ) {
        val request = Request(launcher, param, callback, launcher.key)
        requests.add(request)
        try {
            HandleFileDialog(activity, request).show()
        } catch (e: Throwable) {
            // 弹窗没立起来: 本请求的资源 (集合登记/可能的部分弹窗) 必须收口,
            // 不把请求留在集合里等一个不会发生的回执
            request.finish()
            throw e
        }
    }

    /** 取消该入口发起的未完成请求: 关弹窗、弃回调; 在途系统调用的通道占用保持到真实回传。 */
    fun cancel(launcher: HandleFileLauncher) {
        requests.filter { it.launcher === launcher }.forEach { it.finish() }
    }

    private fun releaseTree() {
        if (treeHeld) {
            treeHeld = false
            treeMutex.unlock()
        }
    }

    private fun releaseDoc() {
        if (docHeld) {
            docHeld = false
            docMutex.unlock()
        }
    }

    private fun releaseImage() {
        if (imageHeld) {
            imageHeld = false
            imageMutex.unlock()
        }
    }

    internal inner class Request internal constructor(
        internal var launcher: HandleFileLauncher,
        internal val param: HandleFileContract.HandleFileParam,
        private val callback: (HandleFileContract.Result) -> Unit,
        internal val key: String? = null,
        internal val bookUrl: String? = param.book?.bookUrl,
    ) {

        /** 重建后同 key 入口重新绑定的回调 (未绑定时用创建时的回调)。 */
        internal var boundCallback: ((HandleFileContract.Result) -> Unit)? = null

        /** 本请求创建的全部弹窗 (选择方式/应用内选择器/手工输入/建目录), 收口时一并关闭。 */
        internal val dialogs = mutableListOf<Dialog>()

        /** 系统选择器在途 (已实际 launch, 未真实回传)。 */
        internal var awaitingSys = false

        /** 在途系统调用所属通道 (重建时按它把请求接回原通道)。 */
        internal var awaitingChannel: String? = null

        /** 已收口 (交付/取消/宿主销毁), 之后的系统回传按取消处置。 */
        internal var finished = false
        internal val isActive: Boolean get() = !finished

        /** 重建恢复后尚未绑定有效入口 (callback 是占位): 真实回执先到时暂存。 */
        internal var awaitingRebind = false

        /** 暂存的回执 (awaitingRebind 期间到达); bindKey 绑定后立即交付。 */
        internal var pendingResult: HandleFileContract.Result? = null

        /** 本请求唯一 id (跨重建持久化): 回执/暂存按它归属, 不依赖入口 key。 */
        internal var requestId: Long = requestIdSeq.incrementAndGet()

        /** 弹窗归属登记: 收口/宿主销毁时随请求关闭。 */
        internal fun own(dialog: Dialog) {
            dialogs.add(dialog)
        }

        /** 收口: 清状态、关弹窗、解除引用, 之后回调不再发生。 */
        internal fun finish() {
            if (finished) return
            finished = true
            awaitingSys = false
            awaitingChannel = null
            pendingResult = null
            heldResults.remove(requestId)
            dialogs.forEach { it.dismiss() }
            dialogs.clear()
            requests.remove(this)
            if (key != null) {
                keyedRequests[key]?.let { list ->
                    list.remove(this)
                    if (list.isEmpty()) keyedRequests.remove(key)
                }
            }
        }

        /** 收口并把结果交给本请求回调 (回调允许合法发起下一次请求)。 */
        internal fun deliver(uri: Uri?) {
            finish()
            val target = boundCallback ?: callback
            target(HandleFileContract.Result(uri, param.requestCode, param.value, param.book, bookUrl))
        }

        /** 交付已构造好的回执 (重建暂存回执在 bindKey 后走这里)。 */
        internal fun deliverResult(result: HandleFileContract.Result) {
            finish()
            val target = boundCallback ?: callback
            target(result)
        }

        /** 恢复未绑定时真实回执先到: 暂存到 bindKey 交付; 否则按正常交付。 */
        internal fun holdOrDeliver(uri: Uri?) {
            // 真实回执已到手: 系统调用本身终结, 在途标记必须清除
            // (否则再次重建会按"在途"恢复一枚已结束的系统调用并永占通道)
            awaitingSys = false
            awaitingChannel = null
            if (awaitingRebind) {
                val held = HandleFileContract.Result(uri, param.requestCode, param.value, param.book, bookUrl)
                pendingResult = held
                heldResults[this.requestId] = held
            } else {
                deliver(uri)
            }
        }

        /** 同 [holdOrDeliver], 真实失败出口。 */
        internal fun holdOrDeliverError(error: Throwable) {
            awaitingSys = false
            awaitingChannel = null
            if (awaitingRebind) {
                val held = HandleFileContract.Result(
                    null, param.requestCode, param.value, param.book, bookUrl, error,
                )
                pendingResult = held
                heldResults[this.requestId] = held
            } else {
                deliverError(error)
            }
        }

        /** 真实失败 (权限启动/系统授权持久化): 本请求一次终结, 不冒充用户取消。 */
        internal fun deliverError(error: Throwable) {
            finish()
            val target = boundCallback ?: callback
            target(
                HandleFileContract.Result(
                    null, param.requestCode, param.value, param.book, bookUrl, error,
                )
            )
        }

        internal fun launchSysTree(onLaunchFailure: (Throwable) -> Unit) {
            activity.lifecycleScope.launch {
                treeMutex.lock()
                treeHeld = true
                if (!this@Request.isActive) {
                    releaseTree()
                    return@launch
                }
                treeInFlight = this@Request
                awaitingSys = true
                awaitingChannel = CHANNEL_TREE
                try {
                    selectDocTree.launch(null)
                } catch (e: Throwable) {
                    treeInFlight = null
                    awaitingSys = false
                    awaitingChannel = null
                    releaseTree()
                    onLaunchFailure(e)
                }
            }
        }

        internal fun launchSysDoc(types: Array<String>, onLaunchFailure: (Throwable) -> Unit) {
            activity.lifecycleScope.launch {
                docMutex.lock()
                docHeld = true
                if (!this@Request.isActive) {
                    releaseDoc()
                    return@launch
                }
                docInFlight = this@Request
                awaitingSys = true
                awaitingChannel = CHANNEL_DOC
                try {
                    selectDoc.launch(types)
                } catch (e: Throwable) {
                    docInFlight = null
                    awaitingSys = false
                    awaitingChannel = null
                    releaseDoc()
                    onLaunchFailure(e)
                }
            }
        }

        internal fun launchSysImage() {
            activity.lifecycleScope.launch {
                imageMutex.lock()
                imageHeld = true
                if (!this@Request.isActive) {
                    releaseImage()
                    return@launch
                }
                imageInFlight = this@Request
                awaitingSys = true
                awaitingChannel = CHANNEL_IMAGE
                try {
                    selectImage.launch(null)
                } catch (e: Throwable) {
                    imageInFlight = null
                    awaitingSys = false
                    awaitingChannel = null
                    releaseImage()
                    AppLog.put("打开系统图片选择器出错", e, true)
                    deliver(null)
                }
            }
        }
    }

    private companion object {

        /**
         * 暂存回执登记 (key → 回执): 真实回执先于有效入口绑定到达时放入,
         * bindKey 交付/请求收口时移除。同进程跨有效重建保留 —— 原异常对象与
         * "用户取消"终态 (Result(null, null)) 都不失真, 不经 Bundle 降级。
         */
        private val heldResults = LinkedHashMap<Long, HandleFileContract.Result>()

        /** 本会话内请求唯一 id: 同入口 key 的多个独立请求各有自己的回执槽, 互不取用/删除。 */
        private val requestIdSeq = java.util.concurrent.atomic.AtomicLong()

        const val SYS_TREE_KEY = "handleFile.sys.tree"
        const val SYS_DOC_KEY = "handleFile.sys.doc"
        const val SYS_IMAGE_KEY = "handleFile.sys.image"

        const val KEY_TREE_HELD = "handleFile.sys.tree.held"
        const val KEY_DOC_HELD = "handleFile.sys.doc.held"
        const val KEY_IMAGE_HELD = "handleFile.sys.image.held"

        const val KEY_REQ_COUNT = "handleFile.req.count"
        const val KEY_REQ_KEY = "key"
        const val KEY_REQ_MODE = "mode"
        const val KEY_REQ_TITLE = "title"
        const val KEY_REQ_EXT = "ext"
        const val KEY_REQ_CODE = "code"
        const val KEY_REQ_VALUE = "value"
        const val KEY_REQ_BOOK = "book"
        const val KEY_REQ_ID = "handleFile.req.id"

        const val KEY_REQ_AWAITING = "awaiting"
        const val KEY_REQ_CHANNEL = "channel"

        const val CHANNEL_TREE = "tree"
        const val CHANNEL_DOC = "doc"
        const val CHANNEL_IMAGE = "image"

        fun reqKey(suffix: String, index: Int): String = "handleFile.req.$index.$suffix"
    }
}
