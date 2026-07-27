package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.ReadRecord

@Dao
interface ReadRecordDao {

    @Query("select * from readRecord")
    suspend fun all(): List<ReadRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(record: ReadRecord)

    @Query("delete from readRecord")
    suspend fun clear()

    @Query("delete from readRecord where bookName = :bookName")
    suspend fun deleteByName(bookName: String)

    @Query("delete from readRecord where day = :day")
    suspend fun deleteByDay(day: Int)
}
