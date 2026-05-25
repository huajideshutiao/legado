package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordShow

@Dao
interface ReadRecordDao {

    @get:Query("select * from readRecord where readTime >= 60000")
    val all: List<ReadRecord>

    @get:Query(
        """
        select bookName,
               sum(readTime) as readTime,
               max(day) as lastRead
        from readRecord
        group by bookName
        having sum(readTime) >= 60000
        order by bookName collate localized"""
    )
    val allShow: List<ReadRecordShow>

    @get:Query("select coalesce(sum(readTime), 0) from readRecord")
    val allTime: Long

    @Query("select coalesce(sum(readTime), 0) from readRecord where bookName = :bookName")
    fun getReadTime(bookName: String): Long?

    @Query("select readTime from readRecord where bookName = :bookName and day = :day")
    fun getDayReadTime(bookName: String, day: Int): Long?

    @Query("select coalesce(sum(readTime), 0) from readRecord where day = :day")
    fun getDayTime(day: Int): Long

    @Query("select coalesce(sum(readTime), 0) from readRecord where day between :start and :end")
    fun getRangeTime(start: Int, end: Int): Long

    @Query(
        """
        select day, sum(readTime) as readTime
        from readRecord
        where day between :start and :end
        group by day
        having sum(readTime) >= 60000"""
    )
    fun getRangeStats(start: Int, end: Int): List<DailyStat>

    @Transaction
    fun addReadTime(bookName: String, day: Int, delta: Long) {
        insertIfAbsent(ReadRecord(bookName, day, 0L))
        incrementReadTime(bookName, day, delta)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(record: ReadRecord)

    @Query("update readRecord set readTime = readTime + :delta where bookName = :bookName and day = :day")
    fun incrementReadTime(bookName: String, day: Int, delta: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg readRecord: ReadRecord)

    @Update
    fun update(vararg record: ReadRecord)

    @Delete
    fun delete(vararg record: ReadRecord)

    @Query("delete from readRecord")
    fun clear()

    @Query("delete from readRecord where bookName = :bookName")
    fun deleteByName(bookName: String)

    data class DailyStat(val day: Int, val readTime: Long)
}
