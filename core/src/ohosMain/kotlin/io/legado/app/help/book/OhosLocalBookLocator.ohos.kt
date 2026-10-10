package io.legado.app.help.book

import io.legado.app.constant.DirUsage
import io.legado.app.help.file.OhosDirAuthorizations

/**
 * 鸿蒙 [LocalBookLocator]: 路径解析与按名找回复用 nativeMain [NativeLocalBookLocator] (与 iOS 同一套规则),
 * 触及文件的只读动作先激活目录授权 (持久授权重启后须按需激活, 见 [OhosDirAuthorizations])。
 *
 * # 为什么必须覆写文件动作
 * 私有目录之外的路径未激活对应授权时, 文件调用会被系统拒绝 —— 直接探测会把"没有授权"当成
 * "文件打不开", 按名找回也就找不到本可用的书。
 *
 * 与 iOS 同名类的区别: 鸿蒙用的是持久授权 + 每次访问前激活, 不持租约。
 *
 * 删本地书文件不在这里: 删文件只有统一入口
 * [io.legado.app.model.fileBook.FileBookAccessor] 一个, 鸿蒙的授权由
 * [io.legado.app.model.fileBook.OhosAuthorizedFileBookAccessor] 包裹。
 */
class OhosLocalBookLocator : NativeLocalBookLocator() {

    /** 引用能否打开: 先激活路径所属目录的授权, 未登记或激活失败一律算"不成立" (由阅读层按失败处理)。 */
    override fun isReadableFile(path: String): Boolean = runCatching {
        OhosDirAuthorizations.ensureActivatedFor(path, DirUsage.BookTree)
        canOpenFile(path)
    }.getOrDefault(false)

    /**
     * 私有目录之外的目录要激活所属目录的授权才能列举: 激活的是授权目录, 其内容随之可访问,
     * 故整段查找前激活一次 (内部探测用 [canOpenFile] 即可)。未登记或激活失败按没找到处理。
     */
    override fun searchInDir(dir: String, originName: String): String? = runCatching {
        val activatedDir = OhosDirAuthorizations.ensureActivatedFor(dir, DirUsage.BookTree)
        findReadableLocalBookFileInDir(activatedDir, originName, ::canOpenFile)
    }.getOrNull()
}

/** 注册鸿蒙本地书定位实现 (在 OhosProviderRegistry 的启动注册处调用)。 */
fun registerOhosLocalBookLocator() {
    LocalBookLocators.register(OhosLocalBookLocator())
}
