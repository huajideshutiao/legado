package io.legado.app.ui.book.manga.extension

/**
 * 插件文本项活编辑会话 (shared 面): 平台在文本项点击合同里取得, 持打开时定位到的实际配置
 * 对象 —— 绑定/动态校验/提交全程针对同一对象 (对齐 androidx 编辑对话框的同一对象语义,
 * 不经序号/键从重建缓存重取)。
 *
 * 会话对象不持 View、不注册进任何进程级缓存: 编辑控件由平台槽实现创建, 控件绑定面
 * (EditText) 留在平台层 ([MangaPrefTextEditorSession]), 弹窗关闭控件与会话即弃。
 */
interface MangaPrefTextSession {

    /** 实际对象当前文本 (对齐 androidx 库弹窗的 `setText(preference.getText())` 填充)。 */
    fun initialText(): String

    /**
     * 提交: `callChangeListener(text)` 通过才落值持久化, 返回是否落值 (被插件拦截 = false,
     * 与 androidx/Mihon 的确认路径同口径)。落值经桥在后台域执行 (桌面兼容层写盘同步,
     * 不得占用 UI 线程)。
     */
    suspend fun commit(text: String): Boolean
}
