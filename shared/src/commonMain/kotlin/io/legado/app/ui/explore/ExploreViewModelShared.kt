package io.legado.app.ui.explore

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.source.SourceHelp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 发现页源置顶/删除 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `ExploreViewModel(application: Application) : BaseViewModel(application)`:
 * - 两个方法 (`topSource` / `deleteSource`) 仅依赖 [AppDbProviders] + [SourceHelp] + 协程,
 *   不依赖 Android 专属 API, 可以下沉 commonMain 供多端复用。
 * - DAO 访问走 [AppDbProviders.get] (宿主启动时注册), 替代 app 端 `appDb` 单例。
 * - [SourceHelp] 已下沉 commonMain (走 [io.legado.app.help.source.SourceHelpAccessors]
 *   provider 间接 ReadBook/AudioPlay/SourceConfig/AppCacheManager), 直接复用。
 * - 原 `execute { ... }` (BaseViewModel 内 launch + try/catch) 改为
 *   `scope.launch(Dispatchers.IO) { ... }`, 行为等价 (DAO 方法 suspend, Room 内部切线程;
 *   保留 IO 调度器与原 `execute` 默认 Dispatchers.IO 一致)。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式 (参考 [ReplaceRuleViewModelShared]):
 * - app 端 ExploreViewModel `extends BaseViewModel`, 内部持有本类实例;
 * - 通过构造函数注入 [scope] (app 端 = `viewModelScope`);
 * - 两个方法全部转发到本类, app 端调用方接口不变。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 */
class ExploreViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /**
     * 置顶书源 (对照原 ExploreViewModel.topSource)。
     *
     * 原 `execute { val minXh = appDb.bookSourceDao.minOrder();
     *        bookSource.customOrder = minXh - 1;
     *        appDb.bookSourceDao.upOrder(bookSource) }`
     * 改为 `scope.launch(Dispatchers.IO) { ... }`, 行为等价。
     *
     * @param bookSource 待置顶的书源 (修改其 customOrder 字段为最小序号 - 1)
     */
    fun topSource(bookSource: BookSourcePart) {
        scope.launch(IoDispatcher) {
            val minXh = appDb.bookSourceDao.minOrder()
            bookSource.customOrder = minXh - 1
            appDb.bookSourceDao.upOrder(bookSource)
        }
    }

    /**
     * 删除书源 (对照原 ExploreViewModel.deleteSource)。
     *
     * 原 `execute { SourceHelp.deleteBookSource(source.bookSourceUrl) }`
     * 改为 `scope.launch(Dispatchers.IO) { ... }`, 行为等价。
     *
     * @param source 待删除的书源 (按 bookSourceUrl 删除)
     */
    fun deleteSource(source: BookSourcePart) {
        scope.launch(IoDispatcher) {
            SourceHelp.deleteBookSource(source.bookSourceUrl)
        }
    }
}
