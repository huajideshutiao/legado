package io.legado.app.data.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import io.legado.app.data.entities.TxtTocRule
import kotlinx.coroutines.flow.Flow

@Dao
interface TxtTocRuleDao {

    @Query("select * from txtTocRules order by serialNumber")
    fun observeAll(): Flow<List<TxtTocRule>>

    @Query("select * from txtTocRules order by serialNumber")
    suspend fun all(): List<TxtTocRule>

    @Query("select * from txtTocRules where enable = 1 order by serialNumber")
    suspend fun enabled(): List<TxtTocRule>

    @Query("select * from txtTocRules where enable != 1 order by serialNumber")
    suspend fun disabled(): List<TxtTocRule>

    @Query("select count(*) from txtTocRules")
    suspend fun count(): Int

    @Query("select * from txtTocRules where id = :id")
    suspend fun get(id: Long): TxtTocRule?

    @Query("select ifNull(min(serialNumber), 0) from txtTocRules")
    suspend fun minOrder(): Int

    @Query("select ifNull(max(serialNumber), 0) from txtTocRules")
    suspend fun maxOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg rule: TxtTocRule)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(vararg rule: TxtTocRule)

    @Delete
    suspend fun delete(vararg rule: TxtTocRule)

    @Query("delete from txtTocRules where id < 0")
    suspend fun deleteDefault()
}
