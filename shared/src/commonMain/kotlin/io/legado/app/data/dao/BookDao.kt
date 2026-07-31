package io.legado.app.data.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface BookDao {

    fun flowByGroup(groupId: Long): Flow<List<Book>> {
        // K5-c Phase 2: 原调用 app 端 BaseBook.isNotShelf 扩展 (app/.../help/book/BookExtensions.kt),
        // DAO 下沉到 shared 后无法引用 app 端扩展; 按 BaseBook.isType(notShelf) = type and notShelf > 0
        // 内联等价改写 (BookType.notShelf 已下沉 commonMain), 行为零变化。
        return when (groupId) {
            BookGroup.IdRoot -> flowRoot()
            BookGroup.IdAll -> flowAll()
            BookGroup.IdLocal -> flowLocal()
            BookGroup.IdUngrouped -> flowNoGroup()
            BookGroup.IdError -> flowUpdateError()
            else -> flowByUserGroup(groupId)
        }.map { list ->
            list.filterNot { (it.type and BookType.notShelf) > 0 }
        }
    }

    @Query(
        """
        select * from books where type & ${BookType.text} > 0
        and type & ${BookType.local} = 0
        and ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        and (select show from book_groups where groupId = ${BookGroup.IdUngrouped}) != 1
        """
    )
    fun flowRoot(): Flow<List<Book>>

    @Query("SELECT * FROM books")
    fun flowAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE type & ${BookType.local} > 0")
    fun flowLocal(): Flow<List<Book>>

    @Query(
        """
        select * from books
        where ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        """
    )
    fun flowNoGroup(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE (`group` & :group) > 0")
    fun flowByUserGroup(group: Long): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE type & ${BookType.updateError} > 0")
    fun flowUpdateError(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE (`group` & :group) > 0")
    suspend fun getBooksByGroup(group: Long): List<Book>

    @Query("SELECT * FROM books WHERE `name` in (:names)")
    suspend fun findByName(vararg names: String): List<Book>

    @Query("select * from books where originName = :fileName")
    suspend fun getBookByFileName(fileName: String): Book?

    @Query("SELECT * FROM books WHERE bookUrl = :bookUrl")
    suspend fun getBook(bookUrl: String): Book?

    @Query("SELECT * FROM books WHERE name = :name and author = :author")
    suspend fun getBook(name: String, author: String): Book?

    @Query("""select distinct bs.* from books, book_sources bs 
        where origin == bookSourceUrl and origin not like '${BookType.localTag}%' 
        and origin not like '${BookType.webDavTag}%'""")
    suspend fun getAllUseBookSource(): List<BookSource>

    @Query("SELECT * FROM books WHERE name = :name and origin = :origin")
    suspend fun getBookByOrigin(name: String, origin: String): Book?

    @Query("SELECT name, author, bookUrl FROM books")
    fun flowShelfBookKeys(): Flow<List<ShelfBookKey>>

    @Query(
        """
        SELECT * FROM books 
        WHERE name LIKE '%' || :key || '%' COLLATE NOCASE
        OR author LIKE '%' || :key || '%' COLLATE NOCASE
        OR originName LIKE '%' || :key || '%' COLLATE NOCASE
        OR kind LIKE '%' || :key || '%' COLLATE NOCASE
        OR intro LIKE '%' || :key || '%' COLLATE NOCASE
        ORDER BY durChapterTime DESC
        """
    )
    fun searchShelfBooks(key: String): Flow<List<Book>>

    @Query("SELECT * FROM books where type & ${BookType.local} = 0")
    suspend fun webBooks(): List<Book>

    @Query("SELECT * FROM books where type & ${BookType.local} = 0 and canUpdate = 1")
    suspend fun hasUpdateBooks(): List<Book>

    @Query("SELECT * FROM books")
    suspend fun all(): List<Book>

    @Query("SELECT * FROM books where type & :type > 0 and type & ${BookType.local} = 0")
    suspend fun getByTypeOnLine(type: Int): List<Book>

    @Query("SELECT * FROM books where type & ${BookType.text} > 0 ORDER BY durChapterTime DESC limit 1")
    suspend fun lastReadBook(): Book?

    @Query("SELECT name, bookUrl FROM books")
    suspend fun allBookUrlsWithName(): List<BookFolder>

    @Query("SELECT COUNT(*) FROM books")
    suspend fun allBookCount(): Int

    @Query("select min(`order`) from books")
    suspend fun minOrder(): Int

    @Query("select max(`order`) from books")
    suspend fun maxOrder(): Int

    @Query("select exists(select 1 from books where bookUrl = :bookUrl)")
    suspend fun has(bookUrl: String): Boolean

    @Query("select exists(select 1 from books where name = :name and author = :author)")
    suspend fun has(name: String, author: String): Boolean

    @Query(
        """select exists(select 1 from books where type & ${BookType.local} > 0 
        and (originName = :fileName or (origin != '${BookType.localTag}' and origin like '%' || :fileName)))"""
    )
    suspend fun hasFile(fileName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg book: Book)

    @Update
    suspend fun update(vararg book: Book)

    @Delete
    suspend fun delete(vararg book: Book)

    @Transaction
    suspend fun replace(oldBook: Book, newBook: Book) {
        delete(oldBook)
        insert(newBook)
    }

    @Query("update books set durChapterPos = :pos where bookUrl = :bookUrl")
    suspend fun upProgress(bookUrl: String, pos: Int)

    /**
     * 仅 PATCH 进度字段, 避免阅读/播放界面退出时的整行 update 冲掉后台
     * updateToc/refreshBookInfo 写入的最新元数据.
     */
    @Query(
        """update books set
            durChapterIndex = :durChapterIndex,
            durChapterPos = :durChapterPos,
            durChapterTime = :durChapterTime,
            durChapterTitle = :durChapterTitle,
            lastCheckCount = 0
            where bookUrl = :bookUrl"""
    )
    suspend fun updateProgress(
        bookUrl: String,
        durChapterIndex: Int,
        durChapterPos: Int,
        durChapterTime: Long,
        durChapterTitle: String?
    )

    @Query("update books set `group` = :newGroupId where `group` = :oldGroupId")
    suspend fun upGroup(oldGroupId: Long, newGroupId: Long)

    @Query("update books set `group` = `group` - :group where `group` & :group > 0")
    suspend fun removeGroup(group: Long)

    @Query("delete from books where type & ${BookType.notShelf} > 0")
    suspend fun deleteNotShelfBook()
}

data class BookFolder(
    val name: String,
    val bookUrl: String
)

data class ShelfBookKey(
    val name: String,
    val author: String,
    val bookUrl: String
)
