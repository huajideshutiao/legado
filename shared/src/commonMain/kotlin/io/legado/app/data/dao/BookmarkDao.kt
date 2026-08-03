package io.legado.app.data.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import io.legado.app.data.entities.Bookmark
import io.legado.app.utils.cnCompare
import kotlinx.coroutines.flow.Flow


/**
 * 书签列表本地化 (拼音) 排序, 替代 SQL `collate localized`。
 *
 * 背景: Android 端依赖框架 SQLite 的 `setLocale(Locale.CHINESE)` 注册 localized collation;
 * 桌面 (BundledSQLiteDriver) / iOS / 鸿蒙 (NativeSQLiteDriver) 的 androidx.sqlite KMP 驱动
 * (2.7.0) 的 SQLiteConnection 接口无 collation 注册能力 (已核实: 无 createCollation 类 API),
 * SQL 中出现 `collate localized` 会直接报 "no such collation sequence: localized"。
 * 故 SQL 侧改 BINARY 排序, Kotlin 侧用 [cnCompare] (Android=android.icu Collator,
 * jvm=java.text Collator(Locale.CHINA), native=拼音表) 复刻 localized 拼音序,
 * 稳定排序保留 chapterIndex/chapterPos 的 SQL 次序, 各端排序结果与 Android 一致。
 */
internal fun List<Bookmark>.sortedByLocalizedOrder(): List<Bookmark> {
    val cn: Comparator<String> = Comparator { a, b -> a.cnCompare(b) }
    return sortedWith(
        compareBy(cn, Bookmark::bookName)
            .thenBy(cn, Bookmark::bookAuthor)
            .thenBy(Bookmark::chapterIndex)
            .thenBy(Bookmark::chapterPos)
    )
}


@Dao
interface BookmarkDao {

    @Query(
        """
        select * from bookmarks order by bookName, bookAuthor, chapterIndex, chapterPos
    """
    )
    suspend fun all(): List<Bookmark>

    @Query("select * from bookmarks order by bookName, bookAuthor, chapterIndex, chapterPos")
    fun flowAll(): Flow<List<Bookmark>>

    @Query(
        """select * from bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        order by chapterIndex"""
    )
    fun flowByBook(bookName: String, bookAuthor: String): Flow<List<Bookmark>>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and (chapterName like '%'||:key||'%' or content like '%'||:key||'%')
        order by chapterIndex"""
    )
    fun flowSearch(bookName: String, bookAuthor: String, key: String): Flow<List<Bookmark>>

    @Query(
        """select * from bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        order by chapterIndex"""
    )
    suspend fun getByBook(bookName: String, bookAuthor: String): List<Bookmark>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and (chapterName like '%'||:key||'%' or content like '%'||:key||'%')
        order by chapterIndex"""
    )
    suspend fun search(bookName: String, bookAuthor: String, key: String): List<Bookmark>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg bookmark: Bookmark)

    @Update
    suspend fun update(bookmark: Bookmark)

    @Delete
    suspend fun delete(vararg bookmark: Bookmark)

}
