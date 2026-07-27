package io.legado.app.ui.rss

import io.legado.app.data.entities.Book
import io.legado.app.model.rss.RssHelp
import kotlinx.coroutines.flow.Flow

/**
 * RSS 源列表 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照桌面端原 `RssSourcesScreen`: 订阅 `bookDao.flowAll()` 过滤 `isRss` 的逻辑直接写在
 * Composable 的 `produceState` 内, 未走 shared 层。本类把该数据流下沉到 commonMain,
 * 让 Android / Desktop / iOS / 鸿蒙 复用同一订阅逻辑。
 *
 * app 端无独立 RssSourcesViewModel (RSS 源列表在 app 端走书架 BookshelfViewModel 的
 * isRss 过滤分支, 数据源一致), 故本类仅暴露 Flow 供多端订阅, 不引入 CRUD
 * (RSS 源的增删走书架 Book 流程)。
 *
 * # 数据流
 *
 * 业务逻辑委托 [RssHelp.flowRssSources] (订阅 bookDao.flowAll() 过滤 isRss),
 * 数据库 RSS Book 变更时自动刷新。宿主用 `produceState` / `collectAsState` 订阅。
 *
 * # 不引入 CoroutineScope
 *
 * 仅转发冷 Flow, 不需启动协程; 调用方 collect 时才执行。
 * 与 [io.legado.app.ui.book.source.BookSourceListViewModel] 的 flowSources 设计一致。
 */
class RssSourcesViewModelShared {

    /**
     * RSS 源列表数据流。
     *
     * RSS 源以 [Book] 形式存在 (type 含 BookType.rss 位), 列表行显示 `book.name` + feed URL。
     * 数据库 RSS Book 变更时自动刷新。
     */
    fun flowRssSources(): Flow<List<Book>> = RssHelp.flowRssSources()
}
