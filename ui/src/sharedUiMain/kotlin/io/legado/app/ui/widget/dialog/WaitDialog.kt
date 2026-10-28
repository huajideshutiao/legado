package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizing
import io.legado.app.ui.compose.component.AppDialogSurface
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.loading
import org.jetbrains.compose.resources.stringResource

/**
 * 加载等待对话框内容 (KMP 共享, app + desktop + iOS 复用)。
 *
 * 对应原版 `io.legado.app.ui.widget.dialog.WaitDialog` 的 UI 部分 (Row +
 * CircularProgressIndicator + Text), 去掉对 Android ComponentDialog / FragmentActivity /
 * FragmentManager 的依赖, 改为纯 @Composable。调用方要么直接用本 Content 内嵌,
 * 要么用带外壳的 [WaitDialog] @Composable (四端唯一实现)。
 *
 * UI 逐项对齐原版 (严禁改变样式):
 * - 容器: Row padding 四边 16dp, 垂直居中, 水平居中
 * - 指示器: CircularProgressIndicator size=30dp, color=accent
 * - 间距: Spacer width=8dp
 * - 文本: message, color=primaryText
 *
 * @param message 等待提示文案 (默认取 i18n key "loading")
 * @param modifier 外部修饰符 (app 端命令式包装不传, 默认 Modifier)
 */
@Composable
fun WaitDialogContent(
    message: String,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier.padding(DesignTokens.spacingLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(30.dp),
            color = colors.accent,
        )
        Spacer(Modifier.width(DesignTokens.spacingDefault))
        Text(message, color = colors.primaryText)
    }
}

/**
 * "本框是否还在界面上"的进程内登记, 供 [awaitWaitDialogDismissed] 判定。
 *
 * 按调用方持有的 [WaitDialogHandle] 逐框登记: 框实际显示期间登记, 离开组合即解除并
 * 放行等待方。等待方因此只等"本框" —— 自己的框未显示过就立即返回, 别窗/别流程的框
 * 不参与 (不能用进程内数量推断归属: 无关框先退会放行, 自己的框未退也不该等它)。
 *
 * 本对象只被 [WaitDialog] 的组合生命周期与 [awaitWaitDialogDismissed] 触碰; 读改写
 * 可能来自不同组合, 故读写同一把锁。
 */
private object WaitDialogVisibility {

    private val lock = SynchronizedObject()
    private val active = HashMap<WaitDialogHandle, CompletableDeferred<Unit>>()

    /** 框实际显示: 登记本框句柄 (同句柄重复显示幂等)。 */
    fun onEnter(handle: WaitDialogHandle) {
        synchronized(lock) { active.getOrPut(handle) { CompletableDeferred() } }
    }

    /** 框离开组合: 解除登记并放行等待本框的协程。 */
    fun onLeave(handle: WaitDialogHandle) {
        val gone = synchronized(lock) { active.remove(handle) } ?: return
        gone.complete(Unit)
    }

    /**
     * 挂起直到 [handle] 对应的框离开组合。
     *
     * 判定与读取在同一次加锁内完成: 若框从未显示 (无登记) 立即返回; 已显示则等它的
     * 解除信号 —— "hide 后才进入组合"的框不会登记 (visible 已为 false), 不会漏也不会多等。
     */
    suspend fun awaitGone(handle: WaitDialogHandle) {
        val gone = synchronized(lock) { active[handle] } ?: return
        gone.await()
    }
}

/**
 * 收起本次流程的等待框 ([hide]), 并等到**本流程那一框**确实已从界面移除之后才返回。
 *
 * 用于"先关框、再跳转"的流程: 调用方把置隐动作交给 [hide], 本函数返回时框已不在画面上, 接着
 * 发起页面跳转即可 —— 转场动画的任何一帧都不会带上等待框 (原版是 "关框 → 打开页面" 的顺序)。
 *
 * # 为什么不能"置隐后直接跳转"
 * 置隐只是把状态改掉, 本框要等之后某次重组才离开组合, 而"离开组合"发生在重组应用阶段。若置隐后
 * 立刻在同一个主线程消息里发起跳转, 这次重组可能根本来不及跑 —— 承载它的页面被转场盖住后其组合
 * 随即停摆, 框会一直挂在界面上, 于是整段转场都带着它。
 *
 * # 等待口径 (按 [handle] 归属, 不看进程内数量)
 * [handle] 是本流程创建并交给显示方的句柄: 自己的框尚未显示过 (无登记) 时立即返回 —— 流程在
 * 组合前/重建空档就完成也不挂; 已显示则只等本框离开组合, 无关页面同时挂着的等待框既不放行也
 * 不被等待。
 *
 * 等待方与写方可能不同线程 (调用方的协程常在 IO 线程, 框的注销在主线程): 判定与注册唤醒
 * 在同一把锁内完成, 变化落在两者之间不会被漏掉。
 *
 * 框离开组合即窗口已被移除, 不必再等帧: [WaitDialog] 的内部对话框与本次登记都在同一次重组应用
 * 的同步块里注销, 平台的 `dismiss()` 同步摘窗口 (Android 走 `removeViewImmediate`); 等待方的恢复
 * 必然晚于这次同步派发走完。
 *
 * [hide] 在调用方线程执行: 置隐涉及平台主线程操作时, 由调用方自己在 [hide] 里切线程。
 */
suspend fun awaitWaitDialogDismissed(
    hide: suspend () -> Unit,
    handle: WaitDialogHandle,
) {
    hide()
    WaitDialogVisibility.awaitGone(handle)
}

/**
 * 加载等待对话框 (KMP 共享, 四端唯一实现)。
 *
 * 用 [AppDialog] 包裹 [WaitDialogContent], 提供声明式 API:
 * - `dismissOnClickOutside = false` 对齐原版 `setCanceledOnTouchOutside(false)`
 * - `dismissOnBackPress = true` 对齐原版默认返回键关闭
 *
 * 四端共用 (原 app 端命令式 WaitDialog 类 + dialogMap 单例已删: 它唯一的调用方
 * AppUpdate.check 已下沉 shared, 等待态由调用方自己的 state 驱动本函数)。
 *
 * 本框在界面上期间会按 [handle] 登记到 [WaitDialogVisibility], 供"先关框再跳转"的调用方
 * 只等本框 (见 [awaitWaitDialogDismissed])。
 *
 * @param visible 是否显示
 * @param message 提示文案 (默认取 i18n key "loading")
 * @param onDismissRequest 用户按返回键触发关闭时回调 (点击外部不触发, 对齐 app 端)
 * @param handle 本框的归属句柄; null = 本框不参与"先关框再跳转"等待 (如页内局部 loading)
 */
@Composable
fun WaitDialog(
    visible: Boolean,
    message: String = stringResource(Res.string.loading),
    onDismissRequest: () -> Unit,
    handle: WaitDialogHandle? = null,
) {
    if (!visible) return
    // 登记声明在对话框之前 (同一次重组应用里两者一同注销): 等待方看到本框解除登记时,
    // 对话框窗口的摘除已在本轮同步派发里完成
    if (handle != null) {
        DisposableEffect(handle) {
            WaitDialogVisibility.onEnter(handle)
            onDispose { WaitDialogVisibility.onLeave(handle) }
        }
    }
    // 原版特例: 转圈小窗不走 AppDialogSizes 的 0.9 屏宽钳制, 保持内容自适应
    AppDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
        sizing = AppDialogSizing.Unconstrained,
    ) {
        val colors = AppTheme.colors
        AppDialogSurface {
            WaitDialogContent(message = message)
        }
    }
}
