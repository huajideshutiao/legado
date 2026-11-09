package io.legado.app.ui.book.manga.extension

import androidx.compose.runtime.Composable

/**
 * 插件文本项编辑弹窗的平台槽: Android 注入真实控件承载 (Mihon 同款), 桌面注入兼容层活适配
 * 承载; 槽随桥在同端装配序列注册, 未装配端 (iOS/鸿蒙, 「设置」入口本就隐藏) 不会产生会话。
 *
 * request 只带活会话与回调 (会话已持实际配置对象身份); 平台控件、插件绑定监听与提交细节
 * 全部留在槽实现内, 跨端 UI 不接触平台 View/控件类型。
 */
object MangaPrefTextEditorSlot {

    @Volatile
    var editor: (@Composable (MangaPrefTextEditorRequest) -> Unit)? = null
}

/**
 * 编辑弹窗请求 (shared 数据面)。
 *
 * @param session 点击合同取得的活编辑会话 (持本次实际配置对象)
 * @param onSettled 确认提交完成回调 (committed = 会话落值成功; 被插件拦截为 false;
 *              提交异常不回调本方法, 弹窗保持打开, 入口负责失败反馈), 入口据此重读刷新
 * @param onDismiss 关闭回调 (取消按钮/点外), 会话与控件随之弃置
 */
class MangaPrefTextEditorRequest(
    val session: MangaPrefTextSession,
    val onSettled: (committed: Boolean) -> Unit,
    val onDismiss: () -> Unit,
)
