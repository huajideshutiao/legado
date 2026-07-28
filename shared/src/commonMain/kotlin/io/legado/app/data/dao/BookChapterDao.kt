package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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

    @Query("delete from chapters where bookUrl not in (select bookUrl from books where (type & ${io.legado.app.constant.BookType.notShelf}) == 0)")
    suspend fun deleteNotShelfBookChapters()

}
