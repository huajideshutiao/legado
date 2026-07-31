package io.legado.app.ui.config

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.file.AppFilesDirs
import kotlinx.coroutines.withContext

/**
 * 其它设置页纯 Kotlin 动作下沉 (shared commonMain)。
 *
 * 对照 app 端 [io.legado.app.ui.config.ConfigViewModel] 的 [clearCache] / [shrinkDatabase]:
 * 两者仅依赖 shared 已有的 Provider ([BookStorageProviders] / [AppFilesDirs] /
 * [AppDatabaseProviders] / [FileUtilsCommon]), 无 Android Context 依赖, 可下沉供全平台复用。
 *
 * Toast 提示由调用方 (sharedUiMain 路由) 在 suspend 返回后自行处理, 保持与
 * app 端 `execute {}.onSuccess { toastOnUi(...) }` 等价语义。
 *
 * 模式参考 [io.legado.app.help.book.BookHelpShared] / [io.legado.app.help.storage.BackupShared]。
 */
object ConfigActionsShared {

    /**
     * 清除缓存 (对照 app 端 `ConfigViewModel.clearCache`)。
     *
     * 1. 删除书籍章节缓存目录 (book_cache): 委托 [BookStorageProviders.get].clearCache()
     * 2. 删除应用内部缓存目录 (cacheDir): 委托 [FileUtilsCommon].delete
     *
     * 成功后调用方应 toast `clear_cache_success`。
     */
    suspend fun clearCache() {
        withContext(IoDispatcher) {
            BookStorageProviders.get().clearCache()
            FileUtilsCommon.delete(AppFilesDirs.get().cacheDir)
        }
    }

    /**
     * 收缩数据库 (对照 app 端 `ConfigViewModel.shrinkDatabase`)。
     *
     * 1. 删除不在书架的书籍章节: `bookChapterDao.deleteNotShelfBookChapters()`
     * 2. 删除不在书架的书籍: `bookDao.deleteNotShelfBook()`
     * 3. VACUUM 回收空间: `useWriterConnection { it.executeSQL("VACUUM") }`
     *
     * 成功后调用方应 toast `success`。
     */
    suspend fun shrinkDatabase() {
        withContext(IoDispatcher) {
            val appDb = AppDatabaseProviders.get().appDb
            appDb.bookChapterDao.deleteNotShelfBookChapters()
            appDb.bookDao.deleteNotShelfBook()
            // 配置 SQLiteDriver 后 openHelper 不可用, 走 driver 连接执行
            // useWriterConnection 的 block 接收 Transactor (继承 PooledConnection)
            // PooledConnection.executeSQL 是 androidx.room3 的 suspend 扩展
            appDb.useWriterConnection { it.executeSQL("VACUUM") }
        }
    }
}
