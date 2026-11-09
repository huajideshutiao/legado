package io.legado.app.ui.bookshelf

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.resolveImagePath
import io.legado.app.model.BookCoverShared.CoverRatio

/**
 * 封面资源身份: 展示封面地址 + 书源来源 + 位图分区。
 * 同一封面地址在不同书源下是不同资源, 同一地址落书架持久区还是临时区也是不同资源,
 * 三元组不全等即不合并。
 */
@Immutable
internal data class CoverResourceKey(
    val coverUrl: String?,
    val origin: String?,
    val persistentCover: Boolean,
)

/** 条目 → 资源身份, 与封面渲染收到的字段同口径 ([SharedBookCover] / [SharedGroupCover]) */
internal fun Book.coverResourceKey(): CoverResourceKey =
    CoverResourceKey(getDisplayCover(), origin, !isNotShelf)

internal fun BookGroup.coverResourceKey(): CoverResourceKey =
    CoverResourceKey(resolveImagePath(cover), null, true)

/** 搜索条目经真实转换取身份 (与 toCoverBook → [SharedBookCover] 渲染逐字段等值) */
internal fun SearchBook.coverResourceKey(inBookshelf: Boolean): CoverResourceKey =
    toCoverBook(inBookshelf).coverResourceKey()

/**
 * AUTO 档已实际决议的布局比例 (16:9/3:4 高度档位), 不是原始图片尺寸元数据。
 *
 * 持有者 = 仍保留当前结果集的真实 UI 所有者, 比例状态与仍保留的当前结果集同寿命:
 * 页面结束/出栈、结果集重置或资源移除即随之清理; 组合重建经同一下发点取回同一实例,
 * 比例不随组合丢失。只存已决议比例枚举, 不持有位图, 无独立淘汰上限 (容量受活跃集合约束)。
 *
 * 写入与清理同以 [updateActiveResources] 提交的当前结果集身份为校验基准: 持有者层在
 * 每次成功组合提交时先更新活跃集合, 条目级补种与真图成功登记只对仍在结果集中的资源
 * 生效, 已移除资源的迟到结果被拒绝。
 */
internal class CoverRatioLayoutState {

    private val resolvedRatioByResource = mutableStateMapOf<CoverResourceKey, CoverRatio>()

    /** 当前结果集内的资源身份 (写入与清理的同一校验基准) */
    private var activeResources: Set<CoverResourceKey> = emptySet()

    /** 结果集变化时由持有者层提交: 同步活跃集合并清理已移除资源的比例 */
    fun updateActiveResources(resources: Set<CoverResourceKey>) {
        activeResources = resources
        resolvedRatioByResource.keys.retainAll(resources)
    }

    /** 已确定的 AUTO 布局比例 (NOVEL=3:4 / VIDEO=16:9); 尚未确定返回 null */
    fun resolvedRatio(resource: CoverResourceKey): CoverRatio? =
        resolvedRatioByResource[resource]

    /** AUTO 档真封面成功后登记其已决议值; 已移出结果集的迟到结果被拒绝 */
    fun recordResolvedRatio(resource: CoverResourceKey, ratio: CoverRatio) {
        if (resource in activeResources) {
            resolvedRatioByResource[resource] = ratio
        }
    }

    /** AUTO 档已决议值补种空记录; 已有记录不覆盖 (新真图结果按 [recordResolvedRatio] 更新) */
    fun seedResolvedRatioIfAbsent(resource: CoverResourceKey, ratio: CoverRatio) {
        if (resource in activeResources && resource !in resolvedRatioByResource) {
            resolvedRatioByResource[resource] = ratio
        }
    }
}

/**
 * 封面比例布局状态的页面级下发: 由与结果集同寿命的页面组合在条目内容处 provide,
 * 封面渲染链 ([SharedCoverContent]) 读取。无持有者的使用处 (如详情页) 取 null,
 * 按各自现有语义起步与决议。
 */
internal val LocalCoverRatioLayout =
    staticCompositionLocalOf<CoverRatioLayoutState?> { null }
