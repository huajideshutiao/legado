package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.ReadRecord

@Dao
interface ReadRecordDao {

    @get:Query("select * from readRecord where readTime >= 60000")
    val all: List<ReadRecord>

    @Query("select readTime from readRecord where bookName = :bookName and day = :day")
    fun getDayReadTime(bookName: String, day: Int): Long?

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
    fun addReadTime(bookName: String, day: Int, lastRead: Long, delta: Long) {
        insertIfAbsent(ReadRecord(bookName, day, 0L, lastRead))
        incrementReadTime(bookName, day, lastRead, delta)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(record: ReadRecord)

    @Query(
        """
        update readRecord
        set readTime = readTime + :delta,
            lastRead = case when :lastRead > lastRead then :lastRead else lastRead end
        where bookName = :bookName and day = :day"""
    )
    fun incrementReadTime(bookName: String, day: Int, lastRead: Long, delta: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg readRecord: ReadRecord)

    @Query("delete from readRecord")
    fun clear()

    @Query("delete from readRecord where bookName = :bookName")
    fun deleteByName(bookName: String)

    data class DailyStat(val day: Int, val readTime: Long)
}
