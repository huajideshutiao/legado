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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.AppLog
import io.legado.app.utils.SelectImageContract
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 一个宿主 Activity 内的文件选择会话: 承载「选择方式弹窗」([HandleFileDialog]) 与三个系统
 * 选择器通道, 由宿主 Activity 的字段持有, 随宿主存亡。
 *
 * 请求的消费资格/业务终态/真实SDK占用/暂存回执的权威在 [HandleFileRequestHost] (宿主
 * ViewModel 持有的纯状态, 配置重建保留同一实例); 本会话只做 UI 执行 —— 弹窗、入口绑定、
 * 通道占用与交付。每个会话在宿主作用域收集各请求的资格状态, 终结即关闭本会话的 UI,
 * 权威与界面之间只有 StateFlow 一条通道, 权威不持任何界面引用。每次
 * [launch][HandleFileLauncher.launch] 是一个独立请求, 并发请求的结果互不串; 同一系统通道
 * 一次只有一个在途系统调用, 新请求排队等旧调用真实回传 (Mutex 在宿主主线程上锁定/启动/释放)。
 *
 * # 配置重建
 * - 带稳定 [key] 的请求 (发起方在重建后仍是同一个宿主) 凭 ViewModel 记录在重建后的新会话
 *   **重新绑定**: 未启动的弹窗重新展示, 已启动的系统结果交给同 key 的新回调;
 * - 无 key 的请求发起方已随页面/协程消亡, 重建后只作废: 系统旧结果只排空, 不交给新请求;
 * - 本宿主销毁只解绑本会话的 UI (关弹窗/弃回调绑定/撤权限消费), 业务记录与真实SDK占用
 *   不经此改动, 新宿主凭记录接回;
 * - 进程重建后 ViewModel 记录不存在, 旧 Bundle 只剩各通道占用标志: 在途系统回执一律排空,
 *   不凭保存状态生造消费资格。
 * - 不会释放一个当前调用未持有的锁。
 */
class HandleFileSession(private val activity: AppCompatActivity) {

    /** 请求纯状态的宿主权威: 与宿主同 ViewModelStore, 随宿主存亡。 */
    private val requestHost: HandleFileRequestHost =
        ViewModelProvider(activity)[HandleFileRequestHost::class.java]

