package io.legado.app.data.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.flow.Flow

@Dao
interface BookChapterDao {

    @Query("SELECT * FROM chapters where bookUrl = :bookUrl and title like '%'||:key||'%' order by `index`")
    suspend fun search(bookUrl: String, key: String): List<BookChapter>

    @Query("SELECT * FROM chapters where bookUrl = :bookUrl and `index` >= :start and `index` <= :end and title like '%'||:key||'%' order by `index`")
    suspend fun search(bookUrl: String, key: String, start: Int, end: Int): List<BookChapter>

    @Query("select * from chapters where bookUrl = :bookUrl order by `index`")
    suspend fun getChapterList(bookUrl: String): List<BookChapter>

    @Query("select * from chapters where bookUrl = :bookUrl and `index` >= :start and `index` <= :end order by `index`")
    suspend fun getChapterList(bookUrl: String, start: Int, end: Int): List<BookChapter>

    @Query("select * from chapters where bookUrl = :bookUrl and `index` = :index")
    suspend fun getChapter(bookUrl: String, index: Int): BookChapter?

    @Query("select * from chapters where bookUrl = :bookUrl and `index` = :index")
    fun flowChapter(bookUrl: String, index: Int): Flow<BookChapter?>

    @Query("select * from chapters where bookUrl = :bookUrl and `title` = :title")
    suspend fun getChapter(bookUrl: String, title: String): BookChapter?

    @Query("select count(url) from chapters where bookUrl = :bookUrl")
    suspend fun getChapterCount(bookUrl: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg bookChapter: BookChapter)

    @Update
    suspend fun update(vararg bookChapter: BookChapter)

    @Query("delete from chapters where bookUrl = :bookUrl")
    suspend fun delByBook(bookUrl: String)

    @Query("update chapters set wordCount = :wordCount where bookUrl = :bookUrl and url = :url")
    suspend fun upWordCount(bookUrl: String, url: String, wordCount: String)

    // 仅 PATCH 单列; 调用方持有整章内存快照 (播放期/正文解析期), 整行 update 会冲掉并发写入的其他字段
    @Query("update chapters set end = :end where bookUrl = :bookUrl and url = :url")
    suspend fun upEnd(bookUrl: String, url: String, end: Long?)

    @Query("update chapters set resourceUrl = :resourceUrl where bookUrl = :bookUrl and url = :url")
    suspend fun upResourceUrl(bookUrl: String, url: String, resourceUrl: String?)

    @Query("update chapters set title = :title where bookUrl = :bookUrl and url = :url")
    suspend fun upTitle(bookUrl: String, url: String, title: String)

    @Query("delete from chapters where bookUrl not in (select bookUrl from books where (type & ${io.legado.app.constant.BookType.notShelf}) == 0)")
    suspend fun deleteNotShelfBookChapters()

}
