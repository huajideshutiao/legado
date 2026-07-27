package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.data.dealGroups
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.coroutine.IoDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

@Dao
interface BookSourceDao {

    @Query("select * from book_sources_part order by customOrder asc")
    fun flowAll(): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources 
        where bookSourceName like '%' || :searchKey || '%'
        or bookSourceGroup like '%' || :searchKey || '%'
        or bookSourceUrl like '%' || :searchKey || '%'
        or bookSourceComment like '%' || :searchKey || '%' 
        order by customOrder asc"""
    )
    suspend fun search(searchKey: String): List<BookSource>

    @Query(
        """select bp.*
    from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl
    where (:enabled IS NULL OR b.enabled = :enabled)
    and (b.bookSourceName like '%' || :searchKey || '%'
    or b.bookSourceGroup like '%' || :searchKey || '%'
    or b.bookSourceUrl like '%' || :searchKey || '%'
    or b.bookSourceComment like '%' || :searchKey || '%')
    order by b.customOrder asc"""
)
fun flowSearch(searchKey: String, enabled: Boolean? = null): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where bookSourceGroup = :searchKey
        or bookSourceGroup like :searchKey || ',%' 
        or bookSourceGroup like  '%,' || :searchKey
        or bookSourceGroup like  '%,' || :searchKey || ',%' 
       """
    )
    fun flowGroupSearch(searchKey: String): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where enabled = :enabled order by customOrder asc")
    fun flowEnabled(enabled: Boolean = true): Flow<List<BookSourcePart>>

    @Query("select * from book_sources where enabled = :enabled")
    suspend fun enabled(enabled: Boolean = true): List<BookSource>

    @Query(
        """select * from book_sources_part
        where enabledExplore = :enabled and hasExploreUrl = 1
        order by customOrder asc"""
    )
    fun flowExplore(enabled: Boolean = true): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where hasLoginUrl = 1")
    fun flowLogin(): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where bookSourceGroup is null or bookSourceGroup = '' or bookSourceGroup like '%未分组%'"""
    )
    fun flowNoGroup(): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part
        where enabledExplore = 1
        and hasExploreUrl = 1
        and (bookSourceGroup like '%' || :key || '%'
            or bookSourceName like '%' || :key || '%')
        order by customOrder asc"""
    )
    fun flowExplore(key: String): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part
        where enabledExplore = 1
        and hasExploreUrl = 1
        and (bookSourceGroup = :key
            or bookSourceGroup like :key || ',%'
            or bookSourceGroup like  '%,' || :key
            or bookSourceGroup like  '%,' || :key || ',%')
        order by customOrder asc"""
    )
    fun flowGroupExplore(key: String): Flow<List<BookSourcePart>>

    @Query("select distinct bookSourceGroup from book_sources where trim(bookSourceGroup) <> ''")
    fun flowGroupsUnProcessed(): Flow<List<String>>

    @Query(
        """select distinct bookSourceGroup from book_sources 
        where enabled = 1 and trim(bookSourceGroup) <> ''"""
    )
    fun flowEnabledGroupsUnProcessed(): Flow<List<String>>

    @Query(
        """select distinct bookSourceGroup from book_sources 
        where enabledExplore = 1 
        and trim(exploreUrl) <> '' 
        and trim(bookSourceGroup) <> ''
        order by customOrder"""
    )
    fun flowExploreGroupsUnProcessed(): Flow<List<String>>

    @Query(
        """select * from book_sources 
        where bookSourceGroup like '%' || :group || '%' order by customOrder asc"""
    )
    suspend fun getByGroup(group: String): List<BookSource>

    @Query(
        """select * from book_sources
        where enabled = 1 
        and (bookSourceGroup = :group
            or bookSourceGroup like :group || ',%' 
            or bookSourceGroup like  '%,' || :group
            or bookSourceGroup like  '%,' || :group || ',%')
        order by customOrder asc"""
    )
    suspend fun getEnabledByGroup(group: String): List<BookSource>


    @Query("select * from book_sources where enabled = 1 and bookSourceUrl like :baseUrl || '%'")
    suspend fun getBookSourceAddBook(baseUrl: String): BookSource?

    @Query(
        """select b.* 
        from book_sources b
        where b.enabled = 1 
        and b.bookUrlPattern is not null 
        and trim(b.bookUrlPattern) <> ''
        order by b.customOrder"""
    )
    suspend fun hasBookUrlPattern(): List<BookSource>

    @Query("select * from book_sources where bookSourceGroup is null or bookSourceGroup = ''")
    suspend fun noGroup(): List<BookSource>

    @Query("select * from book_sources order by customOrder asc")
    suspend fun all(): List<BookSource>

    @Query("select * from book_sources_part order by customOrder asc")
    suspend fun allPart(): List<BookSourcePart>

    @Query("select * from book_sources where enabled = 1 order by customOrder asc")
    suspend fun allEnabled(): List<BookSource>

    @Query("select * from book_sources_part where enabled = 1 order by customOrder asc")
    suspend fun allEnabledPart(): List<BookSourcePart>

    @Query(
        """select bp.*
        from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl 
        where b.enabled = 1 and b.bookSourceType = 0 order by b.customOrder"""
    )
    suspend fun allTextEnabledPart(): List<BookSourcePart>

    @Query(
        """select distinct bookSourceGroup from book_sources 
        where trim(bookSourceGroup) <> ''"""
    )
    suspend fun allGroupsUnProcessed(): List<String>

    @Query(
        """select distinct bookSourceGroup from book_sources 
        where enabled = 1 and trim(bookSourceGroup) <> ''"""
    )
    suspend fun allEnabledGroupsUnProcessed(): List<String>

    @Query("select * from book_sources where bookSourceUrl = :key")
    suspend fun getBookSource(key: String): BookSource?

    @Query("select * from book_sources_part where bookSourceUrl = :key")
    suspend fun getBookSourcePart(key: String): BookSourcePart?

    @Query("select * from book_sources where bookSourceUrl in (:urls)")
    suspend fun getBookSources(urls : List<String>): List<BookSource>

    suspend fun getBookSourcesFix(urls: List<String>): List<BookSource> {
        // 用 for 循环 + flatten 替代 chunked { lambda }, 因为 chunked 的 lambda 非 suspend
        val result = ArrayList<BookSource>(urls.size)
        for (chunk in urls.chunked(999)) {
            result.addAll(getBookSources(chunk))
        }
        return result
    }

    @Query("select count(*) from book_sources")
    suspend fun allCount(): Int

    @Query("SELECT EXISTS(select 1 from book_sources where bookSourceUrl = :key)")
    suspend fun has(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg bookSource: BookSource)

    @Update
    suspend fun update(vararg bookSource: BookSource)

    @Delete
    suspend fun delete(vararg bookSource: BookSource)

    @Query("delete from book_sources where bookSourceUrl = :key")
    suspend fun delete(key: String)

    @Query("delete from book_sources where bookSourceUrl in (:keys)")
    suspend fun deleteIn(keys: List<String>)

    @Transaction
    suspend fun delete(bookSources: List<BookSourcePart>) {
        // 用 for 循环替代 chunked { lambda }, 因为 chunked 的 lambda 非 suspend,
        // 无法在 @Transaction suspend 默认方法内调用 suspend deleteIn
        for (chunk in bookSources.map { it.bookSourceUrl }.chunked(999)) {
            deleteIn(chunk)
        }
    }

    @Query("select min(customOrder) from book_sources")
    suspend fun minOrder(): Int

    @Query("select max(customOrder) from book_sources")
    suspend fun maxOrder(): Int

    @Query(
        """select exists (select 1 
        from book_sources group by customOrder having count(customOrder) > 1)"""
    )
    suspend fun hasDuplicateOrder(): Boolean

    @Query("update book_sources set enabled = :enable where bookSourceUrl = :bookSourceUrl")
    suspend fun enable(bookSourceUrl: String, enable: Boolean)

    @Query("update book_sources set enabled = :enable where bookSourceUrl in (:urls)")
    suspend fun enableIn(urls: List<String>, enable: Boolean)

    @Transaction
    suspend fun enable(enable: Boolean, bookSources: List<BookSourcePart>) {
        // 留一个绑定槽位给 :enable，host parameter 总数必须 ≤ 999（SQLite 默认上限）
        // 用 for 循环替代 chunked { lambda }, 因为 chunked 的 lambda 非 suspend
        for (chunk in bookSources.map { it.bookSourceUrl }.chunked(998)) {
            enableIn(chunk, enable)
        }
    }

    @Query("update book_sources set enabledExplore = :enable where bookSourceUrl = :bookSourceUrl")
    suspend fun enableExplore(bookSourceUrl: String, enable: Boolean)

    @Query("update book_sources set enabledExplore = :enable where bookSourceUrl in (:urls)")
    suspend fun enableExploreIn(urls: List<String>, enable: Boolean)

    @Transaction
    suspend fun enableExplore(enable: Boolean, bookSources: List<BookSourcePart>) {
        // 用 for 循环替代 chunked { lambda }, 因为 chunked 的 lambda 非 suspend
        for (chunk in bookSources.map { it.bookSourceUrl }.chunked(998)) {
            enableExploreIn(chunk, enable)
        }
    }

    @Query(
        """update book_sources
        set customOrder = :customOrder where bookSourceUrl = :bookSourceUrl"""
    )
    suspend fun upOrder(bookSourceUrl: String, customOrder: Int)

    @Query("update book_sources set customOrder = customOrder + :offset")
    suspend fun shiftCustomOrder(offset: Int)

    @Transaction
    suspend fun upOrder(bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            upOrder(bs.bookSourceUrl, bs.customOrder)
        }
    }

    suspend fun upOrder(bookSource: BookSourcePart) {
        upOrder(bookSource.bookSourceUrl, bookSource.customOrder)
    }

    @Query(
        """update book_sources 
        set bookSourceGroup = :bookSourceGroup where bookSourceUrl = :bookSourceUrl"""
    )
    suspend fun upGroup(bookSourceUrl: String, bookSourceGroup: String)

    @Transaction
    suspend fun upGroup(bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            bs.bookSourceGroup?.let { upGroup(bs.bookSourceUrl, it) }
        }
    }

    suspend fun allGroups(): List<String> = dealGroups(allGroupsUnProcessed())

    suspend fun allEnabledGroups(): List<String> = dealGroups(allEnabledGroupsUnProcessed())

    suspend fun flowGroups(): Flow<List<String>> {
        return flowGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IoDispatcher)
    }

    suspend fun flowExploreGroups(): Flow<List<String>> {
        return flowExploreGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IoDispatcher)
    }

    suspend fun flowEnabledGroups(): Flow<List<String>> {
        return flowEnabledGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IoDispatcher)
    }
}
