package io.legado.app.data.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import io.legado.app.data.entities.Server
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {

    @Query("select * from servers order by sortNumber")
    fun observeAll(): Flow<List<Server>>

    @Query("select * from servers order by sortNumber")
    suspend fun all(): List<Server>

    @Query("select * from servers where id = :id")
    suspend fun get(id: Long): Server?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg server: Server)

    @Delete
    suspend fun delete(vararg server: Server)
}
