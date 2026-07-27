package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface BookGroupDao {

    @Query("select * from book_groups where groupId = :id")
    suspend fun getByID(id: Long): BookGroup?

    @Query("select * from book_groups where groupName = :groupName")
    suspend fun getByName(groupName: String): BookGroup?

    @Query("SELECT * FROM book_groups ORDER BY `order`")
    fun flowAll(): Flow<List<BookGroup>>

    @Query(
        """
        with const as (SELECT sum(groupId) sumGroupId FROM book_groups where groupId > 0)
        SELECT book_groups.* FROM book_groups join const
        where show > 0
        and (
            (groupId >= 0  and exists (select 1 from books where `group` & book_groups.groupId > 0))
            or groupId = -1
            or (groupId = -2 and exists (select 1 from books where type & ${BookType.local} > 0))
            or (groupId = -11 and exists (select 1 from books where type & ${BookType.updateError} > 0))
            or (groupId = -4
                and exists (
                    select 1 from books
                    where const.sumGroupId & `group` = 0
                )
            )
        )
        ORDER BY `order`"""
    )
    fun flowShow(): Flow<List<BookGroup>>

    @Query("SELECT * FROM book_groups where groupId >= 0 ORDER BY `order`")
    fun flowSelect(): Flow<List<BookGroup>>

    @Query("SELECT sum(groupId) FROM book_groups where groupId >= 0")
    suspend fun idsSum(): Long

    @Query("SELECT MAX(`order`) FROM book_groups where groupId >= 0")
    suspend fun maxOrder(): Int

    @Query("SELECT * FROM book_groups ORDER BY `order`")
    suspend fun all(): List<BookGroup>

    @Query("select count(*) < 64 from book_groups where groupId >= 0 or groupId == ${Long.MIN_VALUE}")
    suspend fun canAddGroup(): Boolean

    @Query("update book_groups set show = 1 where groupId = :groupId")
    suspend fun enableGroup(groupId: Long)

    @Query("select groupName from book_groups where groupId > 0 and (groupId & :id) > 0")
    suspend fun getGroupNames(id: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg bookGroup: BookGroup)

    @Update
    suspend fun update(vararg bookGroup: BookGroup)

    @Delete
    suspend fun delete(vararg bookGroup: BookGroup)

    fun isInRules(id: Long): Boolean {
        if (id < 0) {
            return true
        }
        return id and (id - 1) == 0L
    }

    suspend fun getUnusedId(): Long {
        var id = 1L
        val idsSum = idsSum()
        while (id and idsSum != 0L) {
            id = id.shl(1)
        }
        return id
    }
}
