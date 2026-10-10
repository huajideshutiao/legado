package io.legado.app.help.book

import io.legado.app.constant.DirUsage
import io.legado.app.utils.IosSecurityScopedStorage

/**
 * iOS [LocalBookLocator]: 路径解析与按名找回复用 nativeMain [NativeLocalBookLocator] (与鸿蒙同一套规则),
 * 触及文件的只读动作 (能否打开) 走 [IosSecurityScopedStorage] 的 security-scoped 授权。
 *
 * # 为什么必须覆写文件动作
 * 用户经"文件"面板授权的目录在应用容器之外, 直接按 POSIX 路径 stat 会因缺少 security scope
 * 失败 (或被系统拒绝): 授权只在 [IosSecurityScopedStorage.acquireLease] 期间有效。
 *
 * 授权之外还要的是"文件能不能打开", 故直接持租约取值, 不走 [IosSecurityScopedStorage.withAccess]
 * 的读协调: 读协调对不存在的目标报错, 拿不到一个干净的"打不开"结论; 授权失效同样只算"不成立"
 * (由阅读层按失败处理), 不在定位阶段报授权异常。
 *
 * 删本地书文件不在这里: 删文件只有统一入口
 * [io.legado.app.model.fileBook.FileBookAccessor] 一个, iOS 的授权由
 * [io.legado.app.model.fileBook.IosAuthorizedFileBookAccessor] 包裹 (含整段删原文件的租约)。
 *
 * 导入进 `{filesDir}/books` 的沙盒副本不需要授权, 授权层对沙盒路径自动放行。
 */
class IosLocalBookLocator : NativeLocalBookLocator() {

    /** 引用能否打开: 沙盒路径直通, 容器外路径先取租约再探测。 */
    override fun isReadableFile(path: String): Boolean {
        val lease = runCatching {
            IosSecurityScopedStorage.acquireLease(path, DirUsage.BookTree)
        }.getOrNull() ?: return false
        return try {
            canOpenFile(path)
        } finally {
            lease.close()
        }
    }

    /**
     * 容器外的目录要持租约才能列举: 租约持的是授权根, 根生效期间其内容递归可访问, 故整段查找
     * 共用一份租约 (内部探测用 [canOpenFile] 即可)。无授权目录列举不出内容, 按没找到处理。
     */
    override fun searchInDir(dir: String, originName: String): String? = runCatching {
        IosSecurityScopedStorage.withAccess(dir, DirUsage.BookTree) { scopedDir ->
            findReadableLocalBookFileInDir(scopedDir, originName, ::canOpenFile)
        }
    }.getOrNull()
}

/** 注册 iOS 本地书定位实现 (在 IosProviderRegistry 的启动注册处调用)。 */
fun registerIosLocalBookLocator() {
    LocalBookLocators.register(IosLocalBookLocator())
}
