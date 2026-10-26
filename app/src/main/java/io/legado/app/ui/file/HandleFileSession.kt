package io.legado.app.ui.file

import android.app.Dialog
import android.content.Intent
import android.net.Uri
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
 * (Mutex 在宿主主线程上锁定/启动/释放)。不做跨界面重建恢复: 宿主重建即全部作废。
 */
class HandleFileSession(private val activity: AppCompatActivity) {

    /** 所属宿主: 会话随宿主存亡, 构造后不再更换。 */
    val hostActivity: AppCompatActivity get() = activity

    /** 本宿主进行中的文件请求: 每次 launch 一个, 收口即移除。 */
    private val requests = mutableSetOf<Request>()

    // 同通道一次只有一个在途系统调用: inFlight 与 Mutex 成对, 真实回传才释放
    private val treeMutex = Mutex()
    private val docMutex = Mutex()
    private val imageMutex = Mutex()
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
            treeMutex.unlock()
            // 请求已取消/宿主已销毁: 按取消处置, 不落常驻权限、不回调
            if (request != null && request.isActive) {
                if (uri != null && uri.isContentScheme()) {
                    val modeFlags =
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    activity.contentResolver.takePersistableUriPermission(uri, modeFlags)
                }
                request.deliver(uri)
            }
        }

    private val selectDoc: ActivityResultLauncher<Array<String>> =
        activity.activityResultRegistry.register(
            SYS_DOC_KEY, activity, ActivityResultContracts.OpenDocument()
        ) { uri ->
            val request = docInFlight
            docInFlight = null
            docMutex.unlock()
            if (request != null && request.isActive) {
                if (uri != null && uri.isContentScheme()) {
                    val modeFlags =
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    activity.contentResolver.takePersistableUriPermission(uri, modeFlags)
                }
                request.deliver(uri)
            }
        }

    private val selectImage: ActivityResultLauncher<Int?> =
        activity.activityResultRegistry.register(
            SYS_IMAGE_KEY, activity, SelectImageContract()
        ) { result ->
            val request = imageInFlight
            imageInFlight = null
            imageMutex.unlock()
            if (request != null && request.isActive) {
                request.deliver(result.uri)
            }
        }

    init {
        // 宿主销毁: 取消全部等待 (等待协程挂在宿主 lifecycleScope, ON_DESTROY 自行取消),
        // 关闭本宿主创建的全部文件请求弹窗; 正常后台/系统选择器覆盖期间不关
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                requests.toList().forEach { it.finish() }
            }
        })
    }

    /** 开始一次请求: 建请求并弹出选择方式弹窗。 */
    fun begin(
        launcher: HandleFileLauncher,
        param: HandleFileContract.HandleFileParam,
        callback: (HandleFileContract.Result) -> Unit,
    ) {
        val request = Request(launcher, param, callback)
        requests.add(request)
        HandleFileDialog(activity, request).show()
    }

    /** 取消该入口发起的未完成请求: 关弹窗、弃回调; 在途系统调用的通道占用保持到真实回传。 */
    fun cancel(launcher: HandleFileLauncher) {
        requests.filter { it.launcher === launcher }.forEach { it.finish() }
    }

    internal inner class Request internal constructor(
        internal val launcher: HandleFileLauncher,
        internal val param: HandleFileContract.HandleFileParam,
        private val callback: (HandleFileContract.Result) -> Unit,
    ) {

        /** 本请求创建的全部弹窗 (选择方式/应用内选择器/手工输入/建目录), 收口时一并关闭。 */
        internal val dialogs = mutableListOf<Dialog>()

        /** 系统选择器在途 (已实际 launch, 未真实回传)。 */
        internal var awaitingSys = false

        /** 已收口 (交付/取消/宿主销毁), 之后的系统回传按取消处置。 */
        internal var finished = false
        internal val isActive: Boolean get() = !finished

        /** 弹窗归属登记: 收口/宿主销毁时随请求关闭。 */
        internal fun own(dialog: Dialog) {
            dialogs.add(dialog)
        }

        /** 收口: 清状态、关弹窗、解除引用, 之后回调不再发生。 */
        internal fun finish() {
            if (finished) return
            finished = true
            awaitingSys = false
            dialogs.forEach { it.dismiss() }
            dialogs.clear()
            requests.remove(this)
        }

        /** 收口并把结果交给本请求回调 (回调允许合法发起下一次请求)。 */
        internal fun deliver(uri: Uri?) {
            finish()
            callback(HandleFileContract.Result(uri, param.requestCode, param.value))
        }

        internal fun launchSysTree(onLaunchFailure: (Throwable) -> Unit) {
            activity.lifecycleScope.launch {
                treeMutex.lock()
                // 排队期间请求可能已收口: 取消不启动系统调用, 锁让给下一个
                if (!this@Request.isActive) {
                    treeMutex.unlock()
                    return@launch
                }
                treeInFlight = this@Request
                awaitingSys = true
                try {
                    selectDocTree.launch(null)
                } catch (e: Throwable) {
                    treeInFlight = null
                    awaitingSys = false
                    treeMutex.unlock()
                    onLaunchFailure(e)
                }
            }
        }

        internal fun launchSysDoc(types: Array<String>, onLaunchFailure: (Throwable) -> Unit) {
            activity.lifecycleScope.launch {
                docMutex.lock()
                if (!this@Request.isActive) {
                    docMutex.unlock()
                    return@launch
                }
                docInFlight = this@Request
                awaitingSys = true
                try {
                    selectDoc.launch(types)
                } catch (e: Throwable) {
                    docInFlight = null
                    awaitingSys = false
                    docMutex.unlock()
                    onLaunchFailure(e)
                }
            }
        }

        internal fun launchSysImage() {
            activity.lifecycleScope.launch {
                imageMutex.lock()
                if (!this@Request.isActive) {
                    imageMutex.unlock()
                    return@launch
                }
                imageInFlight = this@Request
                awaitingSys = true
                try {
                    selectImage.launch(null)
                } catch (e: Throwable) {
                    imageInFlight = null
                    awaitingSys = false
                    imageMutex.unlock()
                    AppLog.put("打开系统图片选择器出错", e, true)
                    deliver(null)
                }
            }
        }
    }

    private companion object {
        const val SYS_TREE_KEY = "handleFile.sys.tree"
        const val SYS_DOC_KEY = "handleFile.sys.doc"
        const val SYS_IMAGE_KEY = "handleFile.sys.image"
    }
}
