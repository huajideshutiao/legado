package io.legado.app.ui.widget.dialog

/**
 * 一次"先关框再跳转"流程的等待框归属句柄。
 *
 * 放 commonMain 供 [io.legado.app.ui.root.PlatformCapabilities] 的 `onWaitDialog`
 * 签名引用 (commonMain 不反向依赖 sharedUiMain); 显示/等待的登记机制在
 * sharedUiMain 的 `WaitDialog` / `awaitWaitDialogDismissed`。
 *
 * 显示方 (`WaitDialog` 组合) 与等待方 (`awaitWaitDialogDismissed`) 各持同一实例:
 * 框实际显示期间按句柄登记, 离开组合即解除 —— 等待只看本框的显示/移除回执,
 * 不用进程内数量、延时推断归属 (自己的框未显示不等, 别窗的框不参与)。
 */
class WaitDialogHandle
