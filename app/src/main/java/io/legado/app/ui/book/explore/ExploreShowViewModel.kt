package io.legado.app.ui.book.explore

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.ui.explore.ExploreShowViewModelShared
import kotlinx.coroutines.launch

/**
 * 发现页展示 VM (Android 端, 组合委托)。
 *
 * # KMP 化重构说明
 *
 * 核心业务编排 (initData / 收藏 / 分页加载 / 书架 key 维护 / 列数与样式切换) 已下沉到
 * shared commonMain [ExploreShowViewModelShared], 用 7 个 `MutableStateFlow` 替代
 * `MutableLiveData` (LiveData 不可 KMP)。
 *
 * 本类采用**组合委托**模式持有 [shared] 实例, 不通过继承 [ExploreShowViewModelShared]:
 * - 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 *   `viewModelScope`), Kotlin 单继承无法同时继承 [ExploreShowViewModelShared];
 * - 仅注入 `scope = viewModelScope` 一个参数;
 * - 7 个 LiveData 字段订阅 [shared] 对应的 StateFlow 转发, 调用方 `observe` 用法不变;
 * - `initData(intent: Intent)` 解析 Intent 后转发到 [shared.initData] (name, url, sourceUrl);
 * - 其余方法 (`isFavorite` / `toggleFavorite` / `explore` / `isInBookShelf` /
 *   `switchLayout` / `setColumnCount`) 直接转发到 [shared]。
 *
 * # 状态桥接
 *
 * 7 个 LiveData 字段内部用 [viewModelScope] 协程订阅 [shared] 对应的 StateFlow, 转发到 MutableLiveData:
 * - StateFlow 是 hot flow, collect 时立即收到当前值, 但 shared 初始值为 null (可空字段),
 *   桥接时过滤 null 避免初始假触发;
 * - `postValue` 异步切到主线程, 与原 LiveData.postValue 行为一致;
 * - 不用 `androidx.lifecycle.asLiveData()` 扩展: 项目未显式引入 lifecycle-livedata-ktx,
 *   用 viewModelScope.launch + collect 自己桥接确保编译通过。
 *
 * # 调用方兼容
 *
 * [ExploreShowActivity] 调用方式保持不变:
 * - `viewModel.booksData.observe(...)` / `viewModel.errorLiveData.observe(...)` 等 7 个 LiveData;
 * - `viewModel.initData(intent)` (Intent 解析在本类做, 转发到 [shared.initData]);
 * - `viewModel.bookSource` / `viewModel.exploreStyle` / `viewModel.exploreName` /
 *   `viewModel.exploreOptions` / `viewModel.page` / `viewModel.hasNextPage` (getter 转发);
 * - `viewModel.toggleFavorite()` / `viewModel.isFavorite()` / `viewModel.isInBookShelf(book)` /
 *   `viewModel.explore(resetPage)` / `viewModel.switchLayout()` / `viewModel.setColumnCount(cols)`
 *   (方法转发)。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExploreShowViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope] 供 shared 内部协程使用。
     *
     * - shared 承担状态托管 (StateFlow) + 全部业务方法;
     * - 本类仅作 LiveData 桥接 + Intent 解析。
     */
    private val shared: ExploreShowViewModelShared = ExploreShowViewModelShared(
        scope = viewModelScope,
    )

    // region 7 个 LiveData 桥接 (订阅 shared 的 StateFlow, 转发到 MutableLiveData)

    /** 书架变化信号 (订阅 [shared.upAdapterFlow], 对照原 `upAdapterLiveData: MutableLiveData<String>`)。 */
    val upAdapterLiveData = MutableLiveData<String>()

    /** 当前发现结果列表 (订阅 [shared.booksFlow], 对照原 `booksData: MutableLiveData<List<SearchBook>>`)。 */
    val booksData = MutableLiveData<List<SearchBook>>()

    /** 加载错误信息 (订阅 [shared.errorFlow], 对照原 `errorLiveData: MutableLiveData<String>`)。 */
    val errorLiveData = MutableLiveData<String>()

    /** 书源就绪信号 (订阅 [shared.sourceReadyFlow], 对照原 `sourceReadyLiveData: MutableLiveData<Unit>`)。 */
    val sourceReadyLiveData = MutableLiveData<Unit>()

    /** 参数 chip 就绪信号 (订阅 [shared.optionsReadyFlow], 对照原 `optionsReadyLiveData: MutableLiveData<Unit>`)。 */
    val optionsReadyLiveData = MutableLiveData<Unit>()

    /** 收藏状态变化信号 (订阅 [shared.upStarFlow], 对照原 `upStarLiveData: MutableLiveData<Boolean>`)。 */
    val upStarLiveData = MutableLiveData<Boolean>()
    // endregion

    init {
        // 订阅 shared 的 7 个 StateFlow, 把变化推到对应 LiveData
        // 一次性订阅, viewModelScope cancel 时自动结束
        viewModelScope.launch {
            shared.upAdapterFlow.collect { value ->
                value?.let { upAdapterLiveData.postValue(it) }
            }
        }
        viewModelScope.launch {
            shared.booksFlow.collect { value ->
                // booksFlow 初始 null (与原 LiveData 默认 null 一致), 过滤 null 避免初始假触发
                value?.let { booksData.postValue(it) }
            }
        }
        viewModelScope.launch {
            shared.errorFlow.collect { value ->
                value?.let { errorLiveData.postValue(it) }
            }
        }
        viewModelScope.launch {
            shared.sourceReadyFlow.collect { value ->
                value?.let { sourceReadyLiveData.postValue(it) }
            }
        }
        viewModelScope.launch {
            shared.optionsReadyFlow.collect { value ->
                value?.let { optionsReadyLiveData.postValue(it) }
            }
        }
        viewModelScope.launch {
            shared.upStarFlow.collect { value ->
                value?.let { upStarLiveData.postValue(it) }
            }
        }
    }

    // region 字段 getter 转发 (供 Activity 直接读)

    /** 当前发现目标书源, 转发到 [shared.bookSource]。 */
    val bookSource: BookSource? get() = shared.bookSource

    /** 当前发现样式, 转发到 [shared.exploreStyle]。 */
    val exploreStyle: Int get() = shared.exploreStyle

    /** 发现分类名, 转发到 [shared.exploreName]。 */
    val exploreName: String? get() = shared.exploreName

    /** 参数 chip 列表, 转发到 [shared.exploreOptions]。 */
    val exploreOptions: MutableList<ExploreOption> get() = shared.exploreOptions

    /** 当前分页页码, 转发到 [shared.page]。 */
    val page: Int get() = shared.page

    /** 是否还有下一页, 转发到 [shared.hasNextPage]。 */
    val hasNextPage: Boolean get() = shared.hasNextPage
    // endregion

    /**
     * 初始化数据 (从 Intent 解析 exploreName / exploreUrl / sourceUrl)。
     *
     * 保留原 `(intent: Intent)` 签名以兼容 [ExploreShowActivity] 调用。
     * 解析 Intent 后转发到 [shared.initData]。
     *
     * - exploreName: intent.getStringExtra("exploreName")
     * - exploreUrl: intent.getStringExtra("exploreUrl")
     * - sourceUrl: intent.getStringExtra("sourceUrl") (IntentData.source 为 null 时用此查 DAO)
     */
    fun initData(intent: Intent) {
        val exploreName = intent.getStringExtra("exploreName")
        val exploreUrl = intent.getStringExtra("exploreUrl")
        val sourceUrl = intent.getStringExtra("sourceUrl")
        shared.initData(exploreName, exploreUrl, sourceUrl)
    }

    /** 是否已收藏, 转发到 [shared.isFavorite]。 */
    fun isFavorite(): Boolean = shared.isFavorite()

    /** 切换收藏, 转发到 [shared.toggleFavorite]。 */
    fun toggleFavorite() = shared.toggleFavorite()

    /**
     * 加载一页发现结果, 转发到 [shared.explore]。
     *
     * @param resetPage true=重置 page + 清 books (参数 chip 变化 / 错误重试用)
     */
    fun explore(resetPage: Boolean = false) = shared.explore(resetPage)

    /** 判断书籍是否在书架, 转发到 [shared.isInBookShelf]。 */
    fun isInBookShelf(book: BaseBook): Boolean = shared.isInBookShelf(book)

    /** 切换视频/非视频样式, 转发到 [shared.switchLayout]。 */
    fun switchLayout() = shared.switchLayout()

    /** 设置列数 (保留视频布局标志位), 转发到 [shared.setColumnCount]。 */
    fun setColumnCount(cols: Int) = shared.setColumnCount(cols)
}
