package io.legado.app.ui.file

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.legado.app.utils.SelectImageContract
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch

/**
 * 选择方式弹窗 ([HandleFileDialog]) 的跨重建宿主, 对照原版该弹窗为 DialogFragment 时由
 * FragmentManager 自动恢复的语义:
 * - 会话参数 (模式/标题/扩展名/回调 key) 存进本 ViewModel, 随宿主 Activity 的
 *   ViewModelStore 跨界面重建存活, 重建后由任一 [registerHandleFile] 触发 [reattach] 重开弹窗
 * - 三个系统选择器的结果登记用稳定 key 挂在宿主的 activityResultRegistry 上:
 *   ComponentActivity 的注册表把待回传结果存进实例状态, 重建后按 key 重投, 重建前已拉起的
 *   系统选择器照常把结果送回 (重建后须在 STARTED 前用同一 key 重登记, 由各调用方在
 *   Activity 构造期重新 [registerHandleFile] 保证)
 * - 结果按会话记录的 key 找回对应调用方回调, 弹窗随结果关闭
 *
 * 每个宿主 Activity 同一时刻只有一个进行中的选择会话 (弹窗本身模态, 不存在并行)。
 */
class HandleFileSession : ViewModel() {

    /** 当前进行中的会话: 调用方稳定 key + 弹窗参数; null = 无进行中的选择。 */
    private var pending: Pair<String, HandleFileContract.HandleFileParam>? = null

    /** 当前弹窗实例: 随宿主 Activity 存亡, 不跨重建, 重建后由 [reattach] 重建。 */
    private var dialog: HandleFileDialog? = null

    /** 调用方回调表 (稳定 key → 回调), 每次重新注册时刷新。 */
    private val callbacks = mutableMapOf<String, (HandleFileContract.Result) -> Unit>()

    /** 结果登记所在的活动实例; 换新实例 (界面重建) 时按稳定 key 在新注册表重登记。 */
    var hostActivity: AppCompatActivity? = null
        private set

    private var awaitingSysResult = false

    private var selectDocTree: ActivityResultLauncher<Uri?>? = null
    private var selectDoc: ActivityResultLauncher<Array<String>>? = null
    private var selectImage: ActivityResultLauncher<Int?>? = null

    /** 调用方注册: 记录稳定 key 与回调, 并顺带驱动会话重挂 (重建后首个注册即恢复弹窗)。 */
    fun bind(activity: AppCompatActivity, key: String, callback: (HandleFileContract.Result) -> Unit) {
        callbacks[key] = callback
        reattach(activity)
    }

    /** 重挂到 (可能是重建后的) 宿主: 系统选择器登记按稳定 key 落到当前注册表, 有进行中会话则重开弹窗。 */
    fun reattach(activity: AppCompatActivity) {
        if (hostActivity !== activity) {
            hostActivity = activity
            dialog = null
            // 恢复弹窗须等宿主 STARTED: 重建后的首次注册发生在 Activity 构造期,
            // 窗口 token 未就绪, 直接 show 会失败
            activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (pending != null && dialog == null) {
                        showDialog(activity)
                    }
                }
            })
            val registry = activity.activityResultRegistry
            selectDocTree = registry.register(SYS_TREE_KEY, ActivityResultContracts.OpenDocumentTree()) { uri ->
                awaitingSysResult = false
                uri?.let {
                    if (it.isContentScheme()) {
                        val modeFlags =
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        activity.contentResolver.takePersistableUriPermission(it, modeFlags)
                    }
                }
                deliver(uri)
            }
            selectDoc = registry.register(SYS_DOC_KEY, ActivityResultContracts.OpenDocument()) { uri ->
                awaitingSysResult = false
                uri?.let {
                    if (it.isContentScheme()) {
                        val modeFlags =
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        activity.contentResolver.takePersistableUriPermission(it, modeFlags)
                    }
                }
                deliver(uri)
            }
            selectImage = registry.register(SYS_IMAGE_KEY, SelectImageContract()) {
                awaitingSysResult = false
                it.uri?.let { uri -> deliver(uri) } ?: deliver(null)
            }
        }
    }

    /** 开始一次选择: 记录会话并弹出选择方式弹窗。 */
    fun begin(activity: AppCompatActivity, key: String, param: HandleFileContract.HandleFileParam) {
        pending = key to param
        dialog?.dismiss()
        dialog = null
        showDialog(activity)
    }

    private fun showDialog(activity: AppCompatActivity) {
        val entry = pending ?: return
        dialog = HandleFileDialog(activity, this, entry.second).also { it.show() }
    }

    fun launchSysTree() {
        awaitingSysResult = true
        selectDocTree?.launch()
    }

    fun launchSysDoc(types: Array<String>) {
        awaitingSysResult = true
        selectDoc?.launch(types)
    }

    fun launchSysImage() {
        awaitingSysResult = true
        selectImage?.launch()
    }

    fun sysPickerUnavailable() {
        awaitingSysResult = false
    }

    fun isAwaitingSysResult(): Boolean = awaitingSysResult

    /** 会话收口: 关弹窗、清会话, 把结果交给发起会话的调用方回调。 */
    fun deliver(uri: Uri?) {
        val entry = pending ?: return
        pending = null
        dialog?.dismiss()
        dialog = null
        callbacks[entry.first]?.invoke(
            HandleFileContract.Result(uri, entry.second.requestCode, entry.second.value)
        )
    }

    companion object {
        private const val SYS_TREE_KEY = "handleFile.sys.tree"
        private const val SYS_DOC_KEY = "handleFile.sys.doc"
        private const val SYS_IMAGE_KEY = "handleFile.sys.image"

        fun of(activity: AppCompatActivity): HandleFileSession =
            ViewModelProvider(activity)[HandleFileSession::class.java]
    }
}
