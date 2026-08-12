package io.legado.app.ui.book.changecover

import kotlin.concurrent.Volatile

/**
 * [CoverStorageService] 注入容器 (shared commonMain), 模式同 [ChangeCoverPlatformProviders]。
 *
 * 与 ChangeCoverPlatform 不同, CoverStorageService 无 commonMain 默认实现可兜底
 * (持久目录是平台专属概念: Android externalFilesDir / 桌面应用数据根目录),
 * 故默认实现直接返回 null, 宿主未注册时调用方回退使用 pickFile 原路径
 * (iOS/ohos 等未注册平台保持引入本桥前的行为)。
 */
object CoverStorageServiceProviders {

    @Volatile
    private var impl: CoverStorageService? = null

    /** 宿主启动早期注册 (app 端 MainActivity / 桌面端 Main.kt)。 */
    fun register(impl: CoverStorageService) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册返回 [DefaultCoverStorageService] (返回 null, 不持久化)。 */
    fun get(): CoverStorageService = impl ?: DefaultCoverStorageService

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}

/** 默认实现: 不持久化, 返回 null (iOS/ohos 等未注册平台由调用方回退原路径)。 */
private object DefaultCoverStorageService : CoverStorageService {
    override fun persistCover(srcPath: String, displayName: String): String? = null
}
