package io.legado.app.ui.book.changecover

import io.legado.app.ui.root.PlatformServiceProviders
import kotlin.concurrent.Volatile

/**
 * [CoverStorageService] 注入容器 (shared commonMain), 模式同 [ChangeCoverPlatformProviders]。
 *
 * 默认实现即四端统一链路 ([CoverPersistenceShared]): 选中图复制进图集
 * `customImg/covers/<字节数>.<ext>` 并按封面比例烘焙产物, 返回图集内部相对引用;
 * 复制成功后清掉平台物化的临时副本 (各端只删自己物化的副本, 见
 * [io.legado.app.ui.root.FilePickerService.discardPickedFile])。
 * 宿主可注册实现覆盖落点; 未注册不会退化成"把选图临时副本当封面存库"。
 */
object CoverStorageServiceProviders {

    @Volatile
    private var impl: CoverStorageService? = null

    /** 宿主启动早期可注册实现覆盖默认落点。 */
    fun register(impl: CoverStorageService) {
        this.impl = impl
    }

    /** 获取实现, 未注册返回四端统一默认实现。 */
    fun get(): CoverStorageService = impl ?: SharedCoverStorageService

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}

/** 默认实现: 图集复制 + 平台物化副本清理 (四端同一份)。 */
private object SharedCoverStorageService : CoverStorageService {
    override fun persistCover(srcPath: String, displayName: String): String? {
        val ref = CoverPersistenceShared.persistCover(srcPath, displayName) ?: return null
        // 选中件已复制进图集, 平台物化的临时副本不留在缓存里
        PlatformServiceProviders.getOrNull()?.files?.discardPickedFile(srcPath)
        return ref
    }
}