    /**
     * 本宿主进行中的文件请求: 每次 launch 一个, 关闭即移除。
     *
     * 用并发快照集合: 撤销 (终结消费资格) 可能来自任意线程 (Job.invokeOnCompletion 的回调
     * 线程), 必须能在不持主线程的情况下安全遍历; 登记/关闭仍只在主线程发生。
     */
    private val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()

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
            // 真实SDK回执先终结真实在途事实, 再判交付/排空
            request?.let { it.record.receiptSettled() }
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
            } else {
                // 已撤销/已解绑: 真实回执只排空, 不取持久授权、不交付
                request?.finish()
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
            // 真实SDK回执先终结真实在途事实, 再判交付/排空
            request?.let { it.record.receiptSettled() }
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
            } else {
                // 已撤销/已解绑: 真实回执只排空, 不取持久授权、不交付
                request?.finish()
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
            // 真实SDK回执先终结真实在途事实, 再判交付/排空
            request?.let { it.record.receiptSettled() }
            if (request != null && request.isActive) {
                request.holdOrDeliver(result.uri)
            } else {
                // 已撤销/已解绑: 真实回执只排空, 不交付
                request?.finish()
            }
        }

    init {
        // 宿主销毁: 本会话全部 UI 执行体解绑关闭 (旧宿主不得再起新权限/子窗、不得再接回执);
        // 业务记录与真实SDK占用随 ViewModel 权威存续, 配置重建后由新宿主凭记录接回
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                requests.toList().forEach { it.finish() }
            }
        })
    }

    /**
     * 恢复重建前的在途文件请求: 带 key 的请求凭宿主 ViewModel 记录接回 —— 未启动的重新展示
     * 弹窗, 已启动的系统结果交给同 key 回调; 无 key 的记录 (发起方已随旧宿主消亡) 只作废。
     *
     * 撤销资格以记录为准: 已终结且非在途的记录不会出现在这里 (终结时已即时回收);
     * 已终结但SDK仍在途的记录同样接回, 真实回执到达时终结在途事实并回收。
     * 各通道的进程死亡占用由 Bundle 占用标志排空。
     *
     * 由宿主在 onCreate 内、系统结果补投 (ON_START) 之前调用。
     */
    fun restoreFrom(state: Bundle?) {
        if (state == null) return
        requestHost.requestRecords().forEach { record ->
            if (record.key == null) {
                // 无 key 记录的发起方已随旧宿主消亡: 只作废; 其通道占用由 Bundle 占用标志排空
                requestHost.removeRequest(record.requestId)
                return@forEach
            }
            // 占位 callback 是空的: 真实回执 (ON_START 补投早于组合) 先到必须暂存,
            // 留到同 key 有效入口 bindKey 时交付, 否则原等待协程永挂
            val request = attach(record, HandleFileLauncher(this, {}, record.key), {})
            request.awaitingRebind = true
            if (record.awaitingSys) {
                // 系统调用仍在途: 结果交给本请求, 同通道新请求继续排队; 不标排空
                when (record.awaitingChannel) {
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
            } else if (record.pendingResult == null && request.isActive) {
                // 只弹了选择方式弹窗: 重建后继续展示, 用户无需重来;
                // 已有真实回执的请求不再显示, 只等有效入口 bindKey 交付;
                // 呈现是消费点: 撤销落在登记与呈现之间时不再弹选择方式弹窗
                HandleFileDialog(activity, request).show()
            }
        }
        // 未被带 key 请求认领的通道占用 (进程重建后原发起方已消亡): 只排空
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

    /** 保存各通道在途占用, 供进程重建后的宿主排空旧结果 (请求本身凭 ViewModel 记录接回)。 */
    fun saveTo(state: Bundle) {
        state.putBoolean(KEY_TREE_HELD, treeHeld)
        state.putBoolean(KEY_DOC_HELD, docHeld)
        state.putBoolean(KEY_IMAGE_HELD, imageHeld)
    }

    /** 同 key 入口在新宿主注册: 重新绑定回调, 结果交给新入口。 */
    internal fun bindKey(key: String, launcher: HandleFileLauncher) {
        // 同一稳定 key 可对应多个独立未完请求 (每次 launch 一个): 全部重绑, 不能单槽覆盖
        // 快照遍历: 补交 pendingResult → deliverResult → 关闭会从本 list 移除,
        // 回调也可能合法再发请求加入本 list —— 边遍历边结构修改不可依赖
        keyedRequests[key]?.toList()?.forEach { request ->
            if (request.closed) return@forEach
            // 资格在记录上实时生效: 已终结时绑定后走原路径, 暂存回执按 isActive 只排空
            request.launcher = launcher
            request.boundCallback = launcher.callback
            // 有效入口已绑定: 重建期间暂存的真实回执现在交付
            request.awaitingRebind = false
            request.record.pendingResult?.let { result ->
                request.record.pendingResult = null
                request.deliverResult(result)
            }
        }
    }

    /** 开始一次请求: 建请求并弹出选择方式弹窗。显示失败则关闭本请求并回原异常。 */
    fun begin(
        launcher: HandleFileLauncher,
        param: HandleFileContract.HandleFileParam,
        callback: (HandleFileContract.Result) -> Unit,
    ) {
        // 入口预绑定的业务记录 ("书籍保存位置"发起时已建立): launch 只认领这份资格,
        // 不再生造新资格; 未预绑定的入口在此建立自己的记录
        val record = launcher.attachedRecord ?: requestHost.RequestRecord(
            requestId = requestHost.nextRequestId(),
            key = launcher.key,
            param = param,
            bookUrl = param.book?.bookUrl,
        ).also { requestHost.addRequest(it) }
        if (!record.isConsumable) return
        val request = attach(record, launcher, callback)
        try {
            // 呈现是消费点: 撤销落在登记与呈现之间时不再弹选择方式弹窗
            if (request.isActive) HandleFileDialog(activity, request).show()
        } catch (e: Throwable) {
            // 弹窗没立起来: 本请求的资源 (集合登记/可能的部分弹窗) 必须关闭,
            // 不把请求留在集合里等一个不会发生的回执
            request.finish()
            throw e
        }
    }

    /**
     * 同步终结该入口名下请求的消费资格 (任意线程可调, 幂等)。
     *
     * 资格终结写在宿主 ViewModel 的请求记录上: 之后系统回传不再取持久授权、不再交付,
     * 持有该请求的每个会话 (含重建后的新宿主) 经资格收集自行关闭UI。已起跑的系统调用
     * 占用保持到真实回传。
     */
    fun revokeConsumption(launcher: HandleFileLauncher) {
        requests.forEach { request ->
            if (request.launcher === launcher) request.record.terminate()
        }
    }

    /** 取消该入口发起的未完成请求: 终结资格 + 关弹窗弃回调; 在途系统调用的通道占用保持到真实回传。 */
    fun cancel(launcher: HandleFileLauncher) {
        revokeConsumption(launcher)
        requests.forEach { request ->
            if (request.launcher === launcher) request.finish()
        }
    }

    /** 建会话请求并挂到记录上: 本会话在宿主作用域收集资格状态, 终结即关闭自己的UI。 */
    private fun attach(
        record: HandleFileRequestHost.RequestRecord,
        launcher: HandleFileLauncher,
        callback: (HandleFileContract.Result) -> Unit,
    ): Request {
        val request = Request(launcher, record, callback)
        requests.add(request)
        record.key?.let { key ->
            keyedRequests.getOrPut(key) { mutableListOf() }.add(request)
        }
        // 收集器活在宿主作用域: 权威不持任何界面引用, 宿主销毁随作用域取消
        request.watchJob = activity.lifecycleScope.launch {
            record.consumable.collect { consumable -> if (!consumable) request.finish() }
        }
        return request
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
        internal val record: HandleFileRequestHost.RequestRecord,
        private val callback: (HandleFileContract.Result) -> Unit,
    ) {

        /** 重建后同 key 入口重新绑定的回调 (未绑定时用创建时的回调); 本UI解绑后弃用。 */
        internal var boundCallback: ((HandleFileContract.Result) -> Unit)? = null

        /** 本请求创建的全部弹窗 (选择方式/应用内选择器/手工输入/建目录), 关闭时一并关闭。 */
        internal val dialogs = mutableListOf<Dialog>()

        /** 本会话对记录资格状态的收集 (宿主作用域), 关闭时取消。 */
        internal var watchJob: Job? = null

        internal val key: String? get() = record.key
        internal val param: HandleFileContract.HandleFileParam get() = record.param

        /** 系统选择器在途 (已实际 launch, 未真实回传): 真实事实在记录上。 */
        internal val awaitingSys: Boolean get() = record.awaitingSys

        /** 本 UI 执行体已关闭 (解绑回调/关弹窗), 之后不再呈现、不再交付。主线程收口。 */
        internal var closed = false

        /** 可交付/可消费: 本 UI 未解绑, 且记录资格未终结 (实时读权威记录)。 */
        internal val isActive: Boolean get() = !closed && record.isConsumable

        /** 重建恢复后尚未绑定有效入口 (callback 是占位): 真实回执先到时暂存。 */
        internal var awaitingRebind = false

        /** 弹窗归属登记: 关闭/宿主销毁时随请求关闭。 */
        internal fun own(dialog: Dialog) {
            dialogs.add(dialog)
        }

        /** 关闭本 UI 执行体: 解绑回调、关弹窗; 业务记录与真实SDK占用不经此改动。 */
        internal fun finish() {
            if (closed) return
            closed = true
            watchJob?.cancel()
            watchJob = null
            boundCallback = null
            dialogs.forEach { it.dismiss() }
            dialogs.clear()
            requests.remove(this)
            key?.let { k ->
                keyedRequests[k]?.let { list ->
                    list.remove(this)
                    if (list.isEmpty()) keyedRequests.remove(k)
                }
            }
        }

        /** 收口并把结果交给本请求回调 (回调允许合法发起下一次请求); 业务记录随交付终结。 */
        internal fun deliver(uri: Uri?) {
            if (!isActive) {
                // 资格已终结/本UI已解绑: 结果只排空, 不交付
                finish()
                return
            }
            val target = boundCallback ?: callback
            finish()
            record.terminate()
            target(HandleFileContract.Result(uri, param.requestCode, param.value, param.book, record.bookUrl))
        }

        /** 交付已构造好的回执 (重建暂存回执在 bindKey 后走这里); 业务记录随交付终结。 */
        internal fun deliverResult(result: HandleFileContract.Result) {
            if (!isActive) {
                finish()
                return
            }
            val target = boundCallback ?: callback
            finish()
            record.terminate()
            target(result)
        }

        /** 恢复未绑定时真实回执先到: 暂存到 bindKey 交付; 否则按正常交付。 */
        internal fun holdOrDeliver(uri: Uri?) {
            if (awaitingRebind) {
                record.pendingResult = HandleFileContract.Result(
                    uri, param.requestCode, param.value, param.book, record.bookUrl,
                )
            } else {
                deliver(uri)
            }
        }

        /** 同 [holdOrDeliver], 真实失败出口。 */
        internal fun holdOrDeliverError(error: Throwable) {
            if (awaitingRebind) {
                record.pendingResult = HandleFileContract.Result(
                    null, param.requestCode, param.value, param.book, record.bookUrl, error,
                )
            } else {
                deliverError(error)
            }
        }

        /** 真实失败 (权限启动/系统授权持久化): 本请求一次终结, 不冒充用户取消。 */
        internal fun deliverError(error: Throwable) {
            if (!isActive) {
                finish()
                return
            }
            val target = boundCallback ?: callback
            finish()
            record.terminate()
            target(
                HandleFileContract.Result(
                    null, param.requestCode, param.value, param.book, record.bookUrl, error,
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
                record.awaitingSys = true
                record.awaitingChannel = CHANNEL_TREE
                try {
                    selectDocTree.launch(null)
                } catch (e: Throwable) {
                    treeInFlight = null
                    record.awaitingSys = false
                    record.awaitingChannel = null
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
                record.awaitingSys = true
                record.awaitingChannel = CHANNEL_DOC
                try {
                    selectDoc.launch(types)
                } catch (e: Throwable) {
                    docInFlight = null
                    record.awaitingSys = false
                    record.awaitingChannel = null
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
                record.awaitingSys = true
                record.awaitingChannel = CHANNEL_IMAGE
                try {
                    selectImage.launch(null)
                } catch (e: Throwable) {
                    imageInFlight = null
                    record.awaitingSys = false
                    record.awaitingChannel = null
                    releaseImage()
                    AppLog.put("打开系统图片选择器出错", e, true)
                    deliverError(e)
                }
            }
        }
    }

    private companion object {

        const val SYS_TREE_KEY = "handleFile.sys.tree"
        const val SYS_DOC_KEY = "handleFile.sys.doc"
        const val SYS_IMAGE_KEY = "handleFile.sys.image"

        const val KEY_TREE_HELD = "handleFile.sys.tree.held"
        const val KEY_DOC_HELD = "handleFile.sys.doc.held"
        const val KEY_IMAGE_HELD = "handleFile.sys.image.held"

        const val CHANNEL_TREE = "tree"
        const val CHANNEL_DOC = "doc"
        const val CHANNEL_IMAGE = "image"
    }
}
